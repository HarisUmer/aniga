package com.example.aniga.data.model;

import com.google.gson.annotations.SerializedName;

import java.util.Collections;
import java.util.List;

/** GET /api/media/{id}: the list fields plus the anime gallery and the cast. */
public class MediaDetail {
    public Media media;
    public String romajiTitle;
    public Integer volumes;
    public Integer episodeMinutes;
    public Integer anilistId;
    public Integer malId;
    public List<GalleryImage> gallery;
    public List<Character> characters;

    public static class GalleryImage {
        public String url;
        /** banner | trailer | episode */
        public String kind;
        public String caption;
        public String video;
    }

    public static class Character {
        public String name;
        @SerializedName("native")
        public String nativeName;
        public String role;
        public String image;
    }

    public List<GalleryImage> galleryOrEmpty() {
        return gallery == null ? Collections.emptyList() : gallery;
    }

    public List<Character> charactersOrEmpty() {
        return characters == null ? Collections.emptyList() : characters;
    }
}
