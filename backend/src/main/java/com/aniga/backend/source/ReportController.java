package com.aniga.backend.source;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aniga.backend.auth.AuthFilter;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    public record Report(String source, String target, String reason) {
    }

    private final ParseLog parseLog;

    public ReportController(ParseLog parseLog) {
        this.parseLog = parseLog;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> create(HttpServletRequest request, @RequestBody Report body) {
        long userId = AuthFilter.userId(request);
        long reportId = parseLog.report(userId, body.source(), body.target(), body.reason());
        return Map.of("id", reportId);
    }
}
