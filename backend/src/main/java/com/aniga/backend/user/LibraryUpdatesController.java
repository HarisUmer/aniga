package com.aniga.backend.user;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aniga.backend.media.Media;
import com.aniga.backend.media.MediaRepository;
import com.aniga.backend.read.SourceAdapter;
import com.aniga.backend.read.TtlCache;

/**
 * "Updates" feed: for every comic being read on a pinned source, check the source's live chapter list and
 * report the ones published past what the reader has already seen. This is what powers the Library updates
 * section and the new-chapter notification — the app polls it and compares against {@code chapters_known}.
 */
@RestController
@RequestMapping("/api/users/{userId}/updates")
public class LibraryUpdatesController {

    /** newCount is how many whole chapters past {@code chaptersKnown} the source now lists. */
    public record Update(Media media, String sourceCode, String seriesId, String seriesTitle, String sourceLang,
                         double latestChapter, Double chaptersKnown, int newCount) {
    }

    private static final long DEADLINE_S = 20;

    private final JdbcClient jdbc;
    private final MediaRepository media;
    private final List<SourceAdapter> adapters;
    // Latest chapter number per (source, series, lang). Short TTL so the feed is fresh but sites aren't hammered.
    private final TtlCache<Double> latest = new TtlCache<>(Duration.ofMinutes(10), 500);

    public LibraryUpdatesController(JdbcClient jdbc, MediaRepository media, List<SourceAdapter> adapters) {
        this.jdbc = jdbc;
        this.media = media;
        this.adapters = adapters;
    }

    private record Row(long mediaId, String sourceCode, String seriesId, String seriesTitle, String sourceLang,
                       int progress, Double chaptersKnown) {
    }

    @GetMapping
    public List<Update> updates(@PathVariable long userId) {
        List<Row> rows = jdbc.sql("""
                        SELECT media_id, source_code, series_id, series_title, source_lang, progress, chapters_known
                        FROM library_entry
                        WHERE user_id = :u AND status IN ('CURRENT', 'PAUSED', 'PLANNING')
                          AND source_code IS NOT NULL AND series_id IS NOT NULL""")
                .param("u", userId)
                .query((rs, i) -> new Row(rs.getLong("media_id"), rs.getString("source_code"),
                        rs.getString("series_id"), rs.getString("series_title"), rs.getString("source_lang"),
                        rs.getInt("progress"), (Double) rs.getObject("chapters_known")))
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Callable<Update>> calls = new ArrayList<>();
        for (Row r : rows) {
            calls.add(() -> toUpdate(r));
        }
        List<Update> out = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var f : pool.invokeAll(calls, DEADLINE_S, TimeUnit.SECONDS)) {
                try {
                    Update u = f.get();
                    if (u != null) {
                        out.add(u);
                    }
                } catch (Exception ignored) {
                    // A source that is down or slow just means no update row for that title this round.
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        out.sort((a, b) -> Integer.compare(b.newCount(), a.newCount()));
        return out;
    }

    private Update toUpdate(Row r) {
        double seen = r.chaptersKnown() != null ? r.chaptersKnown() : r.progress();
        double live = latestChapter(r.sourceCode(), r.seriesId(), r.sourceLang());
        int newCount = (int) (Math.floor(live) - Math.floor(seen));
        if (live <= 0 || newCount <= 0) {
            return null;
        }
        Media m = media.byIds(List.of(r.mediaId())).stream().findFirst().orElse(null);
        return new Update(m, r.sourceCode(), r.seriesId(), r.seriesTitle(), r.sourceLang(),
                live, r.chaptersKnown(), newCount);
    }

    private double latestChapter(String code, String seriesId, String lang) {
        String key = code + "|" + seriesId + "|" + (lang == null ? "en" : lang);
        return latest.get(key, () -> {
            SourceAdapter a = adapter(code);
            if (a == null) {
                return 0.0;
            }
            double max = 0;
            for (SourceAdapter.ChapterRef c : a.chapters(seriesId, lang == null ? "en" : lang)) {
                try {
                    max = Math.max(max, Double.parseDouble(c.number()));
                } catch (NumberFormatException ignored) {
                    // Oneshots / non-numeric labels don't move the "latest number".
                }
            }
            return max;
        });
    }

    private SourceAdapter adapter(String code) {
        return adapters.stream().filter(a -> a.info().code().equals(code)).findFirst().orElse(null);
    }
}
