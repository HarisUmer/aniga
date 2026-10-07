package com.example.aniga.data.model;

import java.util.List;

public final class SearchResults {

    private SearchResults() {
    }

    /** Description search hit: matched = stemmed query words found in the title's text. */
    public static class Hit {
        public Media media;
        public double score;
        public List<String> matched;
    }

    public static class Image {
        public List<Scene> scenes;
        public List<Cover> covers;
        public String sceneError;
        public int coversIndexed;
    }

    /** trace.moe: the anime, episode and timestamp a screenshot came from. */
    public static class Scene {
        public Integer anilistId;
        public String title;
        public String nativeTitle;
        public Double episode;
        public double from;
        public double similarity;
        public String previewImage;
        public Media media;
    }

    public static class Cover {
        public Media media;
        public int distance;
        public double similarity;
    }
}
