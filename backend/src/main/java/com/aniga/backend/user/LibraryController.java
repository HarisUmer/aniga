package com.aniga.backend.user;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aniga.backend.media.Media;
import com.aniga.backend.media.MediaRepository;
import com.aniga.backend.recommend.FeedbackEvent;

/**
 * A user's shelf: status + progress (episodes watched for anime, chapters read for comics) + optional score.
 * Every change also lands in interaction_event, which is what the recommenders train on.
 */
@RestController
@RequestMapping("/api/users/{userId}/library")
public class LibraryController {

    static final Set<String> STATUSES = Set.of("PLANNING", "CURRENT", "COMPLETED", "DROPPED", "PAUSED");

    /** All fields optional: send only what changed. The source* fields pin where the comic is read. */
    public record Update(String status, Integer progress, Integer score,
                         String sourceCode, String seriesId, String seriesTitle, String sourceLang,
                         Double chaptersKnown) {
        public Update(String status, Integer progress, Integer score) {
            this(status, progress, score, null, null, null, null, null);
        }
    }

    public record Entry(Media media, String status, int progress, Integer score, String updatedAt,
                        String sourceCode, String seriesId, String seriesTitle, String sourceLang,
                        Double chaptersKnown) {
    }

    private final JdbcClient jdbc;
    private final RatingRepository ratings;
    private final MediaRepository media;
    private final ApplicationEventPublisher events;

    public LibraryController(JdbcClient jdbc, RatingRepository ratings, MediaRepository media,
                             ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.ratings = ratings;
        this.media = media;
        this.events = events;
    }

    @GetMapping
    public List<Entry> list(@PathVariable long userId, @RequestParam(required = false) String status,
                            @RequestParam(required = false) String type) {
        requireUser(userId);
        String want = status == null ? "" : status.trim().toUpperCase();
        List<Entry> rows = new java.util.ArrayList<>(shelfRows(userId, want, type));
        // A score with no shelf row was invisible. "All" and "Rated" include those likes.
        if (want.isEmpty() || "RATED".equals(want)) {
            rows.addAll(ratedOnly(userId, type));
        }
        rows.sort((a, b) -> {
            String au = a.updatedAt() == null ? "" : a.updatedAt();
            String bu = b.updatedAt() == null ? "" : b.updatedAt();
            return bu.compareTo(au);
        });
        return rows;
    }

    private List<Entry> shelfRows(long userId, String status, String type) {
        if ("RATED".equals(status)) {
            return List.of();
        }
        var sql = new StringBuilder("""
                SELECT m.*, l.status AS lib_status, l.progress, l.updated_at::text AS updated, r.score,
                       l.source_code, l.series_id, l.series_title, l.source_lang, l.chapters_known
                FROM library_entry l JOIN media m ON m.id = l.media_id
                LEFT JOIN rating r ON r.user_id = l.user_id AND r.media_id = l.media_id
                WHERE l.user_id = :u""");
        if (!status.isEmpty()) {
            sql.append(" AND l.status = :s");
        }
        if (type != null && !type.isBlank()) {
            sql.append(" AND m.media_type = :t");
        }
        sql.append(" ORDER BY l.updated_at DESC");
        var stmt = jdbc.sql(sql.toString()).param("u", userId);
        if (!status.isEmpty()) {
            stmt = stmt.param("s", status);
        }
        if (type != null && !type.isBlank()) {
            stmt = stmt.param("t", type.toUpperCase());
        }
        return stmt.query((rs, i) -> new Entry(MediaRepository.map(rs, i), rs.getString("lib_status"),
                rs.getInt("progress"), (Integer) rs.getObject("score"), rs.getString("updated"),
                rs.getString("source_code"), rs.getString("series_id"), rs.getString("series_title"),
                rs.getString("source_lang"), (Double) rs.getObject("chapters_known"))).list();
    }

    /** Scores that never got a shelf status. These were the likes the library dropped. */
    private List<Entry> ratedOnly(long userId, String type) {
        var sql = new StringBuilder("""
                SELECT m.*, r.score, r.rated_at::text AS updated
                FROM rating r JOIN media m ON m.id = r.media_id
                WHERE r.user_id = :u
                  AND NOT EXISTS (
                    SELECT 1 FROM library_entry l
                    WHERE l.user_id = r.user_id AND l.media_id = r.media_id)""");
        if (type != null && !type.isBlank()) {
            sql.append(" AND m.media_type = :t");
        }
        var stmt = jdbc.sql(sql.toString()).param("u", userId);
        if (type != null && !type.isBlank()) {
            stmt = stmt.param("t", type.toUpperCase());
        }
        return stmt.query((rs, i) -> new Entry(MediaRepository.map(rs, i), "RATED",
                0, rs.getInt("score"), rs.getString("updated"), null, null, null, null, null)).list();
    }

