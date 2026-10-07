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

    /** All fields optional: send only what changed. */
    public record Update(String status, Integer progress, Integer score) {
    }

    public record Entry(Media media, String status, int progress, Integer score, String updatedAt) {
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
        var sql = new StringBuilder("""
                SELECT m.*, l.status AS lib_status, l.progress, l.updated_at::text AS updated, r.score
                FROM library_entry l JOIN media m ON m.id = l.media_id
                LEFT JOIN rating r ON r.user_id = l.user_id AND r.media_id = l.media_id
                WHERE l.user_id = :u""");
        if (status != null && !status.isBlank()) {
            sql.append(" AND l.status = :s");
        }
        if (type != null && !type.isBlank()) {
            sql.append(" AND m.media_type = :t");
        }
        sql.append(" ORDER BY l.updated_at DESC");
        var stmt = jdbc.sql(sql.toString()).param("u", userId);
        if (status != null && !status.isBlank()) {
            stmt = stmt.param("s", status.toUpperCase());
        }
        if (type != null && !type.isBlank()) {
            stmt = stmt.param("t", type.toUpperCase());
        }
        return stmt.query((rs, i) -> new Entry(MediaRepository.map(rs, i), rs.getString("lib_status"),
                rs.getInt("progress"), (Integer) rs.getObject("score"), rs.getString("updated"))).list();
    }

    @GetMapping("/{mediaId}")
    public Map<String, Object> get(@PathVariable long userId, @PathVariable long mediaId) {
        requireUser(userId);
        Optional<Map<String, Object>> entry = find(userId, mediaId);
        Integer score = ratings.scoresByMedia(userId).get(mediaId);
        return Map.of("mediaId", mediaId,
                "status", entry.map(e -> e.get("status")).orElse("NONE"),
                "progress", entry.map(e -> e.get("progress")).orElse(0),
                "score", score == null ? 0 : score);
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
            jdbc.sql("""
                            INSERT INTO library_entry (user_id, media_id, status, progress, updated_at)
                            VALUES (:u, :m, :s, :p, now())
                            ON CONFLICT (user_id, media_id) DO UPDATE SET status = EXCLUDED.status,
                                progress = EXCLUDED.progress, updated_at = now()""")
                    .param("u", userId).param("m", mediaId).param("s", status).param("p", progress).update();
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
        return jdbc.sql("SELECT status, progress FROM library_entry WHERE user_id = :u AND media_id = :m")
                .param("u", userId).param("m", mediaId)
                .query((rs, i) -> Map.<String, Object>of("status", rs.getString(1), "progress", rs.getInt(2)))
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
