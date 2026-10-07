package com.aniga.backend.read;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

/**
 * MangaTown (www.mangatown.com). English listings, series pages, and chapter images.
 * Series id is the site slug. Chapter id is {@code slug:chapterSlug}.
 */
@Component
public class MangaTownAdapter implements SourceAdapter {

    private static final String CODE = "mangatown";
    private static final String BASE = "https://www.mangatown.com";

    private final HtmlFetch html;
    private final TtlCache<Document> details = new TtlCache<>(Duration.ofMinutes(20), 400);

    public MangaTownAdapter(HtmlFetch html) {
        this.html = html;
    }

    private Document page(String url) {
        return html.get(url, HtmlFetch.BROWSER, 15_000);
    }

    @Override
    public Info info() {
        return new Info(CODE, "MangaTown", "CMS_FAMILY", BASE,
                "Aggregated manga reader. Chapters open on the site.",
                List.of("en"), "en", false);
    }

    @Override
    public List<Series> popular(String lang, int page) {
        if (!english(lang)) {
            return List.of();
        }
        String url = BASE + "/directory/0-0-0-0-0-0/" + (page + 1) + ".htm?last_chapter_time.za";
        return parseListing(page(url));
    }

    @Override
    public List<Series> search(String query, String lang, int page) {
        if (!english(lang) || query == null || query.isBlank()) {
            return List.of();
        }
        String url = BASE + "/search?name=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
                + "&page=" + (page + 1);
        return parseListing(page(url));
    }

    @Override
    public Series series(String id) {
        String slug = cleanSlug(id);
        Document doc = detail(slug);
        Element root = doc.selectFirst("section.main div.article_content");
        if (root == null) {
            throw new NoSuchElementException("MangaTown series not found: " + slug);
        }
        Element info = root.selectFirst("div.detail_info ul");
        String title = text(doc, "h1");
        String cover = absSrc(doc.selectFirst("div.detail_info img"));
        // The synopsis sits in child nodes of #show, so ownText() came back empty — take the full text.
        Element show = info != null ? info.getElementById("show") : null;
        String description = show != null && !show.text().isBlank() ? show.text().trim() : null;
        String author = findInfo(info, "Author:");
        String status = mapStatus(findInfo(info, "Status:"));
        List<String> tags = new ArrayList<>();
        if (info != null) {
            Element genreLi = info.select("li").stream()
                    .filter(li -> "Genre(s):".equals(text(li, "b")))
                    .findFirst().orElse(null);
            if (genreLi != null) {
                genreLi.select("a").forEach(a -> tags.add(a.attr("title")));
            }
        }
        return new Series(CODE, slug, title, description, cover, "MANGA", status, null, tags, author);
    }

    @Override
    public List<ChapterRef> chapters(String seriesId, String lang) {
        if (!english(lang)) {
            return List.of();
        }
        String slug = cleanSlug(seriesId);
        Document doc = detail(slug);
        Elements lis = doc.select("div.chapter_content ul.chapter_list li");
        SimpleDateFormat df = new SimpleDateFormat("MMM dd,yyyy", Locale.US);
        // Dedupe by the chapter's path and sort by chapter number so partial/duplicated page loads can't
        // shuffle or shrink the list (Berserk in particular has volume segments like /berserk/v38/c364/).
        LinkedHashMap<String, ChapterRef> byPath = new LinkedHashMap<>();
        for (Element li : lis) {
            Element a = li.selectFirst("a");
            if (a == null) {
                continue;
            }
            String rel = chapterPath(slug, a.attr("href"));
            if (rel.isBlank() || byPath.containsKey(rel)) {
                continue;
            }
            String number = chapterNumber(rel);
            String title = li.select("span").stream()
                    .filter(s -> s.className().isEmpty())
                    .map(Element::text)
                    .reduce((left, right) -> left + " - " + right)
                    .orElse(a.text());
            String published = parseRelativeOrDate(text(li, "span.time"), df);
            byPath.put(rel, new ChapterRef(slug + ":" + rel, number, title, "en", 0, null, published, null));
        }
        List<ChapterRef> result = new ArrayList<>(byPath.values());
        result.sort(Comparator.comparingDouble(c -> parseNumber(c.number())));
        return result;
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
        // parts[1] is the full chapter path (e.g. "c012" or "v38/c364"), so volumes survive the round trip.
        Document doc = page(BASE + "/manga/" + parts[0] + "/" + parts[1] + "/");
        Elements imgs = doc.select("img.image");
        if (!imgs.isEmpty()) {
            List<String> urls = imgs.stream().map(this::absSrc).filter(Objects::nonNull)
                    .filter(src -> !src.contains("ocover") && !src.contains("loader") && !src.contains("/images/manga_"))
                    .toList();
            if (!urls.isEmpty()) {
                return new Pages(chapterId, urls, List.of(), List.of(), "MangaTown");
            }
        }
        List<String> pageUrls = new ArrayList<>();
        for (Element opt : doc.select("div.page_select select option")) {
            String val = opt.attr("value");
            if (val.isBlank() || val.endsWith("featured.html")) {
                continue;
            }
            String target = val.startsWith("http") ? val : BASE + (val.startsWith("/") ? val : "/" + val);
            Element img = page(target).getElementById("image");
            if (img != null) {
                String src = absSrc(img);
                if (src != null) {
                    pageUrls.add(src);
                }
            }
        }
        return new Pages(chapterId, pageUrls, List.of(), List.of(), "MangaTown");
    }

