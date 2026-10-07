package com.aniga.backend.recommend;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Turns ratings + library progress into one signal in [-1, 1] per title. MUST stay identical to signals() in
 * ml/train/train_recommenders.py — the model was trained on exactly this definition.
 */
@Repository
public class UserSignals {

    /** ageDays: how long ago this signal last changed; serving uses it to let recent taste outweigh old taste. */
    public record Signal(long mediaId, double value, Integer score, String status, double completion,
                         double ageDays, String feedback) {
    }

    /** "Like" / "Not interested" on a recommendation when there is no rating to say more. */
    static final double FEEDBACK_SIGNAL = 0.6;

    private final JdbcClient jdbc;

    public UserSignals(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Long, Signal> load(long userId) {
        record Row(long mediaId, Integer score, String status, int progress, Integer total, double age, String fb) {
        }
        var rows = jdbc.sql("""
                        SELECT coalesce(l.media_id, r.media_id, f.media_id) AS media_id, r.score, l.status,
                               coalesce(l.progress, 0) AS progress, f.kind,
                               CASE WHEN m.media_type = 'ANIME' THEN m.episodes ELSE m.chapters END AS total,
                               extract(epoch FROM now() - greatest(l.updated_at, r.rated_at, f.created_at)) / 86400 AS age
                        FROM (SELECT * FROM library_entry WHERE user_id = :u) l
                        FULL OUTER JOIN (SELECT * FROM rating WHERE user_id = :u) r ON r.media_id = l.media_id
                        FULL OUTER JOIN (SELECT * FROM rec_feedback WHERE user_id = :u) f
                            ON f.media_id = coalesce(l.media_id, r.media_id)
                        JOIN media m ON m.id = coalesce(l.media_id, r.media_id, f.media_id)
                        ORDER BY greatest(l.updated_at, r.rated_at, f.created_at)""")
                .param("u", userId)
                .query((rs, i) -> new Row(rs.getLong("media_id"), (Integer) rs.getObject("score"),
                        rs.getString("status"), rs.getInt("progress"), (Integer) rs.getObject("total"),
                        rs.getDouble("age"), rs.getString("kind")))
                .list();
        double sum = 0;
        int rated = 0;
        for (Row r : rows) {
            if (r.score() != null) {
                sum += r.score();
                rated++;
            }
        }
        double pivot = rated >= 3 ? sum / rated : 5.5;
        Map<Long, Signal> out = new LinkedHashMap<>();
        for (Row r : rows) {
            double completion = r.total() == null || r.total() == 0 ? 0 : Math.min(1.0, (double) r.progress() / r.total());
            double v = withFeedback(signal(r.score(), r.status(), completion, pivot), r.score(), r.fb());
            out.put(r.mediaId(), new Signal(r.mediaId(), v, r.score(), r.status(), completion, r.age(), r.fb()));
        }
        return out;
    }

    static double signal(Integer score, String status, double completion, double pivot) {
        double s;
        if (score != null) {
            s = clamp((score - pivot) / 4.5) + ("COMPLETED".equals(status) ? 0.15 : 0) - ("DROPPED".equals(status) ? 0.15 : 0);
        } else if (status == null) {
            s = 0;
        } else {
            s = switch (status) {
                case "COMPLETED" -> 0.5;
                case "CURRENT" -> 0.2 + 0.3 * completion;
                case "PAUSED", "PLANNING" -> 0.1;
                case "DROPPED" -> -0.3 - 0.4 * (1 - completion);
                default -> 0;
            };
        }
        return clamp(s);
    }

    /** A rating is the strongest statement and wins; otherwise the button sets a floor (LIKE) or ceiling (NOT_INTERESTED). */
    static double withFeedback(double value, Integer score, String feedback) {
        if (feedback == null || score != null) {
            return value;
        }
        return "LIKE".equals(feedback) ? Math.max(value, FEEDBACK_SIGNAL) : Math.min(value, -FEEDBACK_SIGNAL);
    }

    /** Serving-time recency: a signal loses half its pull on the user vector every HALF_LIFE_DAYS. */
    static final double HALF_LIFE_DAYS = 120;

    static double recency(double ageDays) {
        return Math.max(0.15, Math.pow(0.5, Math.max(0, ageDays) / HALF_LIFE_DAYS));
    }

    private static double clamp(double v) {
        return Math.max(-1, Math.min(1, v));
    }
}
