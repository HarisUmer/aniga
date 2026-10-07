package com.aniga.backend.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class TtlCacheTest {

    @Test
    void concurrentMissesShareOneUpstreamCall() throws Exception {
        TtlCache<String> cache = new TtlCache<>(Duration.ofMinutes(1), 10);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                results.add(pool.submit(() -> cache.get("popular", () -> {
                    calls.incrementAndGet();
                    try {
                        gate.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "list";
                })));
            }
            Thread.sleep(150);
            gate.countDown();
            for (Future<String> f : results) {
                assertEquals("list", f.get(3, TimeUnit.SECONDS));
            }
        }
        assertEquals(1, calls.get());
        assertTrue(cache.isFresh("popular"));
    }

    @Test
    void failuresAreNotCached() {
        TtlCache<String> cache = new TtlCache<>(Duration.ofMinutes(1), 10);
        assertThrows(IllegalStateException.class, () -> cache.get("k", () -> {
            throw new IllegalStateException("429");
        }));
        assertNull(cache.getIfPresent("k"));
        assertEquals("ok", cache.get("k", () -> "ok"));
    }

    @Test
    void staleValueIsServedWhileRefreshing() throws Exception {
        TtlCache<String> cache = new TtlCache<>(Duration.ofMillis(80), 10);
        cache.put("k", "old");
        Thread.sleep(120);
        CountDownLatch refreshed = new CountDownLatch(1);
        assertEquals("old", cache.get("k", () -> {
            refreshed.countDown();
            return "new";
        }));
        assertTrue(refreshed.await(2, TimeUnit.SECONDS));
        Thread.sleep(30);
        assertEquals("new", cache.get("k", () -> "unused"));
    }

    @Test
    void evictsWhenFull() {
        TtlCache<Integer> cache = new TtlCache<>(Duration.ofMinutes(1), 10);
        for (int i = 0; i < 25; i++) {
            cache.put("k" + i, i);
        }
        assertEquals(24, cache.getIfPresent("k24"));
        assertNull(cache.getIfPresent("k0"));
    }
}
