package com.aniga.backend.recommend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aniga.backend.media.Media;

class RecommenderV2HelpersTest {

    @Test
    void labelsReadLikeWords() {
        assertEquals("Romance", RecommenderV2.label("g:Romance"));
        assertEquals("time skip", RecommenderV2.label("t:time-skip"));
        assertEquals("manhwa", RecommenderV2.label("type:MANHWA"));
    }

    @Test
    void sequelsShareAFranchise() {
        String base = RecommenderV2.franchise("Attack on Titan");
        assertEquals(base, RecommenderV2.franchise("Attack on Titan Season 3 Part 2"));
        assertEquals(base, RecommenderV2.franchise("Attack on Titan: The Final Season"));
        assertEquals(RecommenderV2.franchise("Mob Psycho 100"), RecommenderV2.franchise("Mob Psycho 100 II"));
        assertNotEquals(base, RecommenderV2.franchise("Vinland Saga"));
        assertEquals("", RecommenderV2.franchise(null));
    }

    private static RecommenderV2.Recommendation rec(long id, String title, double score) {
        Media m = new Media(id, "ANIME", title, "", List.of("Action"), List.of(), 12, null, 2020, 50, null, null,
                null, null, null, "TV", "FINISHED", null, List.of());
        return new RecommenderV2.Recommendation(m, score, 0, 0, Map.of(), List.of(), null, null, "EXPLOIT");
    }

    @Test
    void diversifyPushesSequelsDownButKeepsThem() {
        List<RecommenderV2.Recommendation> ranked = List.of(
                rec(1, "Attack on Titan Season 2", 0.9),
                rec(2, "Attack on Titan Season 3", 0.8),
                rec(3, "Vinland Saga", 0.7),
                rec(4, "Attack on Titan: The Final Season", 0.6),
                rec(5, "Monster", 0.5));
        List<RecommenderV2.Recommendation> out = RecommenderV2.diversify(ranked, 4);
        assertEquals(List.of(1L, 3L, 5L, 2L), out.stream().map(r -> r.media().id()).toList());
    }

    @Test
    void explorationNamesTheLeastKnownGenre() {
        Map<String, Double> x = Map.of("g:Horror", 0.5, "g:Romance", 0.5, "t:gore", 0.25, "type:ANIME", 0.5);
        Map<String, OnlineLearner.Weight> online = Map.of(
                "g:Horror", new OnlineLearner.Weight(0.4, 3),
                "g:Romance", new OnlineLearner.Weight(0, 0.2));
        assertEquals("Romance", RecommenderV2.leastKnown(online, x));

        Map<String, OnlineLearner.Weight> genresKnown = Map.of(
                "g:Horror", new OnlineLearner.Weight(0.4, 3),
                "g:Romance", new OnlineLearner.Weight(0.1, 2));
        assertEquals("gore", RecommenderV2.leastKnown(genresKnown, x));
        assertEquals("this kind of title", RecommenderV2.leastKnown(Map.of(), Map.of("type:ANIME", 1.0)));
    }

    @Test
    void ratingsBeatButtonsAndButtonsBoundTheSignal() {
        assertEquals(-0.8, UserSignals.withFeedback(-0.8, 3, "LIKE"), 1e-12);
        assertEquals(UserSignals.FEEDBACK_SIGNAL, UserSignals.withFeedback(0.1, null, "LIKE"), 1e-12);
        assertEquals(0.9, UserSignals.withFeedback(0.9, null, "LIKE"), 1e-12);
        assertEquals(-UserSignals.FEEDBACK_SIGNAL, UserSignals.withFeedback(0.5, null, "NOT_INTERESTED"), 1e-12);
        assertEquals(0.3, UserSignals.withFeedback(0.3, null, null), 1e-12);
    }

    @Test
    void oldSignalsFadeButNeverVanish() {
        assertEquals(1.0, UserSignals.recency(0), 1e-12);
        assertEquals(0.5, UserSignals.recency(UserSignals.HALF_LIFE_DAYS), 1e-12);
        assertEquals(0.15, UserSignals.recency(10_000), 1e-12);
        assertEquals(1.0, UserSignals.recency(-5), 1e-12);
        assertTrue(UserSignals.recency(30) > UserSignals.recency(60));
    }
}
