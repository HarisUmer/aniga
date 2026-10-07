package com.aniga.backend.auth;

import java.util.Locale;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.aniga.backend.user.UserController.User;

@Service
public class AccountService {

    private final JdbcClient jdbc;

    public AccountService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Local-part of the email, or a short id when that local-part is too small to be a username. */
    public static String usernameFor(String email, String uid) {
        String raw = email == null ? "" : email;
        int at = raw.indexOf('@');
        if (at > 0) raw = raw.substring(0, at);
        raw = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (raw.length() > 24) raw = raw.substring(0, 24);
        if (raw.length() >= 3) return raw;
        String tail = uid == null ? "" : uid.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (tail.length() > 8) tail = tail.substring(0, 8);
        String combined = "u_" + tail;
        if (combined.length() < 3) return "user";
        return combined.length() > 32 ? combined.substring(0, 32) : combined;
    }

    public Optional<User> byId(long id) {
        return jdbc.sql("SELECT id, username, display_name FROM app_user WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> new User(rs.getLong(1), rs.getString(2), rs.getString(3)))
                .optional();
    }

    public Optional<User> findByUid(String uid) {
        return jdbc.sql("SELECT id, username, display_name FROM app_user WHERE firebase_uid = :f")
                .param("f", uid)
                .query((rs, i) -> new User(rs.getLong(1), rs.getString(2), rs.getString(3)))
                .optional();
    }

    public User link(FirebaseTokenVerifier.Identity identity) {
        Optional<User> existing = findByUid(identity.uid());
        if (existing.isPresent()) {
            if (identity.email() != null) {
                jdbc.sql("UPDATE app_user SET email = :e WHERE firebase_uid = :f")
                        .param("e", identity.email()).param("f", identity.uid()).update();
            }
            return existing.get();
        }
        String base = usernameFor(identity.email(), identity.uid());
        String display = identity.name() != null && !identity.name().isBlank() ? identity.name() : base;
        for (int n = 0; n < 50; n++) {
            String username = n == 0 ? base : trim(base + "_" + (n + 1));
            if (taken(username)) continue;
            try {
                return jdbc.sql("""
                                INSERT INTO app_user (username, display_name, email, firebase_uid)
                                VALUES (:u, :d, :e, :f)
                                RETURNING id, username, display_name""")
                        .param("u", username)
                        .param("d", display)
                        .param("e", identity.email())
                        .param("f", identity.uid())
                        .query((rs, i) -> new User(rs.getLong(1), rs.getString(2), rs.getString(3)))
                        .single();
            } catch (DataIntegrityViolationException e) {
                Optional<User> raced = findByUid(identity.uid());
                if (raced.isPresent()) return raced.get();
            }
        }
        throw new IllegalArgumentException("could not allocate a username");
    }

    private boolean taken(String username) {
        return jdbc.sql("SELECT id FROM app_user WHERE username = :u")
                .param("u", username).query(Long.class).optional().isPresent();
    }

    private static String trim(String username) {
        return username.length() > 32 ? username.substring(0, 32) : username;
    }
}
