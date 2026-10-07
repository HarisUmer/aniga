package com.aniga.backend.search;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.aniga.backend.mangadex.Json;
import com.aniga.backend.media.Media;
import com.aniga.backend.media.MediaRepository;
import com.aniga.backend.read.TtlCache;

/**
 * Two ways to answer "what is this picture?":
 *  1. trace.moe — finds the exact anime, episode and timestamp for a screenshot (frame index of ~all aired anime).
 *  2. cover match — dHash of every catalog cover vs the upload; catches photos/screenshots of covers and key art.
 */
@Service
public class ImageSearchService {

    public record SceneHit(Integer anilistId, String title, String nativeTitle, Double episode, double from, double to,
                           double similarity, String previewImage, String previewVideo, Media media) {
    }

    public record CoverHit(Media media, int distance, double similarity) {
    }

    public record Result(List<SceneHit> scenes, List<CoverHit> covers, String sceneError, int coversIndexed) {
    }

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };
    private static final int MAX_COVER_DISTANCE = 18;
    private static final int VARIANT_PENALTY = 2;
    private static final Executor VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();

    private volatile HashIndex index;
    // Same picture uploaded twice (retry, or the app re-sending on rotate) answers instantly and saves trace.moe quota.
    private final TtlCache<Result> results = new TtlCache<>(Duration.ofMinutes(30), 200);

    private final JdbcClient jdbc;
    private final MediaRepository media;
    private final RestClient traceMoe;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8)).build();
    private final String userAgent;
    private final AtomicBoolean indexing = new AtomicBoolean();
    private final AtomicInteger indexed = new AtomicInteger();

    public ImageSearchService(JdbcClient jdbc, MediaRepository media,
                              @Value("${aniga.mangadex.user-agent}") String userAgent) {
        this.jdbc = jdbc;
        this.media = media;
        this.userAgent = userAgent;
        this.traceMoe = RestClient.builder().baseUrl("https://api.trace.moe").defaultHeader("User-Agent", userAgent).build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void indexCoversInBackground() {
        if (!indexing.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("cover-hash").start(() -> {
            try {
                var todo = jdbc.sql("""
                                SELECT id, cover_url FROM media
                                WHERE (cover_hash IS NULL OR cover_hash_zoom IS NULL) AND cover_url IS NOT NULL""")
                        .query((rs, i) -> Map.entry(rs.getLong(1), rs.getString(2))).list();
                Semaphore gate = new Semaphore(8);
                try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (var t : todo) {
                        pool.submit(() -> {
                            gate.acquireUninterruptibly();
                            try {
                                BufferedImage img = download(t.getValue());
                                if (img != null) {
                                    jdbc.sql("UPDATE media SET cover_hash = :h, cover_hash_zoom = :z WHERE id = :id")
                                            .param("h", ImageHash.dHash(img)).param("z", ImageHash.zoomHash(img))
                                            .param("id", t.getKey()).update();
                                }
                            } catch (Exception ignored) {
                                // A dead cover URL just means that title can't be found by picture.
                            } finally {
                                gate.release();
                            }
                        });
                    }
                }
            } finally {
                index = null;
                indexing.set(false);
                indexed.set(coversIndexed());
            }
        });
    }

    private BufferedImage download(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", userAgent).GET().build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        return resp.statusCode() == 200 ? ImageIO.read(new ByteArrayInputStream(resp.body())) : null;
    }

    int coversIndexed() {
        return hashIndex().ids.length;
    }

    /**
     * All cover hashes as primitive arrays (~24 bytes per title): a full scan is a few ms, no DB round trip.
     * zoom[i] is the hash of the cover's centre 80% (0 until indexed).
     */
    private record HashIndex(long[] ids, long[] hashes, long[] zoom, long loadedAt) {
    }

    private HashIndex hashIndex() {
        HashIndex idx = index;
        if (idx == null || (System.currentTimeMillis() - idx.loadedAt > 10 * 60_000 && !indexing.get())) {
            synchronized (this) {
                idx = index;
                if (idx == null || System.currentTimeMillis() - idx.loadedAt > 10 * 60_000) {
                    var rows = jdbc.sql("""
                                    SELECT id, cover_hash, coalesce(cover_hash_zoom, 0)
                                    FROM media WHERE cover_hash IS NOT NULL""")
                            .query((rs, i) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)}).list();
                    long[] ids = new long[rows.size()];
                    long[] hashes = new long[rows.size()];
                    long[] zoom = new long[rows.size()];
                    for (int i = 0; i < rows.size(); i++) {
                        ids[i] = rows.get(i)[0];
                        hashes[i] = rows.get(i)[1];
                        zoom[i] = rows.get(i)[2];
                    }
                    idx = index = new HashIndex(ids, hashes, zoom, System.currentTimeMillis());
                }
            }
        }
        return idx;
    }

    public Result search(byte[] image, String contentType, boolean scenes) throws IOException {
        String key = digest(image) + ":" + scenes;
        Result cached = results.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(image));
        if (img == null) {
            throw new IllegalArgumentException("unsupported image format; send JPEG, PNG, GIF or BMP");
        }
        // trace.moe is the slow half (network + their index); start it first and match covers meanwhile.
        CompletableFuture<List<SceneHit>> sceneJob = scenes
                ? CompletableFuture.supplyAsync(() -> traceMoe(shrinkForUpload(img, image, contentType)), VIRTUAL)
                : CompletableFuture.completedFuture(List.of());
        List<CoverHit> covers = coverMatches(ImageHash.variants(img));
        List<SceneHit> sceneHits = List.of();
        String sceneError = null;
        try {
            sceneHits = sceneJob.get(25, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            sceneError = "trace.moe unavailable: " + e.getCause().getMessage();
        } catch (TimeoutException e) {
            sceneJob.cancel(true);
            sceneError = "trace.moe timed out";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sceneError = "interrupted";
        }
        Result result = new Result(sceneHits, covers, sceneError, coversIndexed());
        if (sceneError == null) {
            results.put(key, result);
        }
        return result;
    }

    /** Variant 0 is the upload as-is; other variants pay a small penalty so they only win on a clearly better fit. */
    private List<CoverHit> coverMatches(long[] variants) {
        HashIndex idx = hashIndex();
        int n = idx.ids.length;
        int[] best = new int[n];
        for (int i = 0; i < n; i++) {
            int d = ImageHash.distance(variants[0], idx.hashes[i]);
            for (int v = 1; v < variants.length; v++) {
                d = Math.min(d, ImageHash.distance(variants[v], idx.hashes[i]) + VARIANT_PENALTY);
            }
            if (idx.zoom[i] != 0) {
                d = Math.min(d, ImageHash.distance(variants[0], idx.zoom[i]) + VARIANT_PENALTY);
            }
            best[i] = d;
        }
        List<long[]> near = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (best[i] <= MAX_COVER_DISTANCE) {
                near.add(new long[] {idx.ids[i], best[i]});
            }
        }
        near.sort(Comparator.comparingLong(a -> a[1]));
        List<long[]> top = near.stream().limit(6).toList();
        var byId = media.byIds(top.stream().map(a -> a[0]).toList()).stream()
                .collect(java.util.stream.Collectors.toMap(Media::id, m -> m));
        List<CoverHit> hits = new ArrayList<>();
        for (long[] e : top) {
            Media m = byId.get(e[0]);
            if (m != null) {
                hits.add(new CoverHit(m, (int) e[1], Math.round((1 - e[1] / 64.0) * 1000) / 1000.0));
            }
        }
        return hits;
    }

    private record Upload(byte[] bytes, MediaType type) {
    }

    /**
     * trace.moe matches on a small frame anyway, so a 4 MB phone screenshot is re-encoded to a ≤960 px JPEG
     * (~100 KB): the upload, usually the slowest part on mobile networks, shrinks ~20-40x.
     */
    private static Upload shrinkForUpload(BufferedImage img, byte[] original, String contentType) {
        MediaType type = contentType == null ? MediaType.IMAGE_JPEG : MediaType.parseMediaType(contentType);
        int longest = Math.max(img.getWidth(), img.getHeight());
        if (original.length < 300_000 || longest <= 960) {
            return new Upload(original, type);
        }
        double s = 960.0 / longest;
        BufferedImage small = new BufferedImage((int) (img.getWidth() * s), (int) (img.getHeight() * s),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, small.getWidth(), small.getHeight(), null);
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(small, "jpg", out);
            return new Upload(out.toByteArray(), MediaType.IMAGE_JPEG);
        } catch (IOException e) {
            return new Upload(original, type);
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Media byTitle(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return media.search(name, null, null, null, 1, 0).stream().findFirst().orElse(null);
    }

    private List<SceneHit> traceMoe(Upload upload) {
        Map<String, Object> body = traceMoe.post()
                .uri("/search?anilistInfo&cutBorders")
                .contentType(upload.type())
                .body(upload.bytes())
                .retrieve()
                .body(JSON);
        String error = Json.str(body.get("error"));
        if (error != null && !error.isBlank()) {
            throw new IllegalStateException(error);
        }
        List<SceneHit> out = new ArrayList<>();
        for (Object o : Json.list(body.get("result"))) {
            Map<String, Object> r = Json.map(o);
            Map<String, Object> al = Json.map(r.get("anilist"));
            if (Boolean.TRUE.equals(al.get("isAdult"))) {
                continue;
            }
            Integer id = Json.integer(al.get("id"));
            Map<String, Object> title = Json.map(al.get("title"));
            String name = Json.str(title.get("english")) != null ? Json.str(title.get("english")) : Json.str(title.get("romaji"));
            Media local = id == null ? null : media.byAnilistId(id).orElse(null);
            // trace.moe may name a title we hold under a different AniList id; fall back to a title search
            // so the hit still opens a real detail page instead of a dead card.
            if (local == null) {
                local = byTitle(name);
                if (local == null) {
                    local = byTitle(Json.str(title.get("romaji")));
                }
            }
            Object ep = r.get("episode");
            out.add(new SceneHit(id, name, Json.str(title.get("native")),
                    ep instanceof Number n ? n.doubleValue() : null,
                    ((Number) r.getOrDefault("from", 0)).doubleValue(), ((Number) r.getOrDefault("to", 0)).doubleValue(),
                    Math.round(((Number) r.getOrDefault("similarity", 0)).doubleValue() * 1000) / 1000.0,
                    Json.str(r.get("image")), Json.str(r.get("video")), local));
        }
        out.sort(Comparator.comparingDouble(SceneHit::similarity).reversed());
        return out.stream().limit(5).toList();
    }
}
