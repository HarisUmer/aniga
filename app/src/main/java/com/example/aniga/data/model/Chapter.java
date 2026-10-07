package com.example.aniga.data.model;

public class Chapter {
    public String id;
    public String number;
    public String title;
    public String language;
    public int pages;
    public String group;
    public String publishedAt;
    /** Official publisher link; such chapters open in the browser instead of the reader. */
    public String externalUrl;

    public String label() {
        return number == null || number.isEmpty() ? "Oneshot" : number;
    }

    public boolean isExternal() {
        return externalUrl != null && pages == 0;
    }
}
