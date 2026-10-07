package com.aniga.backend.recommend;

/**
 * A reward for one title, published by whoever observed it (rating, shelf change, recommendation button).
 * reward is in [0, 1]: 1 = loved it, 0.5 = neutral, 0 = clearly not for me.
 */
public record FeedbackEvent(long userId, long mediaId, double reward, String cause) {

    /** A 1–10 score as a reward: 1 → 0, 10 → 1. */
    public static FeedbackEvent fromScore(long userId, long mediaId, int score) {
        return new FeedbackEvent(userId, mediaId, (score - 1) / 9.0, "rated " + score + "/10");
    }
}
