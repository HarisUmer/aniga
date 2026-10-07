package com.example.aniga.data.model;

/** A shelved comic whose reading source now lists chapters past what you've seen. */
public class LibraryUpdate {
    public Media media;
    public String sourceCode;
    public String seriesId;
    public String seriesTitle;
    public String sourceLang;
    public double latestChapter;
    public Double chaptersKnown;
    public int newCount;
}
