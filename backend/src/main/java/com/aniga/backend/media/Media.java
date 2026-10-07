package com.aniga.backend.media;

import java.util.List;

public record Media(
        long id,
        String mediaType,
        String title,
        String synopsis,
        List<String> genres,
        List<String> tags,
        Integer episodes,
        Integer chapters,
        Integer startYear,
        int popularity,
        String coverUrl,
        String bannerUrl,
        String accentColor,
        Integer averageScore,
        String nativeTitle,
        String format,
        String status,
        String season,
        List<String> makers) {

    /** Episodes for anime, chapters for comics; null while a series is still running without a final count. */
    public Integer total() {
        return "ANIME".equals(mediaType) ? episodes : chapters;
    }
}
