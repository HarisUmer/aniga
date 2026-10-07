package com.example.aniga.data.model;

import java.util.Collections;
import java.util.List;

public class Media {
    public long id;
    public String mediaType;
    public String title;
    public String synopsis;
    public List<String> genres;
    public List<String> tags;
    public Integer episodes;
    public Integer chapters;
    public Integer startYear;
    public int popularity;
    public String coverUrl;
    public String bannerUrl;
    public String accentColor;
    public Integer averageScore;
    public String nativeTitle;
    public String format;
    public String status;
    public String season;
    public List<String> makers;

    public List<String> genresOrEmpty() {
        return genres == null ? Collections.emptyList() : genres;
    }

    public boolean isReadable() {
        return !"ANIME".equals(mediaType);
    }

    public boolean isAnime() {
        return "ANIME".equals(mediaType);
    }

    /** Episodes for anime, chapters for comics; null while a running series has no final count. */
    public Integer total() {
        return isAnime() ? episodes : chapters;
    }
}
