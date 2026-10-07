package com.aniga.backend.mangadex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The one enabled source adapter: MangaDex's official public API.
 * Responses are read as plain maps because MangaDex returns `[]` instead of `{}` for some empty
 * localized fields, which breaks strict record binding.
 */
@Component
public class MangaDexClient {

    public record MangaSummary(String id, String title, String description, Integer year, String status,
                               String originalLanguage, String coverUrl) {
    }

    public record Chapter(String id, String chapter, String title, String language, int pages,
                          List<String> scanlationGroups) {
    }

    /** fallbackUrls point at the MangaDex origin; MD@Home cache nodes can 404 on a cold cache miss. */
    public record ChapterPages(String chapterId, List<String> pageUrls, List<String> fallbackUrls) {
    }

    private static final String ORIGIN_UPLOADS = "https://uploads.mangadex.org";

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private final RestClient http;

    public MangaDexClient(@Value("${aniga.mangadex.base-url}") String baseUrl,
                          @Value("${aniga.mangadex.user-agent}") String userAgent) {
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .build();
    }

    public List<MangaSummary> search(String title, int limit) {
        Map<String, Object> body = http.get()
                .uri(b -> b.path("/manga")
                        .queryParam("title", "{title}")
                        .queryParam("limit", limit)
                        .queryParam("includes[]", "cover_art")
                        .queryParam("contentRating[]", "safe", "suggestive")
                        .queryParam("order[relevance]", "desc")
                        .build(title))
                .retrieve()
                .body(JSON);

        List<MangaSummary> out = new ArrayList<>();
        for (Object item : Json.list(body.get("data"))) {
            Map<String, Object> manga = Json.map(item);
            String id = Json.str(manga.get("id"));
            Map<String, Object> attrs = Json.map(manga.get("attributes"));
            out.add(new MangaSummary(
                    id,
                    Json.localized(attrs.get("title")),
                    Json.localized(attrs.get("description")),
                    attrs.get("year") instanceof Number n ? n.intValue() : null,
                    Json.str(attrs.get("status")),
                    Json.str(attrs.get("originalLanguage")),
                    coverUrl(id, Json.list(manga.get("relationships")))));
        }
        return out;
    }

    public List<Chapter> chapters(String mangaId, String language, int limit) {
        int page = Math.min(Math.max(limit, 1), 100);
        Map<String, Object> body = http.get()
                .uri(b -> b.path("/manga/{id}/feed")
                        .queryParam("translatedLanguage[]", language)
                        .queryParam("order[chapter]", "asc")
                        .queryParam("limit", page)
                        .queryParam("includes[]", "scanlation_group")
                        .queryParam("contentRating[]", "safe", "suggestive")
                        .build(mangaId))
                .retrieve()
                .body(JSON);

        List<Chapter> out = new ArrayList<>();
        for (Object item : Json.list(body.get("data"))) {
            Map<String, Object> ch = Json.map(item);
            Map<String, Object> attrs = Json.map(ch.get("attributes"));
            int pages = attrs.get("pages") instanceof Number n ? n.intValue() : 0;
            if (pages == 0) {
                continue;
            }
            List<String> groups = Json.list(ch.get("relationships")).stream()
                    .map(Json::map)
                    .filter(r -> "scanlation_group".equals(r.get("type")))
                    .map(r -> Json.str(Json.map(r.get("attributes")).get("name")))
                    .filter(n -> n != null)
                    .toList();
            out.add(new Chapter(Json.str(ch.get("id")), Json.str(attrs.get("chapter")),
                    Json.str(attrs.get("title")), Json.str(attrs.get("translatedLanguage")), pages, groups));
        }
        return out;
    }

    /** Uses data-saver images: smaller files, and the reader is a vertical scroll where full-res is rarely needed. */
    public ChapterPages pages(String chapterId) {
        Map<String, Object> body = http.get()
                .uri("/at-home/server/{id}", chapterId)
                .retrieve()
                .body(JSON);
        String baseUrl = Json.str(body.get("baseUrl"));
        Map<String, Object> chapter = Json.map(body.get("chapter"));
        String hash = Json.str(chapter.get("hash"));
        List<Object> files = Json.list(chapter.get("dataSaver"));
        List<String> urls = files.stream().map(f -> baseUrl + "/data-saver/" + hash + "/" + f).toList();
        List<String> fallback = files.stream().map(f -> ORIGIN_UPLOADS + "/data-saver/" + hash + "/" + f).toList();
        return new ChapterPages(chapterId, urls, fallback);
    }

    private static String coverUrl(String mangaId, List<Object> relationships) {
        return relationships.stream()
                .map(Json::map)
                .filter(r -> "cover_art".equals(r.get("type")))
                .map(r -> Json.str(Json.map(r.get("attributes")).get("fileName")))
                .filter(f -> f != null)
                .findFirst()
                .map(f -> "https://uploads.mangadex.org/covers/" + mangaId + "/" + f + ".256.jpg")
                .orElse(null);
    }
}
