package com.aniga.backend.read;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * Attaches readable chapters to a catalog title. Searches every adapter, drops novel-only
 * lookalikes, then ranks remaining hits by title score and by how many chapters that source
 * (and language) actually has — so Chainsaw Man opens on 230 pt-br chapters, not 100 English ones.
 */
@Component
public class ChapterFinder {

    private static final double MIN_SCORE = 0.42;
    private static final int MAX_HITS = 12;
    private static final int EXPAND_TOP = 4;
    private static final long SEARCH_DEADLINE_S = 14;
    private static final long COUNT_DEADLINE_S = 10;

    /** English HTML readers we host in-app. They are searched in English and ranked ahead of MangaDex. */
    static final Set<String> PRIMARY = Set.of("asura", "mangageko", "mangatown");
    /** When two hits score within this, a primary source wins over MangaDex. */
    private static final double SCORE_TIE = 0.12;

    private final List<SourceAdapter> adapters;
    private final MangaDexAdapter mangaDex;

    public ChapterFinder(List<SourceAdapter> adapters, MangaDexAdapter mangaDex) {
        this.adapters = adapters;
        this.mangaDex = mangaDex;
    }

    public List<SourceAdapter.Series> find(String title, String lang, String aliasesRaw) {
        return find(title, lang, aliasesRaw, null);
    }

