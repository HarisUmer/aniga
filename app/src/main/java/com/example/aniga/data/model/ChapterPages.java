package com.example.aniga.data.model;

import java.util.List;

public class ChapterPages {
    public String chapterId;
    public List<String> urls;
    public List<String> fallbackUrls;
    /** Per-page text, e.g. xkcd alt text; empty for most sources. */
    public List<String> captions;
    public String credit;
}
