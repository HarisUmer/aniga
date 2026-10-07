package com.aniga.backend.read;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * WEBTOON Canvas (creator-uploaded). The public ranking/search/list pages and the official
 * RSS feed are scraped; episode images stay on WEBTOON (every chapter is a link-out).
 * This is the legal scraping lesson — CSS selectors, pagination, rate limits — without
 * pulling licensed Originals or unlicensed mirrors.
 */
@Component
public class WebtoonCanvasAdapter implements SourceAdapter {

    private static final String HOME = "https://www.webtoons.com";
    private static final Pattern TITLE_NO = Pattern.compile("title_no=(\\d+)");
    private static final Pattern SLUG = Pattern.compile("/en/canvas/([^/?#]+)/");
    private static final Pattern EPISODE = Pattern.compile("episode_no=(\\d+)");
    private static final int POPULAR_PAGE = 24;

    private final TtlCache<Object> cache = new TtlCache<>(Duration.ofMinutes(30), 400);
    private final HtmlFetch html;

    public WebtoonCanvasAdapter(HtmlFetch html) {
        this.html = html;
    }

    private Document page(String url) {
        return html.get(url, HtmlFetch.BROWSER, 10_000);
    }

    @Override
    public Info info() {
        return new Info("webtoon-canvas", "Webtoon Canvas", "CMS_FAMILY", HOME + "/en/canvas",
                "Creator-uploaded Canvas. Lists scraped from the public site; pages open on WEBTOON.",
                List.of("en"), "en", true);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Series> popular(String lang, int page) {
        if (page > 0) {
            return List.of();
        }
        return (List<Series>) cache.get("popular", () -> parseListing(page(HOME + "/en/ranking/canvas"), POPULAR_PAGE));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Series> search(String query, String lang, int page) {
        if (page > 0 || query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.trim();
        return (List<Series>) cache.get("search:" + q.toLowerCase(Locale.ROOT), () -> {
            Document doc = page(HOME + "/en/search?keyword=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8));
            return parseListing(doc, 12);
        });
    }

    @Override
    public Series series(String id) {
        CanvasRef ref = CanvasRef.parse(id);
        return (Series) cache.get("series:" + ref.titleNo(), () -> parseSeries(page(ref.listUrl()), ref));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ChapterRef> chapters(String seriesId, String lang) {
        return (List<ChapterRef>) cache.get("chapters:" + seriesId, () -> {
            Series s = series(seriesId);
            CanvasRef ref = CanvasRef.parse(seriesId);
            Document doc = page(ref.listUrl());
            int latest = latestEpisode(doc);
            if (latest <= 0) {
                return List.of();
            }
            List<ChapterRef> out = new ArrayList<>(latest);
            for (int n = 1; n <= latest; n++) {
                out.add(new ChapterRef(ref.titleNo() + ":" + n, String.valueOf(n),
                        "Episode " + n, "en", 0, s.author(), null, ref.viewerUrl(n)));
            }
            return out;
        });
    }

    @Override
    public Pages pages(String chapterId, String lang) {
        throw new NoSuchElementException("Canvas episodes open on WEBTOON (official viewer), not in-app");
    }

    @Override
    public int countChapters(String seriesId, String lang) {
        try {
            CanvasRef ref = CanvasRef.parse(seriesId);
            Document doc = (Document) cache.get("list-html:" + ref.titleNo(), () -> page(ref.listUrl()));
            return Math.max(latestEpisode(doc), 0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static List<Series> parseListing(Document doc, int limit) {
        LinkedHashMap<String, Series> out = new LinkedHashMap<>();
        for (Element a : doc.select("a[href*=/canvas/][href*=title_no=]")) {
            CanvasRef ref = CanvasRef.fromHref(a.absUrl("href"));
            if (ref == null || out.containsKey(ref.id())) {
                continue;
            }
            String title = a.selectFirst("[class*=subj], .subj, h3, strong") != null
                    ? a.selectFirst("[class*=subj], .subj, h3, strong").text()
                    : a.attr("title");
            if (title == null || title.isBlank()) {
                title = slugTitle(ref.slug());
            }
            String cover = null;
            Element img = a.selectFirst("img[src]");
            if (img != null) {
                cover = img.hasAttr("src") ? img.absUrl("src") : null;
            }
            out.put(ref.id(), new Series("webtoon-canvas", ref.id(), title, null, cover,
                    "WEBCOMIC", "ongoing", null, List.of("Canvas"), null));
            if (out.size() >= limit) {
                break;
            }
        }
        return List.copyOf(out.values());
    }

    static Series parseSeries(Document doc, CanvasRef ref) {
        String title = attr(doc, "meta[property=og:title]", "content");
        if (title == null || title.isBlank()) {
            title = slugTitle(ref.slug());
        }
        String cover = attr(doc, "meta[property=og:image]", "content");
        String desc = attr(doc, "meta[property=og:description]", "content");
        String author = null;
        Element a = doc.selectFirst(".author a, a.author, .author");
        if (a != null && !a.text().isBlank()) {
            author = a.text().trim();
        }
        return new Series("webtoon-canvas", ref.id(), title, desc, cover, "WEBCOMIC", "ongoing",
                null, List.of("Canvas"), author);
    }

    static int latestEpisode(Document doc) {
        int max = 0;
        for (Element a : doc.select("a[href*=episode_no=]")) {
            Matcher m = EPISODE.matcher(a.attr("href"));
            if (m.find()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        return max;
    }

    private static String attr(Document doc, String css, String key) {
        Element e = doc.selectFirst(css);
        return e == null ? null : e.attr(key);
    }

    static String slugTitle(String slug) {
        String[] parts = slug.replace('-', ' ').split(" ");
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

    record CanvasRef(String titleNo, String slug) {
        String id() {
            return titleNo + ":" + slug;
        }

        String listUrl() {
            return HOME + "/en/canvas/" + slug + "/list?title_no=" + titleNo;
        }

        String viewerUrl(int episode) {
            return HOME + "/en/canvas/" + slug + "/viewer?title_no=" + titleNo + "&episode_no=" + episode;
        }

        static CanvasRef parse(String id) {
            int cut = id.indexOf(':');
            if (cut < 1 || cut == id.length() - 1) {
                throw new IllegalArgumentException("Canvas id must look like titleNo:slug");
            }
            return new CanvasRef(id.substring(0, cut), id.substring(cut + 1));
        }

        static CanvasRef fromHref(String href) {
            if (href == null) {
                return null;
            }
            Matcher n = TITLE_NO.matcher(href);
            Matcher s = SLUG.matcher(href);
            if (!n.find() || !s.find()) {
                return null;
            }
            return new CanvasRef(n.group(1), s.group(1));
        }
    }
}
