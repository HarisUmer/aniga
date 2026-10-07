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
        User adopted = adoptEmail(identity);
        if (adopted != null) {
            return adopted;
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
                User adoptedLater = adoptEmail(identity);
                if (adoptedLater != null) return adoptedLater;
            }
        }
        throw new IllegalArgumentException("could not allocate a username");
    }

    /** Attach a Firebase uid to the row that already has this email, and keep its password hash. */
    private User adoptEmail(FirebaseTokenVerifier.Identity identity) {
        if (identity.email() == null || identity.email().isBlank()) {
            return null;
        }
        Optional<Long> id = jdbc.sql("SELECT id FROM app_user WHERE lower(email) = lower(:e)")
                .param("e", identity.email().trim())
                .query(Long.class)
                .optional();
        if (id.isEmpty()) {
            return null;
        }
        int updated = jdbc.sql("""
                        UPDATE app_user SET firebase_uid = :f
                        WHERE id = :id AND (firebase_uid IS NULL OR firebase_uid = :f)""")
                .param("f", identity.uid())
                .param("id", id.get())
                .update();
        if (updated == 1) {
            return byId(id.get()).orElseThrow();
        }
        throw new AuthRejected(AuthRejected.Reason.EMAIL_TAKEN,
                "That email already has an account. Sign in instead.");
    }

    /** An account whose password lives in Postgres, used while Firebase Email/Password is off. */
    public User registerLocal(String email, String password) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (emailTaken(normalized)) {
            throw new AuthRejected(AuthRejected.Reason.EMAIL_TAKEN,
                    "That email already has an account. Sign in instead.");
        }
        String hash = PasswordHasher.hash(password);
        String base = usernameFor(normalized, "local");
        for (int n = 0; n < 50; n++) {
            String username = n == 0 ? base : trim(base + "_" + (n + 1));
            if (taken(username)) {
                continue;
            }
            try {
                return jdbc.sql("""
                                INSERT INTO app_user (username, display_name, email, password_hash)
                                VALUES (:u, :d, :e, :p)
                                RETURNING id, username, display_name""")
                        .param("u", username)
                        .param("d", base)
                        .param("e", normalized)
                        .param("p", hash)
                        .query((rs, i) -> new User(rs.getLong(1), rs.getString(2), rs.getString(3)))
                        .single();
            } catch (DataIntegrityViolationException e) {
                if (emailTaken(normalized)) {
                    throw new AuthRejected(AuthRejected.Reason.EMAIL_TAKEN,
                            "That email already has an account. Sign in instead.");
                }
            }
        }
        throw new IllegalArgumentException("could not allocate a username");
    }

    /** Empty when this email has no local password, or the password does not match. */
    public Optional<User> authenticateLocal(String email, String password) {
        if (email == null || password == null) {
            return Optional.empty();
        }
        record Row(long id, String username, String displayName, String hash) {
        }
        Optional<Row> row = jdbc.sql("""
                        SELECT id, username, display_name, password_hash
                        FROM app_user
                        WHERE lower(email) = lower(:e) AND password_hash IS NOT NULL
                        ORDER BY id
                        LIMIT 1""")
                .param("e", email.trim())
                .query((rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .optional();
        if (row.isEmpty() || !PasswordHasher.verify(password, row.get().hash())) {
            return Optional.empty();
        }
        Row found = row.get();
        return Optional.of(new User(found.id(), found.username(), found.displayName()));
    }

    private boolean emailTaken(String normalizedEmail) {
        return jdbc.sql("SELECT id FROM app_user WHERE lower(email) = :e")
                .param("e", normalizedEmail).query(Long.class).optional().isPresent();
    }

    private boolean taken(String username) {
        return jdbc.sql("SELECT id FROM app_user WHERE username = :u")
                .param("u", username).query(Long.class).optional().isPresent();
    }

    private static String trim(String username) {
        return username.length() > 32 ? username.substring(0, 32) : username;
    }
}
