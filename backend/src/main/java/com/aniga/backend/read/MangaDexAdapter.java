package com.aniga.backend.read;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import com.aniga.backend.mangadex.Json;

/**
 * MangaDex official API. One adapter serves all 61 "MangaDex (lang)" extensions in the catalog because
 * language is just a filter (availableTranslatedLanguage / translatedLanguage) on the same API.
 * Rate limits: ~5 req/s global, 40/min on /at-home, so every call is cached.
 */
@Component
public class MangaDexAdapter implements SourceAdapter {

    static final List<String> LANGUAGES = List.of("en", "af", "ar", "az", "be", "bg", "bn", "ca", "cs", "cv", "da",
            "de", "el", "eo", "es", "es-la", "et", "eu", "fa", "fi", "fil", "fr", "ga", "he", "hi", "hr", "hu", "id",
            "it", "ja", "jv", "ka", "kk", "ko", "la", "lt", "mn", "ms", "my", "ne", "nl", "no", "pl", "pt", "pt-br",
            "ro", "ru", "sk", "sq", "sr", "sv", "ta", "te", "th", "tr", "uk", "ur", "uz", "vi", "zh", "zh-hk");

    private static final String ORIGIN_UPLOADS = "https://uploads.mangadex.org";
    private static final int PAGE_SIZE = 24;
    /** Typical collection max is 100; some feeds accept 500, but paging by 100 never skips a window. */
    private static final int FEED_BATCH = 100;
    private static final int MAX_CHAPTERS = 4000;
    static final List<String> COUNT_LANGS = List.of("en", "ko", "zh", "pt-br", "es-la", "es", "fr", "id", "ru", "de", "it");
    /** Mature manhwa is often filed as erotica. Pornographic stays out. */
    private static final String[] RATINGS = {"safe", "suggestive", "erotica"};
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private final RestClient http;
    private final TtlCache<Object> cache = new TtlCache<>(Duration.ofMinutes(10), 2000);
    private final RateLimiter limiter = new RateLimiter(4.5, 5);
    // MangaDex@Home node URLs carry a token that expires after ~15 min, so page lists live shorter (5 + 5 stale).
    private final TtlCache<Pages> pagesCache = new TtlCache<>(Duration.ofMinutes(5), 600);
    /** manga id → availableTranslatedLanguages, filled from every search/browse result. */
    private final Map<String, List<String>> available = new java.util.concurrent.ConcurrentHashMap<>();

    public MangaDexAdapter(@Value("${aniga.mangadex.base-url}") String baseUrl,
                           @Value("${aniga.mangadex.user-agent}") String userAgent) {
        this.http = RestClient.builder().baseUrl(baseUrl).defaultHeader("User-Agent", userAgent).build();
    }

