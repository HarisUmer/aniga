package com.aniga.backend.search;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.aniga.backend.media.MediaRepository;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private static final long MAX_BYTES = 8L * 1024 * 1024;

    private final MediaRepository media;
    private final JdbcClient jdbc;
    private final ImageSearchService images;
    private volatile DescriptionIndex index;
    private volatile long indexedCount = -1;

    public SearchController(MediaRepository media, JdbcClient jdbc, ImageSearchService images) {
        this.media = media;
        this.jdbc = jdbc;
        this.images = images;
    }

    /** Rebuilt only when the catalog size changes; ~900 docs index in well under a second. */
    private DescriptionIndex index() {
        long count = media.count();
        if (index == null || count != indexedCount) {
            Map<Long, String> names = new HashMap<>();
            jdbc.sql("""
                            SELECT id, string_agg(c->>'name', ' ') FROM media, jsonb_array_elements(characters) c
                            GROUP BY id""")
                    .query(rs -> {
                        names.put(rs.getLong(1), rs.getString(2));
                    });
            index = new DescriptionIndex(media.findAll(), names);
            indexedCount = count;
        }
        return index;
    }

    /** Natural-language search over what a story is about, e.g. "girl reborn as the villainess of a novel". */
    @GetMapping
    public List<DescriptionIndex.Hit> describe(@RequestParam String q, @RequestParam(required = false) String type,
                                               @RequestParam(defaultValue = "20") int limit) {
        if (q.isBlank()) {
            throw new IllegalArgumentException("q must not be blank");
        }
        return index().search(q, type, Math.clamp(limit, 1, 60));
    }

    /** Reverse image search. scenes=true also asks trace.moe (anonymous quota is small, so clients opt in). */
    @PostMapping(value = "/image", consumes = "multipart/form-data")
    public ImageSearchService.Result image(@RequestPart("file") MultipartFile file,
                                           @RequestParam(defaultValue = "true") boolean scenes) throws IOException {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("image must be between 1 byte and 8 MB");
        }
        return images.search(file.getBytes(), file.getContentType(), scenes);
    }

    @GetMapping("/image")
    public ImageSearchService.Result imageByUrl(@RequestParam String url,
                                                @RequestParam(defaultValue = "true") boolean scenes)
            throws IOException, InterruptedException {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("url must be http(s)");
        }
        for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
            if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()) {
                throw new IllegalArgumentException("private addresses are not allowed");
            }
        }
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(8)).build();
        HttpResponse<byte[]> resp = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200 || resp.body().length > MAX_BYTES) {
            throw new IllegalArgumentException("could not download image (HTTP " + resp.statusCode() + ")");
        }
        return images.search(resp.body(), resp.headers().firstValue("content-type").orElse("image/jpeg"), scenes);
    }
}
