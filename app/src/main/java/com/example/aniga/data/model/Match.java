package com.example.aniga.data.model;

import java.util.List;

/** A taste match: another reader scored by shared favourites and genre overlap. */
public class Match {
    public long userId;
    public String username;
    public String displayName;
    public int shared;
    public List<String> sharedTitles;
    public List<String> theirPicks;
    public double affinity;
    public String blurb;

    public String name() {
        return displayName != null && !displayName.isEmpty() ? displayName : username;
    }
}
