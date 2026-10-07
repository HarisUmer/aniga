package com.aniga.backend.recommend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aniga.backend.media.Media;

class HybridRecommenderTest {

    private static Media media(long id, String title, String synopsis, List<String> genres, List<String> tags) {
        return new Media(id, "ANIME", title, synopsis, genres, tags, 12, null, 2020, 50, null, null, null, null,
                null, "TV", "FINISHED", null, List.of());
    }

    private final List<Media> catalog = List.of(
            media(1, "Bright Ninja", "A cheerful ninja trains with friends for a tournament.",
                    List.of("Action"), List.of("shonen", "friendship", "tournament")),
            media(2, "Wall War", "Soldiers fight a dark war for survival and revenge.",
                    List.of("Action"), List.of("shonen", "dark", "survival", "revenge")),
            media(3, "Hero School", "Students train at a hero school and join a friendly tournament.",
                    List.of("Action"), List.of("shonen", "school", "friendship", "tournament")),
            media(4, "Grim Blade", "A lone swordsman seeks revenge in a dark world of survival.",
                    List.of("Action"), List.of("seinen", "dark", "revenge", "survival")),
            media(5, "Tea Club", "Gentle slice of life about a tea club.",
                    List.of("Slice of Life"), List.of("school", "wholesome")));

    @Test
    void sameGenreDifferentScoresAreSeparatedBySynopsisThemes() {
        var recommender = new HybridRecommender(catalog);
        Map<Long, Integer> ratings = Map.of(1L, 5, 2L, 10);

        var recs = recommender.recommend(ratings, 10, null);

        int darkRevenge = indexOf(recs, 4);
        int friendlyTournament = indexOf(recs, 3);
        assertTrue(darkRevenge < friendlyTournament,
                "dark/revenge title should outrank the friendship/tournament title");
        assertTrue(recs.get(darkRevenge).textScore() > recs.get(friendlyTournament).textScore());
        assertEquals(recs.get(darkRevenge).genreScore(), recs.get(friendlyTournament).genreScore(), 1e-9,
                "both are Action, so genre alone cannot separate them");
    }

    @Test
    void ratedTitlesAreExcludedAndColdStartFallsBackToPopularity() {
        var recommender = new HybridRecommender(catalog);
        var recs = recommender.recommend(Map.of(1L, 9), 10, null);
        assertTrue(recs.stream().noneMatch(r -> r.media().id() == 1));

        var cold = recommender.recommend(Map.of(), 3, null);
        assertEquals(3, cold.size());
        assertTrue(cold.getFirst().reasons().getFirst().startsWith("cold start"));
    }

    @Test
    void tasteProfileListsLikedAndDislikedTerms() {
        var taste = new HybridRecommender(catalog).taste(Map.of(1L, 5, 2L, 10));
        var liked = taste.likedTerms().stream().map(Map.Entry::getKey).toList();
        var disliked = taste.dislikedTerms().stream().map(Map.Entry::getKey).toList();
        assertTrue(liked.contains("revenge"));
        assertTrue(disliked.contains("tournament"));
    }

    private static int indexOf(List<HybridRecommender.Recommendation> recs, long id) {
        for (int i = 0; i < recs.size(); i++) {
            if (recs.get(i).media().id() == id) {
                return i;
            }
        }
        throw new AssertionError("missing " + id);
    }
}
