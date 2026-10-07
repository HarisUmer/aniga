package com.aniga.backend.source;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Link checker for every catalog extension: one GET of the site's home page, nothing else is fetched.
 * Tells the UI which extensions are alive, which sit behind bot protection, and which domains died
 * (aggregator sites rotate domains constantly — that's why Mihon extensions ship so many updates).
 */
@Service
public class SourceHealthService {

    public record Progress(boolean running, int total, int done, Instant startedAt) {
    }

    private static final int CONCURRENCY = 48;

    private final JdbcClient jdbc;
    private final String userAgent;
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(6))
            .build();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger done = new AtomicInteger();
    private volatile int total;
    private volatile Instant startedAt;

    public SourceHealthService(JdbcClient jdbc, @Value("${aniga.mangadex.user-agent}") String userAgent) {
        this.jdbc = jdbc;
        this.userAgent = userAgent;
    }

    public Progress progress() {
        return new Progress(running.get(), total, done.get(), startedAt);
    }

    /** Returns immediately; the check runs on virtual threads, CONCURRENCY sites at a time. */
    public Progress start(String hint, String lang) {
        if (!running.compareAndSet(false, true)) {
            return progress();
        }
        var sql = new StringBuilder("SELECT id, home_url FROM source_catalog WHERE nsfw = FALSE AND home_url IS NOT NULL");
        if (hint != null && !hint.isBlank()) {
            sql.append(" AND content_hint = '").append(hint.toUpperCase().replaceAll("[^A-Z]", "")).append("'");
        }
        if (lang != null && !lang.isBlank()) {
            sql.append(" AND lang = '").append(lang.replaceAll("[^A-Za-z-]", "")).append("'");
        }
        List<Map.Entry<String, String>> targets = jdbc.sql(sql.toString())
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2))).list();
        total = targets.size();
        done.set(0);
        startedAt = Instant.now();
        Thread.ofVirtual().name("source-health").start(() -> {
            Semaphore gate = new Semaphore(CONCURRENCY);
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (var t : targets) {
                    pool.submit(() -> {
                        gate.acquireUninterruptibly();
                        try {
                            save(t.getKey(), check(t.getValue()));
                        } finally {
                            gate.release();
                            done.incrementAndGet();
                        }
                    });
                }
            } finally {
                running.set(false);
            }
        });
        return progress();
    }

    record Result(String status, Integer httpStatus, long latencyMs, String finalUrl, String error) {
    }

    Result check(String url) {
        long t0 = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html")
                    .GET().build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int code = resp.statusCode();
            String finalUrl = resp.uri().toString();
            boolean cloudflare = resp.headers().firstValue("server").map(s -> s.toLowerCase().contains("cloudflare")).orElse(false);
            String status = classify(code, cloudflare, url, finalUrl);
            return new Result(status, code, ms, finalUrl, null);
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String msg = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return new Result("DOWN", null, ms, null, msg.length() > 200 ? msg.substring(0, 200) : msg);
        }
    }

    static String classify(int code, boolean cloudflare, String url, String finalUrl) {
        if (code == 403 || code == 429 || (code == 503 && cloudflare)) {
            return "BLOCKED";
        }
        if (code >= 200 && code < 400) {
            return host(url).equals(host(finalUrl)) ? "UP" : "MOVED";
        }
        return "DOWN";
    }

    static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? "" : h.replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private void save(String id, Result r) {
        jdbc.sql("""
                        INSERT INTO source_health (source_id, status, http_status, latency_ms, final_url, error, checked_at)
                        VALUES (:id, :s, :h, :l, :f, :e, now())
                        ON CONFLICT (source_id) DO UPDATE SET status = EXCLUDED.status, http_status = EXCLUDED.http_status,
                            latency_ms = EXCLUDED.latency_ms, final_url = EXCLUDED.final_url, error = EXCLUDED.error,
                            checked_at = EXCLUDED.checked_at""")
                .param("id", id).param("s", r.status()).param("h", r.httpStatus()).param("l", (int) r.latencyMs())
                .param("f", r.finalUrl()).param("e", r.error())
                .update();
    }
}
