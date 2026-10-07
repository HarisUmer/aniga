package com.aniga.backend.source;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sources")
public class SourceController {

    /** adapterCode != null means the extension is readable in-app; health is null until a check has run. */
    public record CatalogSource(String id, String name, String lang, String homeUrl, String contentHint,
                                String extensionName, String indexRepo, String adapterCode, String adapterLang,
                                String health, Integer httpStatus, Integer latencyMs) {
    }

    public record Page<T>(long total, int page, int size, List<T> items) {
    }

    public record Adapter(String code, String displayName, String kind, String baseUrl, boolean enabled,
                          String notes) {
    }

    private final JdbcClient jdbc;
    private final SourceHealthService health;

    public SourceController(JdbcClient jdbc, SourceHealthService health) {
        this.jdbc = jdbc;
        this.health = health;
    }

    @GetMapping
    public Page<CatalogSource> catalog(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) String lang,
                                       @RequestParam(required = false) String hint,
                                       @RequestParam(required = false) Boolean readable,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        size = Math.clamp(size, 1, 200);
        page = Math.max(page, 0);

        var where = new StringBuilder(" WHERE c.nsfw = FALSE");
        Map<String, Object> params = new HashMap<>();
        if (q != null && !q.isBlank()) {
            where.append(" AND (c.name ILIKE :q OR c.home_url ILIKE :q)");
            params.put("q", "%" + q.trim() + "%");
        }
        if (lang != null && !lang.isBlank()) {
            where.append(" AND c.lang = :lang");
            params.put("lang", lang);
        }
        if (hint != null && !hint.isBlank()) {
            where.append(" AND c.content_hint = :hint");
            params.put("hint", hint.toUpperCase());
        }
        if (readable != null) {
            where.append(readable ? " AND c.adapter_code IS NOT NULL" : " AND c.adapter_code IS NULL");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND h.status = :status");
            params.put("status", status.toUpperCase());
        }
        String from = " FROM source_catalog c LEFT JOIN source_health h ON h.source_id = c.id";

        long total = jdbc.sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();

        params.put("limit", size);
        params.put("offset", page * size);
        List<CatalogSource> items = jdbc.sql("""
                        SELECT c.id, c.name, c.lang, c.home_url, c.content_hint, c.extension_name, c.index_repo,
                               c.adapter_code, c.adapter_lang, h.status, h.http_status, h.latency_ms""" + from + where
                        + " ORDER BY (c.adapter_code IS NULL), c.name LIMIT :limit OFFSET :offset")
                .params(params)
                .query((rs, i) -> new CatalogSource(rs.getString("id"), rs.getString("name"),
                        rs.getString("lang"), rs.getString("home_url"), rs.getString("content_hint"),
                        rs.getString("extension_name"), rs.getString("index_repo"), rs.getString("adapter_code"),
                        rs.getString("adapter_lang"), rs.getString("status"),
                        (Integer) rs.getObject("http_status"), (Integer) rs.getObject("latency_ms")))
                .list();
        return new Page<>(total, page, size, items);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        var byHint = jdbc.sql("""
                        SELECT content_hint, count(*) AS n FROM source_catalog
                        WHERE nsfw = FALSE GROUP BY content_hint ORDER BY n DESC""")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list();
        var byLang = jdbc.sql("""
                        SELECT lang, count(*) AS n FROM source_catalog
                        WHERE nsfw = FALSE GROUP BY lang ORDER BY n DESC LIMIT 12""")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list();
        long readable = jdbc.sql("SELECT count(*) FROM source_catalog WHERE nsfw = FALSE AND adapter_code IS NOT NULL")
                .query(Long.class).single();
        var byHealth = jdbc.sql("""
                        SELECT coalesce(h.status, 'UNCHECKED'), count(*) FROM source_catalog c
                        LEFT JOIN source_health h ON h.source_id = c.id
                        WHERE c.nsfw = FALSE GROUP BY 1 ORDER BY 2 DESC""")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list();
        Map<String, Long> parseOutcomes = new LinkedHashMap<>();
        parseOutcomes.put("OK", 0L);
        parseOutcomes.put("FAIL", 0L);
        jdbc.sql("SELECT outcome, count(*) FROM source_parse_event GROUP BY outcome")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list()
                .forEach(e -> parseOutcomes.put(e.getKey(), e.getValue()));
        long reports = jdbc.sql("SELECT count(*) FROM source_report").query(Long.class).single();
        return Map.of("byContentHint", byHint, "topLanguages", byLang, "readableInApp", readable,
                "byHealth", byHealth, "healthCheck", health.progress(),
                "parseOutcomes", parseOutcomes, "reports", reports);
    }

    @GetMapping("/adapters")
    public List<Adapter> adapters() {
        return jdbc.sql("SELECT code, display_name, kind, base_url, enabled, notes FROM source_adapter ORDER BY code")
                .query((rs, i) -> new Adapter(rs.getString("code"), rs.getString("display_name"),
                        rs.getString("kind"), rs.getString("base_url"), rs.getBoolean("enabled"),
                        rs.getString("notes")))
                .list();
    }

    @PostMapping("/health/run")
    public SourceHealthService.Progress runHealth(@RequestParam(required = false) String hint,
                                                  @RequestParam(required = false) String lang) {
        return health.start(hint, lang);
    }

    @GetMapping("/health")
    public SourceHealthService.Progress healthProgress() {
        return health.progress();
    }
}