    public List<SourceAdapter.Series> find(String title, String lang, String aliasesRaw, String type) {
        String wantLang = lang == null || lang.isBlank() ? "en" : lang;
        List<String> aliases = splitAliases(aliasesRaw);
        String original = originalLanguage(type);
        List<SourceAdapter.Series> raw = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<List<SourceAdapter.Series>>> calls = new ArrayList<>();
            for (SourceAdapter a : adapters) {
                calls.add(() -> searchSafe(a, title, wantLang));
                for (String alias : aliases) {
                    if (!TitleMatch.normalize(alias).equals(TitleMatch.normalize(title))) {
                        calls.add(() -> searchSafe(a, alias, wantLang));
                    }
                }
            }
            if (original != null) {
                calls.add(() -> searchOriginal(title, original));
                for (String alias : aliases) {
                    if (!TitleMatch.normalize(alias).equals(TitleMatch.normalize(title))) {
                        calls.add(() -> searchOriginal(alias, original));
                    }
                }
            }
            // A slow source is cancelled at the deadline instead of holding up the ones that answered.
            for (var f : pool.invokeAll(calls, SEARCH_DEADLINE_S, TimeUnit.SECONDS)) {
                try {
                    raw.addAll(f.get());
                } catch (Exception ignored) {
                    // One source being down must not hide the others.
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Map<String, Scored> best = new LinkedHashMap<>();
        for (SourceAdapter.Series s : raw) {
            double score = TitleMatch.bestScore(title, aliases, s.title());
            if (score < MIN_SCORE) {
                continue;
            }
            String key = s.source() + ":" + s.id();
            best.merge(key, new Scored(s, score), (a, b) -> a.score >= b.score ? a : b);
        }

        List<Scored> ranked = new ArrayList<>(best.values());
        ranked.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());
        if (ranked.size() > MAX_HITS) {
            ranked = ranked.subList(0, MAX_HITS);
        }
        // Counting is one upstream call per (hit, language), all paced by the MangaDex limiter, so only the best
        // few MangaDex matches get the cross-language comparison; the rest are counted in the asked language.
        List<Callable<List<SourceAdapter.Series>>> counts = new ArrayList<>();
        int expanded = 0;
        for (Scored hit : ranked) {
            if (PRIMARY.contains(hit.series.source())) {
                // The detail screen loads the chapter list; counting here only risks a scrape timeout
                // that would drop the hit, which is exactly why these were missing from Home.
                counts.add(() -> List.of(hit.series.withMeta(null, "en")));
            } else if ("mangadex".equals(hit.series.source())) {
                boolean all = expanded++ < EXPAND_TOP;
                counts.add(() -> expandMangaDex(hit, wantLang, all));
            } else {
                counts.add(() -> List.of(countOther(hit, wantLang)));
            }
        }
        List<SourceAdapter.Series> out = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var f : pool.invokeAll(counts, COUNT_DEADLINE_S, TimeUnit.SECONDS)) {
                try {
                    out.addAll(f.get());
                } catch (Exception ignored) {
                    // a hit that could not be counted in time is dropped rather than shown with a wrong count
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Bucket by title score so near-ties sort together, then put our in-app HTML readers first within a
        // bucket (MangaDex English is often official links only), then the richest chapter list.
        out.sort(Comparator
                .comparingInt((SourceAdapter.Series s) -> scoreBucket(TitleMatch.bestScore(title, aliases, s.title())))
                .reversed()
                .thenComparing((SourceAdapter.Series s) -> PRIMARY.contains(s.source()) ? 0 : 1)
                .thenComparing((SourceAdapter.Series s) -> s.chapterCount() == null ? 0 : s.chapterCount(),
                        Comparator.reverseOrder()));
        if (out.size() > MAX_HITS) {
            return List.copyOf(out.subList(0, MAX_HITS));
        }
        return List.copyOf(out);
    }

    private List<SourceAdapter.Series> expandMangaDex(Scored hit, String wantLang, boolean allLangs) {
        List<String> langs = new ArrayList<>();
        langs.add(wantLang);
        if (allLangs) {
            langs.addAll(MangaDexAdapter.COUNT_LANGS);
        }
        Map<String, Integer> counts = mangaDex.chapterCounts(hit.series.id(), langs);
        if (counts.isEmpty()) {
            return List.of();
        }
        int richest = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<SourceAdapter.Series> rows = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> {
                    boolean keep = e.getKey().equals(MangaDexAdapter.toMangaDexLang(wantLang))
                            || e.getValue() >= Math.max(8, richest / 4);
                    if (keep) {
                        rows.add(hit.series.withMeta(e.getValue(), e.getKey()));
                    }
                });
        return rows;
    }

    /** Groups near-equal title scores so a slightly lower-scoring primary source can still rank first. */
    private static int scoreBucket(double score) {
        return (int) Math.round(score / SCORE_TIE);
    }

    private SourceAdapter.Series countOther(Scored hit, String lang) {
        try {
            int n = adapter(hit.series.source()).countChapters(hit.series.id(), lang);
            return n > 0 ? hit.series.withMeta(n, lang) : hit.series.withMeta(0, lang);
        } catch (RuntimeException e) {
            return hit.series.withMeta(0, lang);
        }
    }

    private SourceAdapter adapter(String code) {
        return adapters.stream().filter(a -> a.info().code().equals(code)).findFirst().orElse(mangaDex);
    }

    private static List<SourceAdapter.Series> searchSafe(SourceAdapter a, String q, String lang) {
        // Our HTML readers only serve English; searching them in the reader's language returns nothing.
        String searchLang = PRIMARY.contains(a.info().code()) ? "en" : lang;
        try {
            return a.search(q, searchLang, 0).stream().limit(6).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** ko / zh / ja so a manhwa is looked up as a Korean original, not only as an English translation. */
    static String originalLanguage(String type) {
        if (type == null) {
            return null;
        }
        return switch (type.toUpperCase(java.util.Locale.ROOT)) {
            case "MANHWA" -> "ko";
            case "MANHUA" -> "zh";
            case "MANGA" -> "ja";
            default -> null;
        };
    }

    private List<SourceAdapter.Series> searchOriginal(String query, String original) {
        try {
            return mangaDex.searchOriginal(query, original).stream().limit(6).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    static List<String> splitAliases(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split("[|]")) {
            String t = part.trim();
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private record Scored(SourceAdapter.Series series, double score) {
    }
}
