package com.aniga.backend.web;

import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final JdbcClient jdbc;

    public HealthController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "database", jdbc.sql("SELECT version()").query(String.class).single(),
                "media", jdbc.sql("SELECT count(*) FROM media").query(Long.class).single(),
                "users", jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single(),
                "catalogSources", jdbc.sql("SELECT count(*) FROM source_catalog").query(Long.class).single());
    }
}
