package com.aniga.backend.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aniga.backend.media.Media;

/**
 * BM25 over "what the story is about": title (x3), tags (x2), genres (x2), synopsis, character names.
 * Queries are plain language ("boy reborn as a villain in a magic academy"); a small synonym table maps
 * fan vocabulary onto the words AniList descriptions actually use.
 */
public final class DescriptionIndex {

    public record Hit(Media media, double score, List<String> matched) {
    }

    static final double K1 = 1.2;
    static final double B = 0.75;

    static final Set<String> STOP = Set.of("a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "with", "is",
            "are", "was", "were", "be", "who", "that", "this", "his", "her", "their", "its", "it", "he", "she", "they",
            "as", "at", "by", "from", "into", "about", "after", "but", "not", "has", "have", "had", "one", "where",
            "when", "what", "which", "story", "anime", "manga", "manhwa", "manhua", "series", "show", "like", "some",
            "find", "looking", "want", "something", "i", "me", "my", "there", "so", "very", "get", "gets");

    /** fan term -> words that show up in descriptions/tags. */
    static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("isekai", List.of("another", "world", "transported", "summoned", "isekai")),
            Map.entry("reborn", List.of("reincarnation", "reborn", "past", "life")),
            Map.entry("reincarnated", List.of("reincarnation", "reborn")),
            Map.entry("regression", List.of("return", "past", "regressor", "second", "chance", "time")),
            Map.entry("op", List.of("overpowered", "strongest", "power")),
            Map.entry("overpowered", List.of("overpowered", "strongest")),
            Map.entry("cultivation", List.of("cultivation", "sect", "martial", "qi", "immortal")),
            Map.entry("wuxia", List.of("martial", "sect", "sword", "wuxia")),
            Map.entry("leveling", List.of("level", "system", "dungeon", "hunter")),
            Map.entry("system", List.of("system", "level", "status", "quest")),
            Map.entry("mecha", List.of("mecha", "robot", "pilot")),
            Map.entry("romcom", List.of("romantic", "comedy", "romance")),
            Map.entry("villainess", List.of("villainess", "duke", "noble", "lady")),
            Map.entry("idol", List.of("idol", "stage", "concert", "singer")),
            Map.entry("zombie", List.of("zombie", "undead", "apocalypse")),
            Map.entry("vampire", List.of("vampire", "blood")),
            Map.entry("ninja", List.of("ninja", "shinobi", "village")),
            Map.entry("samurai", List.of("samurai", "sword", "ronin")),
            Map.entry("detective", List.of("detective", "case", "murder", "mystery")),
            Map.entry("revenge", List.of("revenge", "avenge", "vengeance", "betrayal")),
            Map.entry("time", List.of("time", "loop", "travel")),
            Map.entry("ghost", List.of("ghost", "spirit", "curse", "yokai")),
            Map.entry("demon", List.of("demon", "devil", "oni")),
            Map.entry("school", List.of("school", "student", "academy", "class")),
            Map.entry("sports", List.of("team", "tournament", "match", "championship")));

    private final List<Media> docs = new ArrayList<>();
    private final List<Map<String, Integer>> tf = new ArrayList<>();
    private final List<Integer> length = new ArrayList<>();
    private final Map<String, Integer> df = new HashMap<>();
    private double avgLength;

    public DescriptionIndex(List<Media> media, Map<Long, String> characterNames) {
        long total = 0;
        for (Media m : media) {
            Map<String, Integer> counts = new HashMap<>();
            add(counts, m.title(), 3);
            add(counts, m.nativeTitle(), 1);
            m.tags().forEach(t -> add(counts, t.replace('-', ' '), 2));
            m.genres().forEach(g -> add(counts, g, 2));
            add(counts, m.synopsis(), 1);
            add(counts, characterNames.getOrDefault(m.id(), ""), 1);
            int len = counts.values().stream().mapToInt(Integer::intValue).sum();
            docs.add(m);
            tf.add(counts);
            length.add(len);
            total += len;
            counts.keySet().forEach(t -> df.merge(t, 1, Integer::sum));
        }
        avgLength = docs.isEmpty() ? 1 : (double) total / docs.size();
    }

    private static void add(Map<String, Integer> counts, String text, int weight) {
        for (String t : tokens(text)) {
            counts.merge(t, weight, Integer::sum);
        }
    }

    static List<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String norm = Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        List<String> out = new ArrayList<>();
        for (String raw : norm.split("[^\\p{L}\\p{N}]+")) {
            if (raw.length() < 2 || STOP.contains(raw)) {
                continue;
            }
            out.add(stem(raw));
        }
        return out;
    }

    /** Crude suffix stripping: enough for "reincarnated/reincarnation" or "dungeons/dungeon" to meet. */
    static String stem(String w) {
        if (w.length() <= 4) {
            return w;
        }
        for (String suffix : List.of("ations", "ation", "ated", "ates", "ate", "ions", "ion", "ing", "ers", "er", "ed",
                "es", "s", "ly")) {
            if (w.endsWith(suffix) && w.length() - suffix.length() >= 4) {
                return w.substring(0, w.length() - suffix.length());
            }
        }
        return w;
    }

    public List<Hit> search(String query, String type, int limit) {
        Map<String, Double> terms = new HashMap<>();
        for (String raw : query.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (raw.isBlank() || STOP.contains(raw)) {
                continue;
            }
            terms.merge(stem(raw), 1.0, Double::sum);
            for (String syn : SYNONYMS.getOrDefault(raw, List.of())) {
                terms.merge(stem(syn), 0.5, Math::max);
            }
        }
        if (terms.isEmpty()) {
            return List.of();
        }
        int n = docs.size();
        List<Hit> hits = new ArrayList<>();
        for (int d = 0; d < n; d++) {
            Media m = docs.get(d);
            if (type != null && !type.isBlank() && !m.mediaType().equalsIgnoreCase(type)) {
                continue;
            }
            Map<String, Integer> counts = tf.get(d);
            double score = 0;
            Set<String> matched = new LinkedHashSet<>();
            for (var e : terms.entrySet()) {
                int f = counts.getOrDefault(e.getKey(), 0);
                if (f == 0) {
                    continue;
                }
                double idf = Math.log(1 + (n - df.get(e.getKey()) + 0.5) / (df.get(e.getKey()) + 0.5));
                double norm = f * (K1 + 1) / (f + K1 * (1 - B + B * length.get(d) / avgLength));
                score += e.getValue() * idf * norm;
                matched.add(e.getKey());
            }
            if (score > 0) {
                // Small popularity prior so equally-matching obscure titles don't outrank the famous one.
                hits.add(new Hit(m, Math.round((score + 0.002 * m.popularity()) * 1000) / 1000.0, List.copyOf(matched)));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.subList(0, Math.min(limit, hits.size()));
    }
}
