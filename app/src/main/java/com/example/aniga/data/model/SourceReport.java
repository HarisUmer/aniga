package com.example.aniga.data.model;

public class SourceReport {
    public String source;
    public String target;
    public String reason;

    public SourceReport(String source, String target, String reason) {
        this.source = source;
        this.target = target;
        this.reason = reason;
    }

    public static class Posted {
        public long id;
    }
}
