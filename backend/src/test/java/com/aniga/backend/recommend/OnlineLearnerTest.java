package com.aniga.backend.recommend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aniga.backend.media.Media;

class OnlineLearnerTest {

    static Media media(long id, String type, List<String> genres, List<String> tags) {
        return new Media(id, type, "t" + id, "", genres, tags, 12, null, 2020, 50, null, null, null, null,
                null, "TV", "FINISHED", null, List.of());
    }

    private static final Media GORE = media(1, "ANIME", List.of("Horror", "Action"), List.of("gore"));
    private static final Media ROMCOM = media(2, "MANGA", List.of("Romance", "Comedy"), List.of("school"));

    @Test
    void featuresAreUnitLengthAndTagsWeighHalf() {
        Map<String, Double> x = OnlineLearner.features(GORE);
        double norm = Math.sqrt(x.values().stream().mapToDouble(v -> v * v).sum());
        assertEquals(1.0, norm, 1e-9);
        assertEquals(x.get("g:Horror") / 2, x.get("t:gore"), 1e-9);
        assertTrue(x.containsKey("type:ANIME"));
    }

    @Test
    void emptyModelIsUndecided() {
        assertEquals(0.5, OnlineLearner.predict(Map.of(), OnlineLearner.features(GORE)), 1e-12);
    }

    @Test
    void likeRaisesAndDislikeLowersOnlyThatTitlesFeatures() {
        Map<String, OnlineLearner.Weight> w = new HashMap<>();
        w.putAll(OnlineLearner.step(w, OnlineLearner.features(GORE), 1.0));
        double liked = w.get("g:Horror").weight();
        assertTrue(liked > 0);
        assertTrue(!w.containsKey("g:Romance"), "a gore rating must not touch Romance");

        // The user's taste drifts: the next gore title is rated low and the weight comes back down.
        w.putAll(OnlineLearner.step(w, OnlineLearner.features(GORE), 0.1));
        assertTrue(w.get("g:Horror").weight() < liked);
        assertEquals(2 * OnlineLearner.features(GORE).get("g:Horror"), w.get("g:Horror").n(), 1e-9);
    }

    @Test
    void expectedOutcomesTeachLittle() {
        Map<String, Double> x = OnlineLearner.features(ROMCOM);
        Map<String, OnlineLearner.Weight> w = new HashMap<>();
        for (int i = 0; i < 8; i++) {
            w.putAll(OnlineLearner.step(w, x, 1.0));
        }
        double before = w.get("g:Romance").weight();
        w.putAll(OnlineLearner.step(w, x, 1.0));
        double surprise = OnlineLearner.step(w, x, 0.0).get("g:Romance").weight() - w.get("g:Romance").weight();
        assertTrue(w.get("g:Romance").weight() - before < Math.abs(surprise) / 3,
                "a confirmed like should move the weight far less than a surprising dislike");
        assertTrue(OnlineLearner.predict(w, x) > 0.8);
    }

    @Test
    void weightsAreClamped() {
        Map<String, Double> x = Map.of("g:Horror", 1.0);
        Map<String, OnlineLearner.Weight> w = new HashMap<>(Map.of("g:Horror", new OnlineLearner.Weight(-2.9, 5)));
        for (int i = 0; i < 50; i++) {
            w.putAll(OnlineLearner.step(w, x, 0.0));
        }
        assertTrue(w.get("g:Horror").weight() >= -OnlineLearner.CLAMP);
    }

    @Test
    void uncertaintyShrinksWithEvidence() {
        Map<String, Double> x = OnlineLearner.features(GORE);
        double fresh = OnlineLearner.uncertainty(Map.of(), x);
        Map<String, OnlineLearner.Weight> w = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            w.putAll(OnlineLearner.step(w, x, 1.0));
        }
        assertTrue(OnlineLearner.uncertainty(w, x) < fresh / 1.5);
    }
}
