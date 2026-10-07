package com.example.aniga.data.model;

import java.util.List;

/** The reader personality predicted from ratings. */
public class Personality {
    public String type;
    public String summary;
    public List<Trait> traits;
    public List<String> topGenres;

    public static class Trait {
        public String name;
        public int score;
    }
}
