package com.aniga.backend.user;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.aniga.backend.recommend.FeedbackEvent;

@Repository
public class RatingRepository {

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    public RatingRepository(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    public Map<Long, Integer> scoresByMedia(long userId) {
        Map<Long, Integer> scores = new LinkedHashMap<>();
        jdbc.sql("SELECT media_id, score FROM rating WHERE user_id = :u ORDER BY rated_at")
                .param("u", userId)
                .query(rs -> {
                    scores.put(rs.getLong(1), rs.getInt(2));
                });
        return scores;
    }

    public boolean userExists(long userId) {
        return jdbc.sql("SELECT count(*) FROM app_user WHERE id = :u").param("u", userId)
                .query(Long.class).single() > 0;
    }

    /** Also appends a RATE event so the sequence model and RL reward log see every rating change. */
    public void upsert(long userId, long mediaId, int score) {
        jdbc.sql("""
                        INSERT INTO rating (user_id, media_id, score) VALUES (:u, :m, :s)
                        ON CONFLICT (user_id, media_id) DO UPDATE SET score = EXCLUDED.score, rated_at = now()""")
                .param("u", userId).param("m", mediaId).param("s", score)
                .update();
        jdbc.sql("""
                        INSERT INTO interaction_event (user_id, media_id, event_type, value)
                        VALUES (:u, :m, 'RATE', :s)""")
                .param("u", userId).param("m", mediaId).param("s", (float) score)
                .update();
        // Every rating — including changing an old one — is a reward for the online learner.
        events.publishEvent(FeedbackEvent.fromScore(userId, mediaId, score));
    }
}
