package com.aniga.backend.read;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Asura Scans (asurascans.com; asuracomic.net redirects here). English listings and chapter images.
 * Series id is the comic slug. Chapter id is {@code slug:chapterNumber}.
 */
@Component
public class AsuraComicAdapter implements SourceAdapter {

    private static final String CODE = "asura";
    private static final String BASE = "https://asurascans.com";
    private static final Pattern PUBLIC_URL = Pattern.compile("\"public_url\":\\[0,\"/comics/([^\"]+)\"");
    private static final Pattern CHAPTER = Pattern.compile("/chapter/(\\d+)");

    private final HtmlFetch html;
    private final TtlCache<Document> details = new TtlCache<>(Duration.ofMinutes(15), 400);

    public AsuraComicAdapter(HtmlFetch html) {
        this.html = html;
    }

    private Document page(String url) {
        return html.get(url, HtmlFetch.BROWSER, 15_000);
    }

    @Override
    public Info info() {
        return new Info(CODE, "AsuraComic", "CMS_FAMILY", BASE,
                "Unofficial fan translations. Content is hosted by the site.",
                List.of("en"), "en", true);
    }

    @Override
    public List<Series> popular(String lang, int page) {
        if (!english(lang)) {
            return List.of();
        }
        return parseListing(page(BASE + "/browse/comics?page=" + (page + 1)));
    }

    @Override
    public List<Series> search(String query, String lang, int page) {
        if (!english(lang) || query == null || query.isBlank()) {
            return List.of();
        }
        String url = BASE + "/browse/comics?search=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
                + "&page=" + (page + 1);
        return parseListing(page(url));
    }

    @Override
    public Series series(String id) {
        String slug = cleanSlug(id);
        Document doc = detail(slug);
        String title = meta(doc, "og:title");
        if (title == null || title.isBlank()) {
            title = slugTitle(slug);
        }
        String cover = meta(doc, "og:image");
        String description = meta(doc, "og:description");
        String raw = unescape(doc.html());
        String status = mapStatus(last(raw, "\"status\":\\[0,\"([^\"]+)\""));
        List<String> tags = new ArrayList<>();
        Matcher genres = Pattern.compile("\"name\":\\[0,\"([^\"]+)\"\\],\"slug\":\\[0,\"[^\"]+\"\\]\\}").matcher(raw);
        while (genres.find() && tags.size() < 12) {
            tags.add(genres.group(1));
        }
        return new Series(CODE, slug, title, description, cover, "MANHWA", status, null, tags, null);
    }

    @Override
    public List<ChapterRef> chapters(String seriesId, String lang) {
        if (!english(lang)) {
            return List.of();
        }
        String slug = cleanSlug(seriesId);
        Document doc = detail(slug);
        LinkedHashMap<Integer, ChapterRef> byNumber = new LinkedHashMap<>();
        for (Element a : doc.select("a[href*=/chapter/]")) {
            Matcher m = CHAPTER.matcher(a.attr("href"));
            if (!m.find()) {
                continue;
            }
            int number = Integer.parseInt(m.group(1));
            String title = a.text();
            if (title == null || title.isBlank()) {
                title = "Chapter " + number;
            }
            byNumber.putIfAbsent(number, new ChapterRef(slug + ":" + number, String.valueOf(number),
                    title, "en", 0, null, null, null));
        }
        return byNumber.values().stream()
                .sorted(Comparator.comparingInt(c -> Integer.parseInt(c.number())))
                .toList();
    }

    @Override
    public Pages pages(String chapterId, String lang) {
        if (!english(lang)) {
            return emptyPages(chapterId);
        }
        String[] parts = splitId(chapterId);
        if (parts == null) {
            return emptyPages(chapterId);
        }
        Document doc = page(BASE + "/comics/" + parts[0] + "/chapter/" + parts[1]);
        List<Element> imgs = new ArrayList<>(doc.select("img[data-page-index]"));
        imgs.sort(Comparator.comparingInt(img -> parseInt(img.attr("data-page-index"))));
        List<String> urls = new ArrayList<>();
        for (Element img : imgs) {
            String src = abs(img.attr("src"));
            if (src != null && src.contains("/chapters/")) {
                urls.add(src);
            }
        }
        return new Pages(chapterId, urls, List.of(), List.of(), "AsuraComic");
    }

