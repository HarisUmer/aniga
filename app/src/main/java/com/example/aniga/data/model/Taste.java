package com.example.aniga.data.model;

import java.util.List;
import java.util.Map;

public class Taste {
    public int ratingCount;
    public double pivot;
    public Map<String, Double> genreWeights;
    /** Each element is a single-entry map {term: weight} (Jackson's Map.Entry encoding). */
    public List<Map<String, Double>> likedTerms;
    public List<Map<String, Double>> dislikedTerms;
}
