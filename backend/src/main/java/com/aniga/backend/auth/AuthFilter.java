package com.aniga.backend.auth;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The gate for the API. The phone sends our own session token (never a Firebase token).
 * Private routes need a valid session; routes under {@code /api/users/{id}} also need the id in the
 * path to be the signed-in user. A few routes need an admin token on top.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthFilter extends OncePerRequestFilter {

    public static final String USER_ID = "aniga.userId";

    private static final Pattern USER_SCOPED = Pattern.compile("^/api/users/(\\d+)(/.*)?$");

    private final SessionService sessions;
    private final String adminToken;

    public AuthFilter(SessionService sessions, @Value("${aniga.admin.token:}") String adminToken) {
        this.sessions = sessions;
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    public static long userId(HttpServletRequest request) {
        Object value = request.getAttribute(USER_ID);
        if (!(value instanceof Long id)) {
            throw new IllegalStateException("sign in required");
        }
        return id;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();

        Long session = resolveSession(request);
        if (session != null) {
            request.setAttribute(USER_ID, session);
        }

        if (isAdminRoute(method, path)) {
            if (adminToken.isEmpty() || !adminToken.equals(bearer(request.getHeader("X-Admin-Token")))) {
                deny(response, 403, "Admin token required");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        if (requiresAuth(method, path)) {
            if (session == null) {
                deny(response, 401, "Sign in required");
                return;
            }
            Matcher scoped = USER_SCOPED.matcher(path);
            if (scoped.matches() && Long.parseLong(scoped.group(1)) != session) {
                deny(response, 403, "This belongs to another account");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private Long resolveSession(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return sessions.userId(header.substring(7).trim());
    }

    static boolean isAdminRoute(String method, String path) {
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        return "/api/users".equals(path)
                || "/api/model/reload".equals(path)
                || "/api/sources/health/run".equals(path);
    }

    static boolean requiresAuth(String method, String path) {
        if (path.startsWith("/api/me")
                || path.startsWith("/api/people")
                || path.startsWith("/api/chat")
                || path.startsWith("/api/reports")) {
            return true;
        }
        if ("/api/users".equals(path)) {
            // GET lists accounts (people search); POST is admin and handled above.
            return "GET".equalsIgnoreCase(method);
        }
        return USER_SCOPED.matcher(path).matches();
    }

    private static String bearer(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static void deny(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
