package com.aniga.backend.share;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aniga.backend.auth.AuthFilter;
import com.aniga.backend.media.CatalogCache;
import com.aniga.backend.media.Media;
import com.aniga.backend.read.TtlCache;
import com.aniga.backend.recommend.HybridRecommender;
import com.aniga.backend.user.RatingRepository;

import jakarta.servlet.http.HttpServletRequest;

/**
 * A taste report the owner can hand to a friend as a link. The link is a random token, not a user id,
 * so the ratings and genre weights are visible only to someone who was given the address.
 */
@RestController
public class ShareReportController {

    public record Link(String token, String path) {
    }

    public record GenreBar(String name, double weight) {
    }

    public record RatedTitle(String title, String mediaType, Integer year, int score, String coverUrl) {
    }

    public record Report(String name, String username, int ratingCount, double average,
                         Map<String, Integer> counts, List<GenreBar> genres, List<String> liked,
                         List<RatedTitle> ratings) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final RatingRepository ratings;
    private final CatalogCache catalog;
    private final TtlCache<Report> cache = new TtlCache<>(Duration.ofMinutes(2), 200);

    public ShareReportController(JdbcClient jdbc, RatingRepository ratings, CatalogCache catalog) {
        this.jdbc = jdbc;
        this.ratings = ratings;
        this.catalog = catalog;
    }

    /** Creates the owner's link the first time, and returns the same one after that. */
    @PostMapping("/api/me/report")
    public Link ensure(HttpServletRequest request) {
        long userId = AuthFilter.userId(request);
        String existing = jdbc.sql("SELECT token FROM share_report WHERE user_id = :u")
                .param("u", userId).query(String.class).optional().orElse(null);
        if (existing != null) {
            return new Link(existing, "/r/" + existing);
        }
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.sql("INSERT INTO share_report (user_id, token) VALUES (:u, :t)")
                .param("u", userId).param("t", token).update();
        return new Link(token, "/r/" + token);
    }

    @GetMapping("/api/share/{token}")
    public Report json(@PathVariable String token) {
        return report(token);
    }

    @GetMapping(value = "/r/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public String page(@PathVariable String token) {
        return html(report(token));
    }

