package com.example.aniga.data.model;

/** One title on the user's shelf. status NONE = not on the shelf; score 0 = unrated. */
public class LibraryState {
    public long mediaId;
    public String status;
    public int progress;
    public int score;

    public static class Update {
        public final String status;
        public final Integer progress;
        public final Integer score;
        public String sourceCode;
        public String seriesId;
        public String seriesTitle;
        public String sourceLang;
        public Double chaptersKnown;

        public Update(String status, Integer progress, Integer score) {
            this.status = status;
            this.progress = progress;
            this.score = score;
        }

        /** Pins the reading source so Home continue and Library updates can follow the right site. */
        public Update source(String code, String seriesId, String title, String lang, Double chaptersKnown) {
            this.sourceCode = code;
            this.seriesId = seriesId;
            this.seriesTitle = title;
            this.sourceLang = lang;
            this.chaptersKnown = chaptersKnown;
            return this;
        }
    }

    public static class Entry {
        public Media media;
        public String status;
        public int progress;
        public Integer score;
        public String updatedAt;
        public String sourceCode;
        public String seriesId;
        public String seriesTitle;
        public String sourceLang;
        public Double chaptersKnown;
    }
}
