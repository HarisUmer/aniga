package com.example.aniga.data.model;

import java.util.List;
import java.util.Map;

public class Recommendation {
    public Media media;
    public double score;
    public double genreScore;
    public double textScore;
    /** z-scored contribution of each model part (ease, tower, content, pop). */
    public Map<String, Double> parts;
    public List<String> reasons;
    public Long becauseId;
    public String becauseTitle;
    /** EXPLOIT (best guess) or EXPLORE (testing a taste the model knows little about). */
    public String slot;

    public boolean isExplore() {
        return "EXPLORE".equals(slot);
    }
}
