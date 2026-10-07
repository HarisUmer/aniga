package com.aniga.backend.read;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.aniga.backend.mangadex.Json;

/**
 * Pepper&Carrot by David Revoy: an open webcomic under CC BY 4.0, so pages may be shown in-app with credit.
 * The site publishes its own episode index (episodes-v1.json) listing page files and translations.
 */
@Component
public class PepperCarrotAdapter implements SourceAdapter {

    static final String SERIES_ID = "pepper-carrot";
    private static final String BASE = "https://www.peppercarrot.com/0_sources/";
    private static final ParameterizedTypeReference<List<Map<String, Object>>> EPISODES = new ParameterizedTypeReference<>() {
    };

    private final RestClient http;
    private final TtlCache<List<Map<String, Object>>> cache = new TtlCache<>(Duration.ofHours(6), 4);

    public PepperCarrotAdapter(@Value("${aniga.mangadex.user-agent}") String userAgent) {
        this.http = RestClient.builder().baseUrl(BASE).defaultHeader("User-Agent", userAgent).build();
    }

    private List<Map<String, Object>> episodes() {
        return cache.get("episodes", () -> http.get().uri("episodes-v1.json").retrieve().body(EPISODES));
    }

    @Override
    public Info info() {
        TreeSet<String> langs = new TreeSet<>();
        try {
            episodes().forEach(e -> Json.list(e.get("translated_languages")).forEach(l -> langs.add(Json.str(l))));
        } catch (RuntimeException e) {
            langs.add("en");
        }
        List<String> ordered = new ArrayList<>(List.of("en"));
        langs.stream().filter(l -> !l.equals("en")).forEach(ordered::add);
        return new Info("peppercarrot", "Pepper&Carrot", "OFFICIAL_API", "https://www.peppercarrot.com",
                "CC BY 4.0 — David Revoy, www.peppercarrot.com", ordered, "en", false);
    }

    @Override
    public List<Series> popular(String lang, int page) {
        return page == 0 ? List.of(series(SERIES_ID)) : List.of();
    }

    @Override
    public List<Series> search(String query, String lang, int page) {
        String q = query.toLowerCase(Locale.ROOT);
        boolean hit = List.of("pepper", "carrot", "revoy", "witch", "potion").stream().anyMatch(q::contains);
        return hit ? popular(lang, page) : List.of();
    }

    @Override
    public Series series(String id) {
        if (!SERIES_ID.equals(id)) {
            throw new NoSuchElementException("Pepper&Carrot has a single series: " + SERIES_ID);
        }
        List<Map<String, Object>> eps = episodes();
        String cover = eps.isEmpty() ? null : coverUrl(eps.getFirst());
        return new Series("peppercarrot", SERIES_ID, "Pepper&Carrot",
                "A young witch of the chaotic Chaosah school and her orange cat brew potions, flunk exams and "
                        + "stumble through a world of rival magic schools. Painted digitally by David Revoy and "
                        + "translated by volunteers into dozens of languages.",
                cover, "WEBCOMIC", "ongoing", 2014, List.of("Fantasy", "Comedy", "Magic", "Open source"), "David Revoy");
    }

    @Override
    public List<ChapterRef> chapters(String seriesId, String lang) {
        series(seriesId);
        String l = lang == null || lang.isBlank() || lang.equals("all") ? "en" : lang;
        List<ChapterRef> out = new ArrayList<>();
        List<Map<String, Object>> eps = episodes();
        for (int i = 0; i < eps.size(); i++) {
            Map<String, Object> e = eps.get(i);
            if (!Json.list(e.get("translated_languages")).contains(l)) {
                continue;
            }
            String name = Json.str(e.get("name"));
            Integer total = Json.integer(e.get("total_pages"));
            out.add(new ChapterRef(name, String.valueOf(i + 1), title(name), l, total == null ? 0 : total + 1,
                    "David Revoy", null, null));
        }
        return out;
    }

    @Override
    public Pages pages(String chapterId, String lang) {
        String l = lang == null || lang.isBlank() || lang.equals("all") ? "en" : lang;
        Map<String, Object> ep = episodes().stream()
                .filter(e -> chapterId.equals(Json.str(e.get("name"))))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("episode " + chapterId + " not found"));
        Map<String, Object> pages = Json.map(ep.get("pages"));
        List<String> files = new ArrayList<>();
        if (pages.get("title") != null) {
            files.add(Json.str(pages.get("title")));
        }
        pages.entrySet().stream()
                .filter(p -> p.getKey().chars().allMatch(Character::isDigit))
                .sorted(Comparator.comparingInt(p -> Integer.parseInt(p.getKey())))
                .forEach(p -> files.add(Json.str(p.getValue())));
        if (pages.get("credits") != null) {
            files.add(Json.str(pages.get("credits")));
        }
        String dir = BASE + chapterId + "/low-res/";
        List<String> urls = files.stream().map(f -> dir + l + "_" + f).toList();
        List<String> english = files.stream().map(f -> dir + "en_" + f).toList();
        return new Pages(chapterId, urls, english, List.of(), "Pepper&Carrot by David Revoy — CC BY 4.0");
    }

    private static String coverUrl(Map<String, Object> episode) {
        String cover = Json.str(Json.map(episode.get("pages")).get("cover"));
        return cover == null ? null : BASE + Json.str(episode.get("name")) + "/low-res/" + cover;
    }

    /** "ep07_The-Wish" → "The Wish". */
    static String title(String name) {
        int cut = name.indexOf('_');
        return (cut < 0 ? name : name.substring(cut + 1)).replace('-', ' ');
    }
}