    @GetMapping("/{mediaId}")
    public Map<String, Object> get(@PathVariable long userId, @PathVariable long mediaId) {
        requireUser(userId);
        Optional<Map<String, Object>> entry = find(userId, mediaId);
        Integer score = ratings.scoresByMedia(userId).get(mediaId);
        Object status = entry.map(e -> e.get("status")).orElse("NONE");
        if (status == null) {
            status = "NONE";
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("mediaId", mediaId);
        body.put("status", status);
        body.put("progress", entry.map(e -> e.get("progress")).orElse(0));
        body.put("score", score == null ? 0 : score);
        entry.ifPresent(e -> {
            body.put("sourceCode", e.get("source_code"));
            body.put("seriesId", e.get("series_id"));
            body.put("seriesTitle", e.get("series_title"));
            body.put("sourceLang", e.get("source_lang"));
            body.put("chaptersKnown", e.get("chapters_known"));
        });
        return body;
    }

    @PutMapping("/{mediaId}")
    @Transactional
    public Map<String, Object> update(@PathVariable long userId, @PathVariable long mediaId,
                                      @RequestBody Update body) {
        requireUser(userId);
        Media m = media.byIds(List.of(mediaId)).stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("media " + mediaId + " not found"));
        Optional<Map<String, Object>> before = find(userId, mediaId);
        int oldProgress = before.map(e -> (Integer) e.get("progress")).orElse(0);
        String oldStatus = before.map(e -> (String) e.get("status")).orElse(null);

        String status = body.status() == null ? oldStatus : body.status().toUpperCase();
        if (status != null && !STATUSES.contains(status)) {
            throw new IllegalArgumentException("status must be one of " + STATUSES);
        }
        int progress = body.progress() == null ? oldProgress : Math.max(0, body.progress());
        Integer total = m.total();
        if (total != null && progress > total) {
            progress = total;
        }
        if (status == null) {
            status = progress > 0 ? "CURRENT" : "PLANNING";
        }
        if (body.progress() != null && total != null && progress == total && body.status() == null) {
            status = "COMPLETED";
        }
        if (body.progress() != null && progress > 0 && "PLANNING".equals(status) && body.status() == null) {
            status = "CURRENT";
        }

        // Rating something you never shelved ("I saw this years ago: 8") must not invent a PLANNING entry.
        boolean scoreOnly = body.status() == null && body.progress() == null;
        if (!(scoreOnly && before.isEmpty())) {
            // COALESCE keeps a previously-pinned source when this update only changes status/progress.
            jdbc.sql("""
                            INSERT INTO library_entry (user_id, media_id, status, progress, updated_at,
                                source_code, series_id, series_title, source_lang, chapters_known)
                            VALUES (:u, :m, :s, :p, now(), :sc, :sid, :stitle, :slang, :known)
                            ON CONFLICT (user_id, media_id) DO UPDATE SET status = EXCLUDED.status,
                                progress = EXCLUDED.progress, updated_at = now(),
                                source_code = COALESCE(EXCLUDED.source_code, library_entry.source_code),
                                series_id = COALESCE(EXCLUDED.series_id, library_entry.series_id),
                                series_title = COALESCE(EXCLUDED.series_title, library_entry.series_title),
                                source_lang = COALESCE(EXCLUDED.source_lang, library_entry.source_lang),
                                chapters_known = GREATEST(
                                    COALESCE(EXCLUDED.chapters_known, library_entry.chapters_known),
                                    COALESCE(library_entry.chapters_known, EXCLUDED.chapters_known))""")
                    .param("u", userId).param("m", mediaId).param("s", status).param("p", progress)
                    .param("sc", body.sourceCode()).param("sid", body.seriesId())
                    .param("stitle", body.seriesTitle()).param("slang", body.sourceLang())
                    .param("known", body.chaptersKnown())
                    .update();
        }

        if (progress > oldProgress) {
            event(userId, mediaId, "ANIME".equals(m.mediaType()) ? "WATCH_EPISODE" : "READ_CHAPTER",
                    progress - oldProgress);
        }
        if ("DROPPED".equals(status) && !"DROPPED".equals(oldStatus)) {
            float completion = total == null || total == 0 ? 0 : (float) progress / total;
            event(userId, mediaId, "DROP", completion);
            // Dropping after episode 1 says more than dropping near the end.
            if (body.score() == null) {
                events.publishEvent(new FeedbackEvent(userId, mediaId, 0.1 + 0.25 * completion, "dropped it"));
            }
        }
        if ("COMPLETED".equals(status) && !"COMPLETED".equals(oldStatus) && body.score() == null) {
            events.publishEvent(new FeedbackEvent(userId, mediaId, 0.75, "finished it"));
        }
        if (body.score() != null) {
            if (body.score() == 0) {
                jdbc.sql("DELETE FROM rating WHERE user_id = :u AND media_id = :m")
                        .param("u", userId).param("m", mediaId).update();
            } else if (body.score() < 1 || body.score() > 10) {
                throw new IllegalArgumentException("score must be between 1 and 10 (0 clears it)");
            } else {
                ratings.upsert(userId, mediaId, body.score());
            }
        }
        return get(userId, mediaId);
    }

    @DeleteMapping("/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable long userId, @PathVariable long mediaId) {
        jdbc.sql("DELETE FROM library_entry WHERE user_id = :u AND media_id = :m")
                .param("u", userId).param("m", mediaId).update();
    }

    private Optional<Map<String, Object>> find(long userId, long mediaId) {
        return jdbc.sql("""
                        SELECT status, progress, source_code, series_id, series_title, source_lang, chapters_known
                        FROM library_entry WHERE user_id = :u AND media_id = :m""")
                .param("u", userId).param("m", mediaId)
                .query((rs, i) -> {
                    Map<String, Object> row = new java.util.HashMap<>();
                    row.put("status", rs.getString("status"));
                    row.put("progress", rs.getInt("progress"));
                    row.put("source_code", rs.getString("source_code"));
                    row.put("series_id", rs.getString("series_id"));
                    row.put("series_title", rs.getString("series_title"));
                    row.put("source_lang", rs.getString("source_lang"));
                    row.put("chapters_known", rs.getObject("chapters_known"));
                    return row;
                })
                .optional();
    }

    private void event(long userId, long mediaId, String type, float value) {
        jdbc.sql("INSERT INTO interaction_event (user_id, media_id, event_type, value) VALUES (:u, :m, :t, :v)")
                .param("u", userId).param("m", mediaId).param("t", type).param("v", value).update();
    }

    private void requireUser(long userId) {
        if (!ratings.userExists(userId)) {
            throw new NoSuchElementException("user " + userId + " not found");
        }
    }
}
