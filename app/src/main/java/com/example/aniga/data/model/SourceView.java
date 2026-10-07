package com.example.aniga.data.model;

import java.util.List;

public class SourceView {
    public Info info;
    public long catalogExtensions;
    public List<String> catalogLanguages;

    public static class Info {
        public String code;
        public String displayName;
        public String kind;
        public String homeUrl;
        public String license;
        public List<String> languages;
        public String defaultLanguage;
    }
}
