package com.aniga.backend.read;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Ranks a catalog title against a source's name so we open the comic, not the novel or a side story.
 * "The Beginning After the End" must beat "… (Book Version)" and "After the Beginning".
 */
final class TitleMatch {

    private static final Pattern PUNCT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern NOVEL = Pattern.compile(
            "\\b(book version|novel version|light novel|web novel|wn)\\b");
    private static final Pattern SIDE = Pattern.compile(
            "\\b(side story|doujinshi|fan colored|fan-colored|pre-serialization)\\b");

    private TitleMatch() {
    }

    static String normalize(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String n = PUNCT.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
        if (n.startsWith("the ")) {
            n = n.substring(4);
        }
        return n.replaceAll("\\s+", " ");
    }

    static boolean novelOnly(String title) {
        return title != null && NOVEL.matcher(title.toLowerCase(Locale.ROOT)).find();
    }

    /**
     * 0–1. Exact (after normalize) is 1. Book/novel versions score 0 unless the query is also a novel.
     * Side stories and doujinshi are penalised so the main series wins.
     */
    static double score(String query, String candidate) {
        String q = normalize(query);
        String c = normalize(candidate);
        if (q.isEmpty() || c.isEmpty()) {
            return 0;
        }
        boolean queryIsNovel = novelOnly(query);
        if (novelOnly(candidate) && !queryIsNovel) {
            return 0;
        }
        if (q.equals(c)) {
            return 1;
        }
        String cCore = normalize(candidate.replaceAll("(?i)\\s*\\([^)]*\\)", ""));
        if (!cCore.isEmpty() && q.equals(cCore)) {
            return 0.94;
        }
        if (c.startsWith(q + " ") || q.startsWith(c + " ")) {
            return SIDE.matcher(candidate.toLowerCase(Locale.ROOT)).find() ? 0.55 : 0.88;
        }
        if (c.contains(q) && c.length() > q.length() + 10) {
            // "Red String: The Beginning After the End" is not the manhwa.
            return 0.48;
        }
        List<String> qw = List.of(q.split(" "));
        int hits = 0;
        for (String w : qw) {
            if (w.length() >= 2 && c.contains(w)) {
                hits++;
            }
        }
        if (hits == 0) {
            return 0;
        }
        double overlap = (double) hits / qw.size();
        double lengthPenalty = Math.min(1, (double) q.length() / Math.max(c.length(), 1));
        double raw = 0.35 + 0.5 * overlap + 0.1 * lengthPenalty;
        if (SIDE.matcher(candidate.toLowerCase(Locale.ROOT)).find()) {
            raw -= 0.25;
        }
        return Math.max(0, Math.min(0.86, raw));
    }

    static double bestScore(String query, List<String> aliases, String candidate) {
        double best = score(query, candidate);
        if (aliases == null) {
            return best;
        }
        for (String a : aliases) {
            best = Math.max(best, score(a, candidate));
        }
        return best;
    }
}
