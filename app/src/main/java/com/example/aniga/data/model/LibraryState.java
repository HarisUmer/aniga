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

        public Update(String status, Integer progress, Integer score) {
            this.status = status;
            this.progress = progress;
            this.score = score;
        }
    }

    public static class Entry {
        public Media media;
        public String status;
        public int progress;
        public Integer score;
        public String updatedAt;
    }
}
