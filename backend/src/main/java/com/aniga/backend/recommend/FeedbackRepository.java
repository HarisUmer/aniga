package com.aniga.backend.recommend;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Storage for what the user did with recommendations: buttons, impressions, explicit genre/tag preferences. */
@Repository
public class FeedbackRepository {

    public record Impression(int shown, int clicked) {
    }

    private final JdbcClient jdbc;

    public FeedbackRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Integer> prefs(long userId) {
        Map<String, Integer> out = new HashMap<>();
        jdbc.sql("SELECT feature, weight FROM user_pref WHERE user_id = :u").param("u", userId)
                .query(rs -> {
                    out.put(rs.getString(1), rs.getInt(2));
                });
        return out;
    }

    public void setPref(long userId, String feature, int weight) {
        if (weight == 0) {
            jdbc.sql("DELETE FROM user_pref WHERE user_id = :u AND feature = :f")
                    .param("u", userId).param("f", feature).update();
            return;
        }
        jdbc.sql("""
                        INSERT INTO user_pref (user_id, feature, weight) VALUES (:u, :f, :w)
                        ON CONFLICT (user_id, feature) DO UPDATE SET weight = EXCLUDED.weight, updated_at = now()""")
                .param("u", userId).param("f", feature).param("w", (short) Integer.signum(weight)).update();
    }

    public Map<Long, Impression> impressions(long userId) {
        Map<Long, Impression> out = new HashMap<>();
        jdbc.sql("SELECT media_id, shown, clicked FROM rec_impression WHERE user_id = :u").param("u", userId)
                .query(rs -> {
                    out.put(rs.getLong(1), new Impression(rs.getInt(2), rs.getInt(3)));
                });
        return out;
    }

    public void recordImpressions(long userId, List<Long> mediaIds, List<String> slots) {
        for (int i = 0; i < mediaIds.size(); i++) {
            jdbc.sql("""
                            INSERT INTO rec_impression (user_id, media_id, shown, last_slot) VALUES (:u, :m, 1, :s)
                            ON CONFLICT (user_id, media_id) DO UPDATE SET shown = rec_impression.shown + 1,
                                last_slot = EXCLUDED.last_slot, last_shown_at = now()""")
                    .param("u", userId).param("m", mediaIds.get(i)).param("s", slots.get(i)).update();
        }
    }

    /** Returns true if the title had been recommended (so the click is evidence about the recommender). */
    public boolean recordClick(long userId, long mediaId) {
        return jdbc.sql("UPDATE rec_impression SET clicked = clicked + 1 WHERE user_id = :u AND media_id = :m")
                .param("u", userId).param("m", mediaId).update() > 0;
    }

    public void setFeedback(long userId, long mediaId, String kind) {
        jdbc.sql("""
                        INSERT INTO rec_feedback (user_id, media_id, kind) VALUES (:u, :m, :k)
                        ON CONFLICT (user_id, media_id) DO UPDATE SET kind = EXCLUDED.kind, created_at = now()""")
                .param("u", userId).param("m", mediaId).param("k", kind).update();
    }

    public void clearFeedback(long userId, long mediaId) {
        jdbc.sql("DELETE FROM rec_feedback WHERE user_id = :u AND media_id = :m")
                .param("u", userId).param("m", mediaId).update();
    }

    public Map<Long, String> feedback(long userId) {
        Map<Long, String> out = new HashMap<>();
        jdbc.sql("SELECT media_id, kind FROM rec_feedback WHERE user_id = :u ORDER BY created_at DESC")
                .param("u", userId)
                .query(rs -> {
                    out.put(rs.getLong(1), rs.getString(2));
                });
        return out;
    }

    public int rewardCount(long userId) {
        return jdbc.sql("SELECT count(*) FROM taste_change WHERE user_id = :u").param("u", userId)
                .query(Integer.class).single();
    }

    public void event(long userId, long mediaId, String type, Float value) {
        jdbc.sql("INSERT INTO interaction_event (user_id, media_id, event_type, value) VALUES (:u, :m, :t, :v)")
                .param("u", userId).param("m", mediaId).param("t", type).param("v", value).update();
    }
}