    private Document detail(String slug) {
        return details.get(slug, () -> page(BASE + "/comics/" + slug));
    }

    private List<Series> parseListing(Document doc) {
        String raw = unescape(doc.html());
        Matcher m = PUBLIC_URL.matcher(raw);
        LinkedHashMap<String, Series> out = new LinkedHashMap<>();
        while (m.find()) {
            String slug = m.group(1);
            if (slug.isBlank() || slug.indexOf('/') >= 0 || out.containsKey(slug)) {
                continue;
            }
            String window = raw.substring(Math.max(0, m.start() - 6000), m.start());
            String title = unescapeJson(last(window, "\"title\":\\[0,\"((?:\\\\.|[^\"\\\\])*)\""));
            if (title == null || title.isBlank()) {
                title = slugTitle(slug);
            }
            String cover = last(window, "\"cover\":\\[0,\"(https:[^\"]+)\"");
            String status = mapStatus(last(window, "\"status\":\\[0,\"([^\"]+)\""));
            out.put(slug, new Series(CODE, slug, title, null, cover, "MANHWA", status, null, List.of(), null));
        }
        return List.copyOf(out.values());
    }

    private static boolean english(String lang) {
        return lang == null || lang.isBlank() || "en".equalsIgnoreCase(lang);
    }

    private static String mapStatus(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "ongoing" -> "ongoing";
            case "completed", "finished" -> "completed";
            case "hiatus" -> "hiatus";
            case "dropped", "cancelled" -> "cancelled";
            default -> null;
        };
    }

    private static String meta(Document doc, String property) {
        Element e = doc.selectFirst("meta[property=" + property + "]");
        if (e == null) {
            return null;
        }
        String value = e.attr("content");
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String last(String window, String regex) {
        Matcher m = Pattern.compile(regex).matcher(window);
        String found = null;
        while (m.find()) {
            found = m.group(1);
        }
        return found;
    }

    private static String unescape(String html) {
        return html.replace("&quot;", "\"").replace("&#34;", "\"");
    }

    private static String unescapeJson(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\\\"", "\"").replace("\\u0026", "&").replace("&amp;", "&");
    }

    private static String abs(String src) {
        if (src == null || src.isBlank()) {
            return null;
        }
        if (src.startsWith("//")) {
            return "https:" + src;
        }
        return src;
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String cleanSlug(String raw) {
        if (raw == null) {
            return "";
        }
        String slug = raw;
        int cut = slug.indexOf('?');
        if (cut >= 0) {
            slug = slug.substring(0, cut);
        }
        int colon = slug.indexOf(':');
        if (colon >= 0) {
            slug = slug.substring(0, colon);
        }
        int slash = slug.lastIndexOf('/');
        if (slash >= 0) {
            slug = slug.substring(slash + 1);
        }
        return slug;
    }

    static String slugTitle(String slug) {
        String trimmed = slug.replaceAll("-[0-9a-f]{6,}$", "").replace('-', ' ');
        String[] parts = trimmed.split(" ");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (!b.isEmpty()) {
                b.append(' ');
            }
            b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return b.toString();
    }

    private static String[] splitId(String chapterId) {
        if (chapterId == null) {
            return null;
        }
        int colon = chapterId.indexOf(':');
        if (colon < 1 || colon == chapterId.length() - 1) {
            return null;
        }
        return new String[] { chapterId.substring(0, colon), chapterId.substring(colon + 1) };
    }

    private static Pages emptyPages(String chapterId) {
        return new Pages(chapterId, List.of(), List.of(), List.of(), "AsuraComic");
    }
}
