package com.aniga.backend.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Counts whether a source actually returned page links, and stores reader reports. */
@Service
public class ParseLog {

    private static final Logger log = LoggerFactory.getLogger(ParseLog.class);

    private final JdbcClient jdbc;

    public ParseLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void ok(String source, String target) {
        write(source, target, "OK", null);
    }

    public void fail(String source, String target, String error) {
        write(source, target, "FAIL", error);
    }

    public long report(long userId, String source, String target, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source is required");
        }
        return jdbc.sql("""
                        INSERT INTO source_report (user_id, source_code, target, reason)
                        VALUES (:u, :s, :t, :r) RETURNING id""")
                .param("u", userId)
                .param("s", clip(source, 80))
                .param("t", clip(target, 500))
                .param("r", clip(reason.trim(), 500))
                .query(Long.class).single();
    }

    private void write(String source, String target, String outcome, String error) {
        try {
            jdbc.sql("""
                            INSERT INTO source_parse_event (source_code, target, outcome, error)
                            VALUES (:s, :t, :o, :e)""")
                    .param("s", clip(source, 80))
                    .param("t", clip(target, 500))
                    .param("o", outcome)
                    .param("e", clip(error, 400))
                    .update();
        } catch (RuntimeException e) {
            log.warn("parse event was not stored: {}", e.getMessage());
        }
    }

    private static String clip(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
