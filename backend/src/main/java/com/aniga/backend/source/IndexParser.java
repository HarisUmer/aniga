package com.aniga.backend.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a Mihon, Tachiyomi, or Aidoku index into catalog rows: name, language, home URL, NSFW flag.
 * The index is a list of scraper plugins, not the comics themselves. Aidoku legacy rows have no
 * site URL (it lives inside the .aix); they are kept by name so the directory is still complete.
 */
public final class IndexParser {

    public record Entry(String id, String name, String lang, String homeUrl, boolean nsfw,
                        String extensionName, String packageName, String version, String repo,
                        String contentHint) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private IndexParser() {
    }

    public static List<Entry> parse(String repo, String body) {
        if (repo == null || repo.isBlank() || body == null || body.isBlank()) {
            return List.of();
        }
        try {
            return parseTree(repo, JSON.readTree(body));
        } catch (Exception e) {
            return List.of();
        }
    }

    static List<Entry> parseTree(String repo, JsonNode root) {
        List<Entry> out = new ArrayList<>();
        if (root == null || root.isNull()) {
            return out;
        }
        if (root.isObject() && root.path("extensionList").path("extensions").isArray()) {
            for (JsonNode ext : root.path("extensionList").path("extensions")) {
                boolean nsfw = "CONTENT_WARNING_NSFW".equals(ext.path("contentWarning").asText(""));
                for (JsonNode src : ext.path("sources")) {
                    add(out, repo, text(src, "id"), text(src, "name"), text(src, "language"),
                            text(src, "homeUrl"), nsfw, text(ext, "name"), text(ext, "packageName"),
                            text(ext, "versionName"));
                }
            }
            return out;
        }
        if (root.isObject() && root.path("sources").isArray()) {
            for (JsonNode src : root.path("sources")) {
                JsonNode langs = src.path("languages");
                String lang = langs.isArray() && langs.size() == 1 ? langs.get(0).asText("all")
                        : langs.isArray() && langs.size() > 1 ? "multi" : "all";
                boolean nsfw = src.path("contentRating").asInt(0) >= 1;
                add(out, repo, text(src, "id"), text(src, "name"), lang, text(src, "baseURL"), nsfw,
                        text(src, "name"), text(src, "id"), text(src, "version"));
            }
            return out;
        }
        if (root.isArray() && root.size() > 0 && root.get(0).has("apk")) {
            for (JsonNode ext : root) {
                boolean nsfw = ext.path("nsfw").asInt(0) == 1;
                for (JsonNode src : ext.path("sources")) {
                    add(out, repo, text(src, "id"), text(src, "name"),
                            src.has("lang") ? text(src, "lang") : text(ext, "lang"),
                            text(src, "baseUrl"), nsfw, text(ext, "name"), text(ext, "pkg"), text(ext, "version"));
                }
            }
            return out;
        }
        if (root.isArray()) {
            for (JsonNode src : root) {
                if (!src.has("file") && !src.has("id")) {
                    continue;
                }
                add(out, repo, text(src, "id"), text(src, "name"), text(src, "lang"), null,
                        src.path("nsfw").asInt(0) == 1, text(src, "name"), text(src, "id"), text(src, "version"));
            }
        }
        return out;
    }

    private static void add(List<Entry> out, String repo, String id, String name, String lang, String home,
                            boolean nsfw, String extension, String pkg, String version) {
        if (id == null || id.isBlank() || name == null || name.isBlank()) {
            return;
        }
        String language = lang == null || lang.isBlank() ? "all" : lang;
        String ext = extension == null || extension.isBlank() ? name : extension;
        out.add(new Entry(repo + ":" + id, name, language, blankToNull(home), nsfw, ext,
                blankToNull(pkg), blankToNull(version), repo, hint(language, name, home)));
    }

    static String hint(String lang, String name, String url) {
        String text = ((name == null ? "" : name) + " " + (url == null ? "" : url)).toLowerCase(Locale.ROOT);
        String language = lang == null ? "" : lang.toLowerCase(Locale.ROOT);
        if (text.contains("manhua") || text.contains("bilibili") || text.contains("kuaikan")
                || text.contains("dmzj") || language.startsWith("zh")) {
            return "MANHUA";
        }
        if (text.contains("toon") || text.contains("manhwa") || text.contains("webtoon")
                || text.contains("naver") || text.contains("kakao") || "ko".equals(language)) {
            return "MANHWA";
        }
        if ("ja".equals(language)) {
            return "MANGA";
        }
        return "MIXED";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String s = value.asText();
        return s == null || s.isBlank() || "null".equals(s) ? null : s;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
