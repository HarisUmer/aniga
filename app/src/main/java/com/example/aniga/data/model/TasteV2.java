package com.example.aniga.data.model;

import java.util.List;
import java.util.Map;

/** GET /taste/v2: what the recommender believes per feature, and what changed it lately. */
public class TasteV2 {
    public int signals;
    public int rewards;
    public int exploreSlots;
    public List<Feature> features;
    public List<Change> changes;
    public List<FeedbackItem> feedback;

    /**
     * feature is "g:Horror", "t:gore" or "type:MANHWA". effective = history + online + pref/2 is what ranks titles;
     * pref is the hand-set boost (1) / mute (-1).
     */
    public static class Feature {
        public String feature;
        public String label;
        public String kind;
        public double history;
        public double online;
        public int pref;
        public double effective;
        public double evidence;
    }

    public static class Change {
        public Long mediaId;
        public String title;
        public String cause;
        public double reward;
        public Double expected;
        public Map<String, Double> deltas;
        public String at;
    }

    public static class FeedbackItem {
        public Media media;
        public String kind;
    }

    public static class Feedback {
        public final long mediaId;
        public final String kind;

        public Feedback(long mediaId, String kind) {
            this.mediaId = mediaId;
            this.kind = kind;
        }
    }

    public static class Pref {
        public final String feature;
        public final int weight;

        public Pref(String feature, int weight) {
            this.feature = feature;
            this.weight = weight;
        }
    }
}
