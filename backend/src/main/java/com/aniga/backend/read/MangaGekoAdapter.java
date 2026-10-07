package com.aniga.backend.read;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * MangaGeko (www.mgeko.cc). English listings, series pages, and chapter images.
 * Series id is the slug after {@code /manga/}. Chapter id is {@code slug:readerSlug}.
 */
@Component
public class MangaGekoAdapter implements SourceAdapter {

    private static final String CODE = "mangageko";
    private static final String BASE = "https://www.mgeko.cc";
    private static final Pattern MANGA = Pattern.compile("/manga/([^/?#]+)");
    private static final Pattern CHAPTER_NO = Pattern.compile("chapter-(\\d+)");
    private static final Pattern PAGE_NO = Pattern.compile("/(\\d+)\\.(?:jpg|jpeg|png|webp)");

    private final HtmlFetch html;
    private final TtlCache<Document> details = new TtlCache<>(Duration.ofMinutes(15), 400);

    public MangaGekoAdapter(HtmlFetch html) {
        this.html = html;
    }

    private Document page(String url) {
        return html.get(url, HtmlFetch.BROWSER, 15_000);
    }

    @Override
    public Info info() {
        return new Info(CODE, "MangaGeko", "CMS_FAMILY", BASE,
                "Aggregated manga / manhwa reader.",
                List.of("en"), "en", true);
    }

    @Override
    public List<Series> popular(String lang, int page) {
        if (!english(lang)) {
            return List.of();
        }
        if (page == 0) {
            return parseListing(page(BASE + "/"));
        }
        return parseListing(page(BASE + "/browse-comics/?sort=recently_added&page=" + (page + 1)));
    }

