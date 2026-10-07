package com.aniga.backend.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class WebtoonCanvasAdapterTest {

    @Test
    void listingExtractsSlugAndTitleNumber() {
        var doc = Jsoup.parse("""
                <a href="https://www.webtoons.com/en/canvas/erma/list?title_no=170650">
                  <strong class="subj">Erma</strong><img src="https://example.test/e.jpg">
                </a>
                <a href="https://www.webtoons.com/en/canvas/crow-time/list?title_no=693372" title="Crow Time"></a>
                """);
        var hits = WebtoonCanvasAdapter.parseListing(doc, 8);
        assertEquals(2, hits.size());
        assertEquals("170650:erma", hits.getFirst().id());
        assertEquals("Erma", hits.getFirst().title());
        assertEquals("webtoon-canvas", hits.getFirst().source());
    }

    @Test
    void latestEpisodeUsesTheHighestNumberOnTheListPage() {
        var doc = Jsoup.parse("""
                <a href="/viewer?title_no=1&episode_no=1">first</a>
                <a href="/viewer?title_no=1&episode_no=772">latest</a>
                <a href="/viewer?title_no=1&episode_no=771">prev</a>
                """);
        assertEquals(772, WebtoonCanvasAdapter.latestEpisode(doc));
    }

    @Test
    void canvasRefRoundTrips() {
        var ref = WebtoonCanvasAdapter.CanvasRef.parse("170650:erma");
        assertEquals("https://www.webtoons.com/en/canvas/erma/list?title_no=170650", ref.listUrl());
        assertTrue(ref.viewerUrl(3).contains("episode_no=3"));
    }
}