    /** Every MangaDex call: shared token bucket, and up to two retries with backoff on HTTP 429. */
    private Map<String, Object> get(Function<UriBuilder, URI> uri) {
        for (int attempt = 0; ; attempt++) {
            limiter.acquire();
            try {
                return http.get().uri(uri).retrieve().body(JSON);
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt >= 2) {
                    throw e;
                }
                try {
                    Thread.sleep(800L << attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Runs independent upstream calls concurrently on virtual threads (the limiter still paces them). */
    private static <T> List<T> parallel(List<Callable<T>> calls) {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = calls.stream().map(pool::submit).toList();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                try {
                    out.add(f.get());
                } catch (ExecutionException e) {
                    throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return out;
        }
    }

    /** Warms the page cache for chapters the reader is likely to open next (≤2, so /at-home's 40/min holds). */
    @Override
    public void prefetch(List<String> chapterIds) {
        for (String id : chapterIds.stream().limit(2).toList()) {
            if (!pagesCache.isFresh("pages:" + id)) {
                Thread.ofVirtual().name("mdx-prefetch").start(() -> {
                    try {
                        pages(id, null);
                    } catch (RuntimeException ignored) {
                        // prefetch is best effort
                    }
                });
            }
        }
    }

    /** The catalog uses Keiyoushi codes (pt-BR, es-419, zh-Hans); MangaDex uses its own (pt-br, es-la, zh). */
    static String toMangaDexLang(String lang) {
        if (lang == null || lang.isBlank()) {
            return "en";
        }
        return switch (lang) {
            case "pt-BR" -> "pt-br";
            case "es-419" -> "es-la";
            case "zh-Hans" -> "zh";
            case "zh-Hant" -> "zh-hk";
            // MangaDex only accepts xx or xx-yy; Filipino is filed under Tagalog.
            case "fil" -> "tl";
            default -> lang.toLowerCase();
        };
    }

    @Override
    public Info info() {
        return new Info("mangadex", "MangaDex", "OFFICIAL_API", "https://mangadex.org",
                "Scanlations hosted with permission of MangaDex; credit MangaDex and the scanlation group, no ads",
                LANGUAGES, "en", false);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Series> popular(String lang, int page) {
        String l = toMangaDexLang(lang);
        return (List<Series>) cache.get("popular:" + l + ":" + page, () -> readablePopular(l, page));
    }

    /**
     * availableTranslatedLanguage still matches series whose chapters were taken down (licensed titles such as
     * Solo Leveling in Spanish), so "popular by followers" would list unreadable series. Instead: take the latest
     * hosted chapters in this language (3 x 100), collect their series, and sort those by followers in one call.
     * Every series returned has at least one chapter you can open here.
     */
    private List<Series> readablePopular(String lang, int page) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        // The three chapter pages are independent: fetch them together (≈3x faster than one after another).
        List<Callable<Map<String, Object>>> calls = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            final int off = page * 300 + k * 100;
            calls.add(() -> get(b -> b.path("/chapter")
                    .queryParam("translatedLanguage[]", lang)
                    .queryParam("order[readableAt]", "desc")
                    .queryParam("limit", 100)
                    .queryParam("offset", off)
                    .queryParam("includeExternalUrl", 0)
                    .queryParam("contentRating[]", RATINGS)
                    .build()));
        }
        for (Map<String, Object> body : parallel(calls)) {
            for (Object item : Json.list(body.get("data"))) {
                Json.list(Json.map(item).get("relationships")).stream()
                        .map(Json::map)
                        .filter(r -> "manga".equals(r.get("type")))
                        .map(r -> Json.str(r.get("id")))
                        .filter(id -> id != null && ids.size() < 100)
                        .forEach(ids::add);
            }
        }
        if (ids.isEmpty()) {
            return page == 0 ? browse(null, lang, 0, true) : List.of();
        }
        List<String> idList = List.copyOf(ids);
        Map<String, Object> body = get(b -> b.path("/manga")
                .queryParam("ids[]", idList.toArray())
                .queryParam("limit", 100)
                .queryParam("includes[]", "cover_art", "author")
                .queryParam("contentRating[]", RATINGS)
                .queryParam("order[followedCount]", "desc")
                .build());
        List<Series> out = new ArrayList<>();
        for (Object item : Json.list(body.get("data"))) {
            out.add(toSeries(Json.map(item)));
        }
        return out.size() > 48 ? out.subList(0, 48) : out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Series> search(String query, String lang, int page) {
        String l = toMangaDexLang(lang);
        return (List<Series>) cache.get("search:" + l + ":" + page + ":" + query.toLowerCase(), () -> {
            List<Series> hits = browse(query, l, page, true);
            // Language filter hides the right comic when MangaDex filed it under another code,
            // and it also used to surface "Book Version" rows with zero chapters.
            if (query != null && page == 0 && hits.stream().noneMatch(s -> TitleMatch.score(query, s.title()) >= 0.7)) {
                hits = browse(query, l, page, false);
            }
            return hits;
        });
    }

    /**
     * Title search locked to the original language (ko for manhwa, zh for manhua, ja for manga),
     * without requiring a translation. Catalog titles often exist only under that language first.
     */
    @SuppressWarnings("unchecked")
    public List<Series> searchOriginal(String query, String originalLang) {
        if (query == null || query.isBlank() || originalLang == null || originalLang.isBlank()) {
            return List.of();
        }
        String key = "orig:" + originalLang + ":" + query.toLowerCase(java.util.Locale.ROOT);
        return (List<Series>) cache.get(key, () -> {
            Map<String, Object> body = get(b -> b.path("/manga")
                    .queryParam("limit", PAGE_SIZE)
                    .queryParam("title", "{q}")
                    .queryParam("originalLanguage[]", originalLang)
                    .queryParam("hasAvailableChapters", "true")
                    .queryParam("includes[]", "cover_art", "author")
                    .queryParam("contentRating[]", RATINGS)
                    .queryParam("order[relevance]", "desc")
                    .build(query));
            List<Series> out = new ArrayList<>();
            for (Object item : Json.list(body.get("data"))) {
                out.add(toSeries(Json.map(item)));
            }
            return out;
        });
    }

    private List<Series> browse(String query, String lang, int page, boolean requireLang) {
        Map<String, Object> body = get(b -> {
                    b.path("/manga")
                            .queryParam("limit", PAGE_SIZE)
                            .queryParam("offset", page * PAGE_SIZE)
                            .queryParam("includes[]", "cover_art", "author")
                            .queryParam("contentRating[]", RATINGS)
                            .queryParam("hasAvailableChapters", "true");
                    if (requireLang) {
                        b.queryParam("availableTranslatedLanguage[]", lang);
                    }
                    if (query == null) {
                        b.queryParam("order[followedCount]", "desc");
                    } else {
                        b.queryParam("title", "{q}").queryParam("order[relevance]", "desc");
                    }
                    return query == null ? b.build() : b.build(query);
                });
        List<Series> out = new ArrayList<>();
        for (Object item : Json.list(body.get("data"))) {
            out.add(toSeries(Json.map(item)));
        }
        return out;
    }

    @Override
    public Series series(String id) {
        return (Series) cache.get("series:" + id, () -> {
            Map<String, Object> body = get(b -> b.path("/manga/{id}").queryParam("includes[]", "cover_art", "author")
                    .build(id));
            return toSeries(Json.map(body.get("data")));
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ChapterRef> chapters(String seriesId, String lang) {
        String l = toMangaDexLang(lang);
        return (List<ChapterRef>) cache.get("chapters:" + seriesId + ":" + l, () -> loadChapters(seriesId, l));
    }

    @Override
    public int countChapters(String seriesId, String lang) {
        return aggregateCount(seriesId, toMangaDexLang(lang));
    }

    /**
     * Compact chapter totals per language (MangaDex /aggregate), used to pick the translation
     * that actually has the story instead of stopping at the first 100 English rows.
     */
    @SuppressWarnings("unchecked")
    Map<String, Integer> chapterCounts(String seriesId, List<String> langs) {
        // Every /aggregate costs a rate-limit token; skip languages the search result says have no chapters.
        List<String> has = available.get(seriesId);
        List<String> want = new ArrayList<>();
        for (String lang : langs) {
            String l = toMangaDexLang(lang);
            if (l != null && !want.contains(l) && (has == null || has.contains(l))) {
                want.add(l);
            }
        }
        List<Callable<Integer>> calls = want.stream()
                .map(l -> (Callable<Integer>) () -> aggregateCount(seriesId, l))
                .toList();
        List<Integer> counts = parallel(calls);
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < want.size(); i++) {
            if (counts.get(i) > 0) {
                out.put(want.get(i), counts.get(i));
            }
        }
        return out;
    }

    private int aggregateCount(String seriesId, String lang) {
        try {
            Map<String, Object> body = (Map<String, Object>) cache.get("agg:" + seriesId + ":" + lang, () -> get(b -> b
                    .path("/manga/{id}/aggregate")
                    .queryParam("translatedLanguage[]", lang)
                    .build(seriesId)));
            int n = 0;
            for (Object vol : Json.map(body.get("volumes")).values()) {
                n += Json.map(Json.map(vol).get("chapters")).size();
            }
            return n;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Several groups often upload the same chapter number; keep a hosted scan over an official link. */
    private List<ChapterRef> loadChapters(String seriesId, String lang) {
        Map<String, ChapterRef> byNumber = new LinkedHashMap<>();
        Function<Integer, Map<String, Object>> feed = off -> get(b -> b.path("/manga/{id}/feed")
                .queryParam("translatedLanguage[]", lang)
                .queryParam("order[chapter]", "asc")
                .queryParam("limit", FEED_BATCH)
                .queryParam("offset", off)
                .queryParam("includes[]", "scanlation_group")
                .queryParam("contentRating[]", RATINGS)
                .build(seriesId));
        // Walk by the number actually returned (API may cap below FEED_BATCH) so we never skip 101–499.
        List<Map<String, Object>> bodies = new ArrayList<>();
        bodies.add(feed.apply(0));
        Integer total = Json.integer(bodies.get(0).get("total"));
        int firstSize = Json.list(bodies.get(0).get("data")).size();
        int step = firstSize > 0 ? firstSize : FEED_BATCH;
        List<Callable<Map<String, Object>>> rest = new ArrayList<>();
        int cap = total == null ? MAX_CHAPTERS : Math.min(total, MAX_CHAPTERS);
        for (int off = step; off < cap; off += step) {
            final int o = off;
            rest.add(() -> feed.apply(o));
        }
        bodies.addAll(parallel(rest));
        for (Map<String, Object> body : bodies) {
            List<Object> data = Json.list(body.get("data"));
            for (Object item : data) {
                Map<String, Object> ch = Json.map(item);
                Map<String, Object> attrs = Json.map(ch.get("attributes"));
                Integer pages = Json.integer(attrs.get("pages"));
                String external = Json.str(attrs.get("externalUrl"));
                if ((pages == null || pages == 0) && external == null) {
                    continue;
                }
                String number = Json.str(attrs.get("chapter"));
                String key = number == null ? Json.str(ch.get("id")) : number;
                String group = Json.list(ch.get("relationships")).stream()
                        .map(Json::map)
                        .filter(r -> "scanlation_group".equals(r.get("type")))
                        .map(r -> Json.str(Json.map(r.get("attributes")).get("name")))
                        .filter(n -> n != null)
                        .findFirst().orElse(null);
                ChapterRef ref = new ChapterRef(Json.str(ch.get("id")), number, Json.str(attrs.get("title")),
                        Json.str(attrs.get("translatedLanguage")), pages == null ? 0 : pages, group,
                        Json.str(attrs.get("publishAt")), external);
                // A hosted scan beats an external link for the same chapter number.
                byNumber.merge(key, ref, (old, neu) -> old.externalUrl() != null && neu.externalUrl() == null ? neu : old);
            }
        }
        return List.copyOf(byNumber.values());
    }

    /** Data-saver images: the vertical reader is width-bound, so full-res mostly wastes bandwidth. */
    @Override
    public Pages pages(String chapterId, String lang) {
        return pagesCache.get("pages:" + chapterId, () -> {
            Map<String, Object> body = get(b -> b.path("/at-home/server/{id}").build(chapterId));
            String baseUrl = Json.str(body.get("baseUrl"));
            Map<String, Object> chapter = Json.map(body.get("chapter"));
            String hash = Json.str(chapter.get("hash"));
            List<Object> files = Json.list(chapter.get("dataSaver"));
            if (hash == null || files.isEmpty()) {
                throw new NoSuchElementException("chapter " + chapterId + " has no pages on MangaDex");
            }
            return new Pages(chapterId,
                    files.stream().map(f -> baseUrl + "/data-saver/" + hash + "/" + f).toList(),
                    files.stream().map(f -> ORIGIN_UPLOADS + "/data-saver/" + hash + "/" + f).toList(),
                    List.of(), "MangaDex · scanlation group credited per chapter");
        });
    }

    private Series toSeries(Map<String, Object> manga) {
        String id = Json.str(manga.get("id"));
        Map<String, Object> attrs = Json.map(manga.get("attributes"));
        List<String> langs = Json.list(attrs.get("availableTranslatedLanguages")).stream()
                .map(Json::str).filter(l -> l != null).toList();
        if (id != null && !langs.isEmpty()) {
            if (available.size() > 5000) {
                available.clear();
            }
            available.put(id, langs);
        }
        List<Map<String, Object>> rel = Json.list(manga.get("relationships")).stream().map(Json::map).toList();
        String cover = rel.stream()
                .filter(r -> "cover_art".equals(r.get("type")))
                .map(r -> Json.str(Json.map(r.get("attributes")).get("fileName")))
                .filter(f -> f != null)
                .findFirst()
                .map(f -> ORIGIN_UPLOADS + "/covers/" + id + "/" + f + ".512.jpg")
                .orElse(null);
        String author = rel.stream()
                .filter(r -> "author".equals(r.get("type")))
                .map(r -> Json.str(Json.map(r.get("attributes")).get("name")))
                .filter(n -> n != null)
                .findFirst().orElse(null);
        List<String> tags = Json.list(attrs.get("tags")).stream()
                .map(t -> Json.localized(Json.map(Json.map(t).get("attributes")).get("name")))
                .filter(t -> t != null)
                .limit(8)
                .toList();
        return new Series("mangadex", id, englishTitle(attrs), Json.localized(attrs.get("description")),
                cover, contentType(Json.str(attrs.get("originalLanguage"))), Json.str(attrs.get("status")),
                Json.integer(attrs.get("year")), tags, author);
    }

    /** Prefer an English name from title or altTitles so "Solo Leveling" matches, not only the romanised Korean. */
    static String englishTitle(Map<String, Object> attrs) {
        Map<String, Object> title = Json.map(attrs.get("title"));
        if (title.containsKey("en")) {
            return Json.str(title.get("en"));
        }
        for (Object alt : Json.list(attrs.get("altTitles"))) {
            Map<String, Object> m = Json.map(alt);
            if (m.containsKey("en")) {
                return Json.str(m.get("en"));
            }
        }
        for (String key : List.of("ja-ro", "ko-ro", "zh-ro")) {
            if (title.containsKey(key)) {
                return Json.str(title.get(key));
            }
        }
        return Json.localized(attrs.get("title"));
    }

    static String contentType(String originalLanguage) {
        if (originalLanguage == null) {
            return "MANGA";
        }
        return switch (originalLanguage) {
            case "ko" -> "MANHWA";
            case "zh", "zh-hk" -> "MANHUA";
            case "ja" -> "MANGA";
            default -> "WEBCOMIC";
        };
    }
}
