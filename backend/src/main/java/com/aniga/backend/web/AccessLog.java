package com.aniga.backend.web;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** One line per API call: method, path, status, time. No query string and no token. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccessLog extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessLog.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") && !path.startsWith("/r/")) {
            chain.doFilter(request, response);
            return;
        }
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - started) / 1_000_000L;
            int status = response.getStatus();
            String line = request.getMethod() + " " + path + " -> " + status + " " + ms + "ms";
            if (status >= 500) {
                log.warn(line);
            } else {
                log.info(line);
            }
        }
    }
}
