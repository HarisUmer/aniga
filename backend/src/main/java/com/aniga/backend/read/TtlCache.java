package com.aniga.backend.read;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Per-key cache for upstream APIs that ask clients to cache (MangaDex, xkcd).
 * <ul>
 *   <li><b>single-flight</b>: concurrent misses for the same key share one upstream call</li>
 *   <li><b>stale-while-revalidate</b>: for one extra TTL an expired value is served instantly while a background
 *       refresh runs, so popular lists never make a user wait on MangaDex</li>
 *   <li>failures are not cached; eviction drops expired entries first, then the oldest ones</li>
 * </ul>
 */
public final class TtlCache<V> {

    private record Entry<V>(V value, long freshUntil, long staleUntil) {
    }

    private final Map<String, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxEntries;

    public TtlCache(Duration ttl, int maxEntries) {
        this.ttlMillis = ttl.toMillis();
        this.maxEntries = maxEntries;
    }

    public V get(String key, Supplier<V> loader) {
        long now = System.currentTimeMillis();
        Entry<V> hit = entries.get(key);
        if (hit != null && hit.freshUntil > now) {
            return hit.value;
        }
        if (hit != null && hit.staleUntil > now) {
            if (!inFlight.containsKey(key)) {
                Thread.ofVirtual().name("cache-refresh").start(() -> {
                    try {
                        load(key, loader);
                    } catch (RuntimeException ignored) {
                        // keep serving the stale value; the next request retries
                    }
                });
            }
            return hit.value;
        }
        return load(key, loader);
    }

    /** True if a fresh value is cached (used to skip prefetching what is already warm). */
    public boolean isFresh(String key) {
        Entry<V> hit = entries.get(key);
        return hit != null && hit.freshUntil > System.currentTimeMillis();
    }

    private V load(String key, Supplier<V> loader) {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            try {
                return running.join();
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException re ? re : e;
            }
        }
        try {
            V value = loader.get();
            put(key, value);
            mine.complete(value);
            return value;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    /** Fresh-or-stale hit without loading; null on miss. */
    public V getIfPresent(String key) {
        Entry<V> hit = entries.get(key);
        return hit != null && hit.staleUntil > System.currentTimeMillis() ? hit.value : null;
    }

    public void put(String key, V value) {
        long now = System.currentTimeMillis();
        if (entries.size() >= maxEntries) {
            entries.entrySet().removeIf(e -> e.getValue().staleUntil <= now);
            if (entries.size() >= maxEntries) {
                entries.entrySet().stream()
                        .sorted(Comparator.comparingLong(e -> e.getValue().freshUntil))
                        .limit(Math.max(1, maxEntries / 10))
                        .map(Map.Entry::getKey).toList()
                        .forEach(entries::remove);
            }
        }
        entries.put(key, new Entry<>(value, now + ttlMillis, now + 2 * ttlMillis));
    }
}
