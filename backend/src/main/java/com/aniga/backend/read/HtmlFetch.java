package com.aniga.backend.read;

import java.io.IOException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One polite HTML client for every scraper. A new parser calls {@link #get(String)} and parses the
 * document; the user agent, timeout, and rate limit live here.
 */
@Component
public class HtmlFetch {

    /** Some public sites answer a browser identity and refuse a short app token. */
    public static final String BROWSER =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private final RateLimiter limiter = new RateLimiter(1.6, 3);
    private final String userAgent;

    public HtmlFetch(@Value("${aniga.mangadex.user-agent}") String userAgent) {
        this.userAgent = userAgent == null || userAgent.isBlank() ? "Aniga/0.1 (practice project)" : userAgent;
    }

    public Document get(String url) {
        return get(url, userAgent, 15_000);
    }

    public Document get(String url, String userAgent, int timeoutMs) {
        limiter.acquire();
        try {
            return Jsoup.connect(url)
                    .userAgent(userAgent)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .timeout(timeoutMs)
                    .get();
        } catch (IOException e) {
            throw new IllegalStateException("page unavailable: " + url, e);
        }
    }
}