    private Report report(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{16,64}")) {
            throw new NoSuchElementException("report not found");
        }
        return cache.get(token, () -> build(token));
    }

    private Report build(String token) {
        Long userId = jdbc.sql("SELECT user_id FROM share_report WHERE token = :t")
                .param("t", token).query(Long.class).optional()
                .orElseThrow(() -> new NoSuchElementException("report not found"));
        var who = jdbc.sql("SELECT username, display_name FROM app_user WHERE id = :id")
                .param("id", userId)
                .query((rs, i) -> new String[]{rs.getString(1), rs.getString(2)})
                .single();
        String username = who[0];
        String display = who[1] == null || who[1].isBlank() ? username : who[1];

        Map<Long, Integer> scores = ratings.scoresByMedia(userId);
        var snap = catalog.get();
        List<RatedTitle> rated = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        long sum = 0;
        for (var entry : scores.entrySet()) {
            Media media = snap.byId().get(entry.getKey());
            if (media == null) {
                continue;
            }
            String type = media.mediaType() == null ? "OTHER" : media.mediaType();
            counts.merge(type, 1, Integer::sum);
            sum += entry.getValue();
            rated.add(new RatedTitle(media.title(), type, media.startYear(), entry.getValue(), media.coverUrl()));
        }
        rated.sort(Comparator.comparingInt(RatedTitle::score).reversed()
                .thenComparing(RatedTitle::title, String.CASE_INSENSITIVE_ORDER));

        HybridRecommender.TasteProfile taste = new HybridRecommender(snap.all()).taste(scores);
        List<GenreBar> genres = taste.genreWeights().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(12)
                .map(e -> new GenreBar(e.getKey(), round2(e.getValue())))
                .toList();
        List<String> liked = taste.likedTerms().stream().map(Map.Entry::getKey).limit(8).toList();
        double average = rated.isEmpty() ? 0 : round2(sum / (double) rated.size());
        return new Report(display, username, rated.size(), average, counts, genres, liked, rated);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String html(Report report) {
        StringBuilder body = new StringBuilder();
        body.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        body.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        body.append("<title>").append(esc(report.name())).append(" · Aniga taste report</title>");
        body.append("<style>");
        body.append("body{margin:0;background:#15120f;color:#f1e9da;font-family:Georgia,serif;}");
        body.append("main{max-width:760px;margin:0 auto;padding:28px 18px 64px;}");
        body.append("h1{font-size:34px;margin:6px 0 4px;}");
        body.append(".kicker{letter-spacing:.16em;font-size:12px;color:#e5532d;font-family:sans-serif;}");
        body.append(".meta{color:#b7aa96;font-family:sans-serif;font-size:14px;}");
        body.append("h2{font-size:13px;letter-spacing:.14em;font-family:sans-serif;margin:28px 0 10px;}");
        body.append(".bar{display:flex;align-items:center;gap:10px;margin:6px 0;font-family:sans-serif;font-size:14px;}");
        body.append(".bar i{display:block;height:8px;border-radius:4px;background:#4db38a;}");
        body.append(".bar i.down{background:#e5532d;}");
        body.append(".bar span{width:120px;}");
        body.append(".chips span{display:inline-block;border:1px solid #3a332c;border-radius:99px;");
        body.append("padding:4px 10px;margin:0 6px 6px 0;font-family:sans-serif;font-size:13px;}");
        body.append(".row{display:flex;gap:12px;align-items:center;padding:8px 0;border-top:1px solid #2a241f;}");
        body.append(".row img{width:42px;height:60px;object-fit:cover;background:#2a241f;}");
        body.append(".row .t{flex:1;}");
        body.append(".score{font-family:sans-serif;color:#e5532d;font-weight:700;}");
        body.append(".empty{color:#b7aa96;font-family:sans-serif;}");
        body.append("</style></head><body><main>");
        body.append("<div class=\"kicker\">ANIGA · TASTE REPORT</div>");
        body.append("<h1>").append(esc(report.name())).append("</h1>");
        body.append("<p class=\"meta\">@").append(esc(report.username())).append(" · ");
        body.append(report.ratingCount()).append(" rated · average ");
        body.append(String.format(Locale.US, "%.1f", report.average())).append(" / 10");
        if (!report.counts().isEmpty()) {
            body.append("<br>");
            boolean first = true;
            for (var count : report.counts().entrySet()) {
                if (!first) body.append(" · ");
                first = false;
                body.append(count.getValue()).append(" ").append(esc(count.getKey().toLowerCase(Locale.ROOT)));
            }
        }
        body.append("</p>");

        body.append("<h2>WHAT THEY LIKE</h2>");
        if (report.genres().isEmpty()) {
            body.append("<p class=\"empty\">No ratings yet, so there is no taste to chart.</p>");
        } else {
            double max = report.genres().stream().mapToDouble(g -> Math.abs(g.weight())).max().orElse(1);
            if (max <= 0) max = 1;
            for (GenreBar bar : report.genres()) {
                int width = Math.max(4, (int) Math.round(Math.abs(bar.weight()) / max * 100));
                boolean down = bar.weight() < 0;
                body.append("<div class=\"bar\"><span>").append(esc(bar.name())).append("</span>");
                body.append("<i class=\"").append(down ? "down" : "").append("\" style=\"width:")
                        .append(width).append("%\"></i></div>");
            }
        }
        if (!report.liked().isEmpty()) {
            body.append("<h2>WORDS IN THE STORIES THEY RATE HIGH</h2><div class=\"chips\">");
            for (String term : report.liked()) {
                body.append("<span>").append(esc(term)).append("</span>");
            }
            body.append("</div>");
        }

        String current = "";
        for (RatedTitle title : report.ratings()) {
            if (!title.mediaType().equals(current)) {
                current = title.mediaType();
                body.append("<h2>").append(esc(current)).append("</h2>");
            }
            body.append("<div class=\"row\">");
            if (title.coverUrl() != null && title.coverUrl().startsWith("https://")) {
                body.append("<img alt=\"\" src=\"").append(esc(title.coverUrl())).append("\">");
            }
            body.append("<div class=\"t\"><strong>").append(esc(title.title())).append("</strong>");
            body.append("<div class=\"meta\">");
            if (title.year() != null) body.append(title.year()).append(" · ");
            body.append(esc(title.mediaType().toLowerCase(Locale.ROOT))).append("</div></div>");
            body.append("<div class=\"score\">").append(title.score()).append("</div></div>");
        }
        if (report.ratings().isEmpty()) {
            body.append("<p class=\"empty\">Nothing rated yet.</p>");
        }
        body.append("<p class=\"meta\" style=\"margin-top:28px\">Anyone with this link can read this report. ");
        body.append("It lists ratings and taste, not messages or account secrets.</p>");
        body.append("</main></body></html>");
        return body.toString();
    }

    private static String esc(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
