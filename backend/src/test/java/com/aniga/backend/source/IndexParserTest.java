package com.aniga.backend.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IndexParserTest {

    @Test
    void readsMihonAidokuAndLegacyTachiyomi() {
        var mihon = IndexParser.parse("keiyoushi", """
                {"extensionList":{"extensions":[{"name":"Asura","packageName":"eu.x","versionName":"1.2",
                  "contentWarning":"CONTENT_WARNING_NSFW",
                  "sources":[{"id":"9","name":"Asura Toon","language":"en","homeUrl":"https://asuratoon.example"}]}]}}
                """);
        assertEquals(1, mihon.size());
        assertEquals("keiyoushi:9", mihon.get(0).id());
        assertTrue(mihon.get(0).nsfw());
        assertEquals("https://asuratoon.example", mihon.get(0).homeUrl());
        assertEquals("MANHWA", mihon.get(0).contentHint());

        var aidoku = IndexParser.parse("aidoku-community", """
                {"sources":[{"id":"en.webtoon","name":"Webtoon","version":3,"languages":["en"],
                  "contentRating":0,"baseURL":"https://www.webtoons.com"}]}
                """);
        assertEquals("aidoku-community:en.webtoon", aidoku.get(0).id());
        assertEquals("MANHWA", aidoku.get(0).contentHint());

        var tachiyomi = IndexParser.parse("nyora", """
                [{"name":"Nyora","pkg":"nyora.app","apk":"nyora.apk","version":"1","nsfw":0,
                  "sources":[{"id":"4","name":"Naver","lang":"ko","baseUrl":"https://comic.naver.com"}]}]
                """);
        assertEquals("ko", tachiyomi.get(0).lang());
        assertEquals("MANHWA", tachiyomi.get(0).contentHint());

        var legacy = IndexParser.parse("skitty", """
                [{"id":"en.manga","name":"Manga","lang":"ja","file":"manga.aix","nsfw":0,"version":1}]
                """);
        assertEquals("ja", legacy.get(0).lang());
        assertEquals("MANGA", legacy.get(0).contentHint());
        assertEquals(null, legacy.get(0).homeUrl());
    }

    @Test
    void manhwaAndManhuaHintsFollowLanguageAndName() {
        assertEquals("MANHWA", IndexParser.hint("en", "Asura Toon", "https://asura.example"));
        assertEquals("MANHUA", IndexParser.hint("zh", "Something", "https://example.com"));
        assertEquals("MANGA", IndexParser.hint("ja", "Jump", "https://example.com"));
        assertEquals("MIXED", IndexParser.hint("en", "Comic", "https://example.com"));
    }
}