    @Override
    public List<Series> search(String query, String lang, int page) {
        if (!english(lang) || query == null || query.isBlank() || page > 0) {
            return List.of();
        }
        return parseListing(page(BASE + "/search/?search=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)));
    }

    @Override
    public Series series(String id) {
        String slug = cleanSlug(id);
        Document doc = detail(slug);
        String title = text(doc, "h1.novel-title, h1");
        String cover = absSrc(doc.selectFirst("img.lazy, .novel-cover img, img"));
        String description = text(doc, ".description");
        String author = text(doc, ".author");
        if (author != null) {
            author = author.replace("Author(S):", "").trim();
        }
        String statusRaw = text(doc, ".header-stats span:contains(Status) strong");
        String status = statusRaw == null ? null
                : statusRaw.equalsIgnoreCase("Ongoing") ? "ongoing"
                : statusRaw.equalsIgnoreCase("Completed") ? "completed" : null;
        List<String> tags = doc.select(".categories ul li a")
                .stream().map(Element::text).filter(s -> !s.isBlank()).toList();
        return new Series(CODE, slug, title, description, cover, "MANHWA", status, null, tags, author);
    }

    @Override
    public List<ChapterRef> chapters(String seriesId, String lang) {
        if (!english(lang)) {
            return List.of();
        }
        String slug = cleanSlug(seriesId);
        Document doc = details.get("chapters:" + slug, () -> page(BASE + "/manga/" + slug + "/all-chapters/"));
        LinkedHashMap<String, ChapterRef> out = new LinkedHashMap<>();
        List<ChapterRef> numbered = new ArrayList<>();
        for (Element a : doc.select("a[href*=/reader/]")) {
            String chapterSlug = tail(a.attr("href"));
            if (chapterSlug.isBlank() || out.containsKey(chapterSlug)) {
                continue;
            }
            String title = a.text();
            Matcher number = CHAPTER_NO.matcher(chapterSlug);
            String shown = number.find() ? number.group(1) : String.valueOf(numbered.size() + 1);
            if (title == null || title.isBlank()) {
                title = "Chapter " + shown;
            }
            ChapterRef ref = new ChapterRef(slug + ":" + chapterSlug, shown, title, "en", 0, null, null, null);
            out.put(chapterSlug, ref);
            numbered.add(ref);
        }
        numbered.sort(Comparator.comparingInt(c -> {
            try {
                return Integer.parseInt(c.number());
            } catch (NumberFormatException e) {
                return 0;
            }
        }));
        return numbered;
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
        Document doc = page(BASE + "/reader/en/" + parts[1] + "/");
        // The reader renders one <img id="image-N"> per page, served from a CDN such as
        // imgsrv5.com/.../cdn_mangaraw/<slug>/chapter-<n>/<page>.jpg. Older markup used
        // /sv2/comic/ paths, so accept both shapes.
        List<Element> imgs = doc.select("img[id^=image-]");
        if (imgs.isEmpty()) {
            imgs = doc.select(".chapter-images img, #chapter-images img, .reader img");
        }
        List<String> urls = new ArrayList<>();
        for (Element img : imgs.isEmpty() ? doc.select("img[src], img[data-src]") : imgs) {
            String src = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
            if (src == null || src.isBlank() || src.startsWith("data:image")) {
                continue;
            }
            if (src.contains("logo") || src.contains("loading_api") || src.contains("placeholder")
                    || src.contains("/static/")) {
                continue;
            }
            if (src.startsWith("//")) {
                src = "https:" + src;
            } else if (src.startsWith("/")) {
                src = BASE + src;
            }
            if (!urls.contains(src)) {
                urls.add(src);
            }
        }
        urls.sort(Comparator.comparingInt(MangaGekoAdapter::pageNumber));
        return new Pages(chapterId, urls, List.of(), List.of(), "MangaGeko");
    }

    private Document detail(String id) {
        return details.get(id, () -> page(BASE + "/manga/" + id + "/"));
    }

    private List<Series> parseListing(Document doc) {
        LinkedHashMap<String, Series> out = new LinkedHashMap<>();
        for (Element a : doc.select("a[href*=/manga/]")) {
            String slug = mangaSlug(a.attr("href"));
            if (slug.isBlank() || out.containsKey(slug)) {
                continue;
            }
            String title = a.attr("title");
            if (title == null || title.isBlank()) {
                Element heading = a.selectFirst("h4, h5, .novel-title");
                title = heading != null ? heading.text().trim() : a.text().trim();
            }
            if (title == null || title.isBlank()) {
                continue;
            }
            String cover = absSrc(a.selectFirst("img"));
            String author = text(a, "h6");
            if (author != null) {
                author = author.replace("Author(S):", "").trim();
            }
            out.put(slug, new Series(CODE, slug, title, null, cover, "MANHWA", null, null, List.of(), author));
        }
        return List.copyOf(out.values());
    }

    private static boolean english(String lang) {
        return lang == null || lang.isBlank() || "en".equalsIgnoreCase(lang);
    }

    private static String text(Element root, String css) {
        if (root == null) {
            return null;
        }
        Element e = root.selectFirst(css);
        return e != null && !e.text().isBlank() ? e.text().trim() : null;
    }

    private String absSrc(Element img) {
        if (img == null) {
            return null;
        }
        String src = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
        if (src == null || src.isBlank() || src.contains("placeholder") || src.startsWith("data:image")) {
            return null;
        }
        if (src.startsWith("//")) {
            return "https:" + src;
        }
        if (src.startsWith("/")) {
            return BASE + src;
        }
        return src;
    }

    static String mangaSlug(String href) {
        if (href == null) {
            return "";
        }
        Matcher m = MANGA.matcher(href);
        return m.find() ? m.group(1) : "";
    }

    static String cleanSlug(String raw) {
        if (raw == null) {
            return "";
        }
        String slug = mangaSlugOrRaw(raw);
        int colon = slug.indexOf(':');
        return colon < 0 ? slug : slug.substring(0, colon);
    }

    private static String mangaSlugOrRaw(String raw) {
        Matcher m = MANGA.matcher(raw);
        if (m.find()) {
            return m.group(1);
        }
        String path = raw;
        int cut = path.indexOf('?');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    static String tail(String href) {
        if (href == null) {
            return "";
        }
        String path = href;
        int cut = path.indexOf('?');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        cut = path.indexOf('#');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static int pageNumber(String url) {
        Matcher m = PAGE_NO.matcher(url);
        int n = 0;
        while (m.find()) {
            n = Integer.parseInt(m.group(1));
        }
        return n;
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
        return new Pages(chapterId, List.of(), List.of(), List.of(), "MangaGeko");
    }
}
