package com.aniga.backend.recommend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.aniga.backend.media.Media;

/**
 * V1 recommender: score = 0.55 * genreScore + 0.45 * textScore (+ tiny popularity tie-break).
 *
 * Ratings are centred on the user's own mean, so a 5 from a generous rater is a negative signal.
 * That is what lets two titles of the same genre (Naruto 5 vs Attack on Titan 10) pull the
 * synopsis/tag profile in opposite directions instead of both counting as "likes shonen".
 */
public final class HybridRecommender {

    static final double GENRE_WEIGHT = 0.55;
    static final double TEXT_WEIGHT = 0.45;
    static final double POPULARITY_WEIGHT = 0.02;
    /** Pseudo-count that shrinks genre weights seen in only one or two ratings toward 0. */
    static final double GENRE_SHRINKAGE = 1.0;
    static final int MIN_RATINGS_FOR_PERSONAL_MEAN = 3;

    public record Recommendation(Media media, double score, double genreScore, double textScore,
                                 List<String> reasons) {
    }

    public record TasteProfile(int ratingCount, double pivot, Map<String, Double> genreWeights,
                               List<Map.Entry<String, Double>> likedTerms,
                               List<Map.Entry<String, Double>> dislikedTerms) {
    }

    private final Map<Long, Media> catalog;
    private final TfIdfIndex tfidf;

    public HybridRecommender(List<Media> media) {
        this.catalog = media.stream().collect(Collectors.toMap(Media::id, Function.identity()));
        this.tfidf = new TfIdfIndex(media.stream().collect(Collectors.toMap(Media::id,
                m -> m.synopsis() + " " + String.join(" ", m.tags()))));
    }

    public TasteProfile taste(Map<Long, Integer> ratings) {
        double pivot = pivot(ratings);
        Map<Long, Double> signal = centred(ratings, pivot);
        Map<String, Double> textProfile = textProfile(signal);
        var terms = textProfile.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).toList();
        return new TasteProfile(ratings.size(), pivot, genreWeights(signal),
                terms.stream().filter(e -> e.getValue() > 0).limit(10).toList(),
                terms.reversed().stream().filter(e -> e.getValue() < 0).limit(10).toList());
    }

    public List<Recommendation> recommend(Map<Long, Integer> ratings, int limit, String typeFilter) {
        List<Media> candidates = catalog.values().stream()
                .filter(m -> !ratings.containsKey(m.id()))
                .filter(m -> typeFilter == null || typeFilter.isBlank() || m.mediaType().equalsIgnoreCase(typeFilter))
                .toList();

        if (ratings.isEmpty()) {
            return candidates.stream()
                    .sorted(Comparator.comparingInt(Media::popularity).reversed())
                    .limit(limit)
                    .map(m -> new Recommendation(m, m.popularity() / 100.0, 0, 0,
                            List.of("cold start: no ratings yet, showing popular titles")))
                    .toList();
        }

        Map<Long, Double> signal = centred(ratings, pivot(ratings));
        Map<String, Double> genreWeights = genreWeights(signal);
        Map<String, Double> textProfile = textProfile(signal);

        List<Recommendation> out = new ArrayList<>();
        for (Media m : candidates) {
            double genreScore = m.genres().stream().mapToDouble(g -> genreWeights.getOrDefault(g, 0.0))
                    .average().orElse(0);
            Map<String, Double> vec = tfidf.vector(m.id());
            double textScore = TfIdfIndex.dot(textProfile, vec);
            double score = GENRE_WEIGHT * genreScore + TEXT_WEIGHT * textScore
                    + POPULARITY_WEIGHT * m.popularity() / 100.0;
            out.add(new Recommendation(m, score, genreScore, textScore,
                    reasons(m, genreWeights, textProfile, vec)));
        }
        out.sort(Comparator.comparingDouble(Recommendation::score).reversed());
        return out.subList(0, Math.min(limit, out.size()));
    }

    private static double pivot(Map<Long, Integer> ratings) {
        if (ratings.size() < MIN_RATINGS_FOR_PERSONAL_MEAN) {
            return 5.5;
        }
        return ratings.values().stream().mapToInt(Integer::intValue).average().orElse(5.5);
    }

    /** Maps 1..10 scores to roughly [-1, 1] around the pivot. */
    private static Map<Long, Double> centred(Map<Long, Integer> ratings, double pivot) {
        Map<Long, Double> out = new HashMap<>();
        ratings.forEach((id, score) -> out.put(id, (score - pivot) / 4.5));
        return out;
    }

    private Map<String, Double> genreWeights(Map<Long, Double> signal) {
        Map<String, Double> sum = new HashMap<>();
        Map<String, Integer> count = new HashMap<>();
        signal.forEach((id, s) -> {
            Media m = catalog.get(id);
            if (m == null) {
                return;
            }
            for (String g : m.genres()) {
                sum.merge(g, s, Double::sum);
                count.merge(g, 1, Integer::sum);
            }
        });
        Map<String, Double> out = new HashMap<>();
        sum.forEach((g, s) -> out.put(g, s / (count.get(g) + GENRE_SHRINKAGE)));
        return out;
    }

    private Map<String, Double> textProfile(Map<Long, Double> signal) {
        Map<String, Double> profile = new HashMap<>();
        signal.forEach((id, s) -> tfidf.vector(id).forEach((term, w) -> profile.merge(term, s * w, Double::sum)));
        return TfIdfIndex.normalize(profile);
    }

    private static List<String> reasons(Media m, Map<String, Double> genreWeights,
                                        Map<String, Double> textProfile, Map<String, Double> vec) {
        List<String> reasons = new ArrayList<>();
        m.genres().stream()
                .filter(g -> genreWeights.getOrDefault(g, 0.0) > 0.05)
                .sorted(Comparator.comparingDouble((String g) -> genreWeights.get(g)).reversed())
                .limit(2)
                .forEach(g -> reasons.add("you rate " + g + " above your average"));
        vec.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue() * textProfile.getOrDefault(e.getKey(), 0.0)))
                .filter(e -> e.getValue() > 0.005)
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .limit(3)
                .forEach(e -> reasons.add("matches themes you liked: " + e.getKey()));
        m.genres().stream()
                .filter(g -> genreWeights.getOrDefault(g, 0.0) < -0.05)
                .forEach(g -> reasons.add("penalised: you rate " + g + " below your average"));
        return reasons;
    }
}
