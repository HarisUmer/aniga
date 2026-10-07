package com.aniga.backend.read;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.aniga.backend.mangadex.Json;

/**
 * xkcd (CC BY-NC 2.5). Two techniques in one adapter:
 * - HTML scraping with Jsoup: /archive/ lists every comic as {@code <a href="/N/" title="date">Title</a>}.
 * - JSON API: /N/info.0.json gives the image URL and alt text for one comic.
 * Comics are grouped into "chapters" of 25 so the vertical reader has something to scroll.
 */
@Component
public class XkcdAdapter implements SourceAdapter {

    static final String SERIES_ID = "xkcd";
    static final int BLOCK = 25;
    private static final String BASE = "https://xkcd.com";
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    record Comic(int num, String title, String date) {
    }

    private record Strip(String img, String alt, String title) {
    }

    private final RestClient http;
    private final String userAgent;
    private final TtlCache<List<Comic>> archive = new TtlCache<>(Duration.ofHours(6), 2);
    private final TtlCache<Strip> strips = new TtlCache<>(Duration.ofDays(30), 5000);

    public XkcdAdapter(@Value("${aniga.mangadex.user-agent}") String userAgent) {
        this.userAgent = userAgent;
        this.http = RestClient.builder().baseUrl(BASE).defaultHeader("User-Agent", userAgent).build();
    }

    @Override
    public Info info() {
        return new Info("xkcd", "xkcd", "OFFICIAL_API", BASE,
                "CC BY-NC 2.5 — Randall Munroe, xkcd.com", List.of("en"), "en", false);
    }

    List<Comic> archive() {
        return archive.get("archive", () -> {
            try {
                Document doc = Jsoup.connect(BASE + "/archive/").userAgent(userAgent).timeout(20_000).get();
                return parseArchive(doc);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("xkcd archive unavailable: " + e.getMessage(), e);
            }
        });
    }

    static List<Comic> parseArchive(Document doc) {
        List<Comic> out = new ArrayList<>();
        for (var a : doc.select("#middleContainer a[href]")) {
            String href = a.attr("href").replace("/", "");
            if (!href.chars().allMatch(Character::isDigit) || href.isEmpty()) {
                continue;
            }
            out.add(new Comic(Integer.parseInt(href), a.text(), a.attr("title")));
        }
        out.sort(Comparator.comparingInt(Comic::num));
        return out;
    }

    @Override
    public List<Series> popular(String lang, int page) {
        return page == 0 ? List.of(series(SERIES_ID)) : List.of();
    }

    @Override
    public List<Series> search(String query, String lang, int page) {
        return query.toLowerCase(Locale.ROOT).contains("xkcd") ? popular(lang, page) : List.of();
    }

    @Override
    public Series series(String id) {
        if (!SERIES_ID.equals(id)) {
            throw new NoSuchElementException("xkcd has a single series: " + SERIES_ID);
        }
        return new Series("xkcd", SERIES_ID, "xkcd",
                "A webcomic of romance, sarcasm, math, and language — stick figures explaining science, "
                        + "programming and everyday absurdity, with a hidden joke in every image's alt text.",
                "https://imgs.xkcd.com/static/terrible_small_logo.png", "WEBCOMIC", "ongoing", 2005,
                List.of("Comedy", "Science", "Programming"), "Randall Munroe");
    }

    @Override
    public List<ChapterRef> chapters(String seriesId, String lang) {
        series(seriesId);
        List<Comic> comics = archive();
        List<ChapterRef> out = new ArrayList<>();
        for (int start = 0; start < comics.size(); start += BLOCK) {
            List<Comic> block = comics.subList(start, Math.min(start + BLOCK, comics.size()));
            int from = block.getFirst().num();
            int to = block.getLast().num();
            out.add(new ChapterRef(from + "-" + to, String.valueOf(start / BLOCK + 1),
                    "#" + from + "–" + to + " · " + block.getFirst().title(), "en", block.size(), "Randall Munroe",
                    block.getFirst().date(), null));
        }
        return out;
    }

    @Override
    public Pages pages(String chapterId, String lang) {
        String[] range = chapterId.split("-");
        if (range.length != 2) {
            throw new IllegalArgumentException("xkcd chapter id must look like 1-25");
        }
        int from = Integer.parseInt(range[0]);
        int to = Integer.parseInt(range[1]);
        if (to < from || to - from > 60) {
            throw new IllegalArgumentException("xkcd chapter range too large");
        }
        List<Strip> loaded = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Strip>> futures = new ArrayList<>();
            for (int n = from; n <= to; n++) {
                final int num = n;
                futures.add(pool.submit(() -> strip(num)));
            }
            for (Future<Strip> f : futures) {
                try {
                    loaded.add(f.get());
                } catch (Exception e) {
                    loaded.add(null);
                }
            }
        }
        List<Strip> ok = loaded.stream().filter(Objects::nonNull).filter(s -> s.img() != null).toList();
        return new Pages(chapterId, ok.stream().map(Strip::img).toList(), List.of(),
                ok.stream().map(s -> s.title() + " — " + s.alt()).toList(), "xkcd by Randall Munroe — CC BY-NC 2.5");
    }

    /** #404 is intentionally missing from xkcd; a 404 just means "no strip". */
    private Strip strip(int num) {
        return strips.get(String.valueOf(num), () -> {
            try {
                Map<String, Object> body = http.get().uri("/{n}/info.0.json", num).retrieve().body(JSON);
                return new Strip(Json.str(body.get("img")), Json.str(body.get("alt")), Json.str(body.get("safe_title")));
            } catch (HttpClientErrorException.NotFound e) {
                return new Strip(null, null, null);
            }
        });
    }
}
