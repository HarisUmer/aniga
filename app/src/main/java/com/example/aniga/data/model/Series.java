package com.example.aniga.data.model;

import java.util.List;

/** A series as one reading source (MangaDex, Pepper&Carrot, xkcd) describes it. */
public class Series {
    public String source;
    public String id;
    public String title;
    public String description;
    public String coverUrl;
    /** MANGA / MANHWA / MANHUA / WEBCOMIC */
    public String contentType;
    public String status;
    public Integer year;
    public List<String> tags;
    public String author;
    /** Present on /api/read/find hits so the app can pick the source with the most chapters. */
    public Integer chapterCount;
    public String language;
}
