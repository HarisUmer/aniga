package com.aniga.backend.media;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * The whole catalog (~900 rows) in memory. Recommenders and search touch every title on every request; re-reading
 * and re-mapping 900 rows (with synopses) each time cost more than the scoring itself. Refreshes when the row count
 * changes, checked at most every 30 s.
 */
@Component
public class CatalogCache {

    public record Snapshot(List<Media> all, Map<Long, Media> byId, long loadedAt) {
    }

    private static final long CHECK_MS = 30_000;

    private final MediaRepository media;
    private volatile Snapshot snapshot;
    private volatile long checkedAt;

    public CatalogCache(MediaRepository media) {
        this.media = media;
    }

    public Snapshot get() {
        Snapshot s = snapshot;
        long now = System.currentTimeMillis();
        if (s != null && now - checkedAt < CHECK_MS) {
            return s;
        }
        synchronized (this) {
            s = snapshot;
            if (s != null && System.currentTimeMillis() - checkedAt < CHECK_MS) {
                return s;
            }
            if (s == null || media.count() != s.all().size()) {
                List<Media> all = media.findAll();
                s = new Snapshot(all, all.stream().collect(Collectors.toMap(Media::id, Function.identity())), now);
                snapshot = s;
            }
            checkedAt = System.currentTimeMillis();
            return s;
        }
    }

    public void invalidate() {
        snapshot = null;
    }
}