    private Document detail(String id) {
        return details.get(id, () -> page(BASE + "/manga/" + id));
    }

    private List<Series> parseListing(Document doc) {
        Element root = doc.selectFirst("ul.manga_pic_list");
        if (root == null) {
            return List.of();
        }
        List<Series> list = new ArrayList<>();
        for (Element li : root.select("li")) {
            Element a = li.selectFirst("a.manga_cover");
            if (a == null) {
                continue;
            }
            String id = cleanSlug(a.attr("href").replaceAll(".*/manga/", "").replaceAll("/.*", ""));
            if (id.isBlank()) {
                continue;
            }
            String title = a.attr("title");
            String cover = absSrc(a.selectFirst("img"));
            String status = null;
            for (Element p : li.select("p.view")) {
                String t = p.ownText();
                if (t.startsWith("Status:")) {
                    status = mapStatus(t.substring(7).trim());
                }
            }
            list.add(new Series(CODE, id, title, null, cover, "MANGA", status, null, List.of(), null));
        }
        return list;
    }

    private static String findInfo(Element info, String label) {
        if (info == null) {
            return null;
        }
        for (Element li : info.select("li")) {
            if (li.text().startsWith(label)) {
                return li.text().substring(label.length()).trim();
            }
        }
        return null;
    }

    private static boolean english(String lang) {
        return lang == null || lang.isBlank() || "en".equalsIgnoreCase(lang);
    }

    private static String mapStatus(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("ongoing")) {
            return "ongoing";
        }
        if (s.startsWith("completed")) {
            return "completed";
        }
        return null;
    }

    private String absSrc(Element img) {
        if (img == null) {
            return null;
        }
        String src = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
        if (src == null || src.isBlank()) {
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

    private static String text(Element root, String css) {
        Element e = root.selectFirst(css);
        return e != null ? e.text().trim() : null;
    }

    private static String parseRelativeOrDate(String raw, SimpleDateFormat df) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.contains("Today")) {
            return Instant.now().toString();
        }
        if (raw.contains("Yesterday")) {
            return Instant.now().minus(1, ChronoUnit.DAYS).toString();
        }
        try {
            return df.parse(raw).toInstant().toString();
        } catch (Exception e) {
            return raw;
        }
    }

    private static final Pattern CHAPTER_NO = Pattern.compile("c0*(\\d+(?:\\.\\d+)?)");

    /** Relative chapter path after {@code /manga/{slug}/} with no trailing slash, keeping any volume segment. */
    static String chapterPath(String slug, String href) {
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
        path = path.replace(".html", "");
        int marker = path.indexOf("/manga/" + slug + "/");
        if (marker >= 0) {
            path = path.substring(marker + ("/manga/" + slug + "/").length());
        } else {
            int slash = path.lastIndexOf('/', path.endsWith("/") ? path.length() - 2 : path.length() - 1);
            path = slash >= 0 ? path.substring(slash + 1) : path;
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    /** Pulls the chapter number out of a path like "v38/c364" or "c012.5"; falls back to a running label. */
    static String chapterNumber(String rel) {
        Matcher m = CHAPTER_NO.matcher(rel);
        String found = null;
        while (m.find()) {
            found = m.group(1);
        }
        return found != null ? found : rel;
    }

    private static double parseNumber(String number) {
        try {
            return Double.parseDouble(number);
        } catch (NumberFormatException e) {
            return Double.MAX_VALUE;
        }
    }

    static String cleanSlug(String raw) {
        String slug = tail(raw);
        int colon = slug.indexOf(':');
        return colon < 0 ? slug : slug.substring(0, colon);
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
        return new Pages(chapterId, List.of(), List.of(), List.of(), "MangaTown");
    }
}
