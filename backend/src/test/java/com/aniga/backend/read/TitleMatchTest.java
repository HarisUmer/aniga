package com.aniga.backend.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TitleMatchTest {

    @Test
    void exactTitleBeatsBookVersionAndSideStory() {
        String q = "The Beginning After the End";
        double comic = TitleMatch.score(q, "The Beginning After the End");
        double book = TitleMatch.score(q, "The Beginning After the End (Book Version)");
        double side = TitleMatch.score(q, "The Beginning After the End: Side Story - Jasmine");
        double other = TitleMatch.score(q, "After the Beginning");
        assertEquals(1.0, comic);
        assertEquals(0.0, book);
        assertTrue(comic > side);
        assertTrue(comic > other);
        assertTrue(side < 0.7);
        assertTrue(TitleMatch.score(q, "Red String: The Beginning After The End") < 0.55);
    }

    @Test
    void aliasesRescueRomanisedManhwaTitles() {
        double solo = TitleMatch.bestScore("Solo Leveling", List.of("나 혼자만 레벨업", "Na Honjaman Level Up"),
                "Solo Leveling");
        assertEquals(1.0, solo);
        assertEquals(0.0, TitleMatch.score("Omniscient Reader", "Omniscient Reader's Viewpoint (Book Version)"));
    }

    @Test
    void splitAliasesKeepsOrderAndDropsBlanks() {
        assertEquals(List.of("TBATE", "나 혼자만 레벨업"), ChapterFinder.splitAliases("TBATE | | 나 혼자만 레벨업"));
    }
}
