package com.aniga.backend.read;

/**
 * Token bucket shared by every call to one upstream. Parallel fetches (virtual threads) stay under the
 * provider's limit instead of tripping 429s: MangaDex allows ~5 requests/s per client IP.
 */
public final class RateLimiter {

    private final double perSecond;
    private final double burst;
    private double tokens;
    private long last = System.nanoTime();

    public RateLimiter(double perSecond, double burst) {
        this.perSecond = perSecond;
        this.burst = burst;
        this.tokens = burst;
    }

    public void acquire() {
        long waitNanos;
        synchronized (this) {
            long now = System.nanoTime();
            tokens = Math.min(burst, tokens + (now - last) / 1e9 * perSecond);
            last = now;
            tokens -= 1;
            waitNanos = tokens >= 0 ? 0 : (long) (-tokens / perSecond * 1e9);
        }
        if (waitNanos > 0) {
            try {
                Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
