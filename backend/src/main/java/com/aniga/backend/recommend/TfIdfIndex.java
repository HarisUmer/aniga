package com.aniga.backend.recommend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * TF-IDF vectors over each title's synopsis + tags, L2-normalised so a dot product is cosine similarity.
 * idf = ln((1 + N) / (1 + df)) + 1  (smoothed, same formula as scikit-learn's default).
 */
public final class TfIdfIndex {

    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "his", "her", "their", "into", "from", "that", "this", "who",
            "while", "through", "after", "across", "full", "all", "are", "was", "but", "not", "out",
            "one", "two", "its", "own", "him", "she", "they", "them", "only", "back", "again", "every",
            "each", "other", "what", "makes", "make", "gets", "becomes", "become", "find", "finds");

    private final Map<Long, Map<String, Double>> vectors = new HashMap<>();
    private final Map<String, Double> idf = new HashMap<>();

    public TfIdfIndex(Map<Long, String> documents) {
        Map<Long, List<String>> tokenized = new HashMap<>();
        Map<String, Integer> docFreq = new HashMap<>();
        documents.forEach((id, text) -> {
            List<String> tokens = tokenize(text);
            tokenized.put(id, tokens);
            tokens.stream().distinct().forEach(t -> docFreq.merge(t, 1, Integer::sum));
        });

        int n = documents.size();
        docFreq.forEach((term, df) -> idf.put(term, Math.log((1.0 + n) / (1.0 + df)) + 1.0));

        tokenized.forEach((id, tokens) -> {
            Map<String, Double> tf = new HashMap<>();
            tokens.forEach(t -> tf.merge(t, 1.0, Double::sum));
            Map<String, Double> vec = new HashMap<>();
            tf.forEach((term, count) -> vec.put(term, (count / tokens.size()) * idf.get(term)));
            vectors.put(id, normalize(vec));
        });
    }

    public Map<String, Double> vector(long id) {
        return vectors.getOrDefault(id, Map.of());
    }

    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9-]+")) {
            String t = raw.replaceAll("^-+|-+$", "");
            if (t.length() >= 3 && !STOPWORDS.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    public static Map<String, Double> normalize(Map<String, Double> vec) {
        double norm = Math.sqrt(vec.values().stream().mapToDouble(v -> v * v).sum());
        if (norm == 0) {
            return vec;
        }
        Map<String, Double> out = new HashMap<>();
        vec.forEach((k, v) -> out.put(k, v / norm));
        return out;
    }

    public static double dot(Map<String, Double> a, Map<String, Double> b) {
        Map<String, Double> small = a.size() <= b.size() ? a : b;
        Map<String, Double> large = small == a ? b : a;
        double sum = 0;
        for (var e : small.entrySet()) {
            Double other = large.get(e.getKey());
            if (other != null) {
                sum += e.getValue() * other;
            }
        }
        return sum;
    }
}
