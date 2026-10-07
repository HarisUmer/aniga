package com.aniga.backend.auth;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aniga.backend.user.UserController.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Sign-up and sign-in. Firebase is asked first. When Email/Password is not enabled there yet,
 * the account is kept in Postgres with a hashed password. Either way the phone receives our session
 * token and never sees a Firebase credential.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record Credentials(String email, String password, String username) {
    }

    public record AuthResponse(String token, User user) {
    }

    private final FirebaseAuthClient firebase;
    private final AccountService accounts;
    private final SessionService sessions;
    private final RateLimiter logins = new RateLimiter(10, 60_000);

    public AuthController(FirebaseAuthClient firebase, AccountService accounts, SessionService sessions) {
        this.firebase = firebase;
        this.accounts = accounts;
        this.sessions = sessions;
    }

    @PostMapping("/signup")
    public AuthResponse signup(HttpServletRequest request, @RequestBody Credentials body) {
        logins.check(clientKey(request));
        try {
            FirebaseTokenVerifier.Identity id = firebase.signUp(body.email(), body.password());
            User user = accounts.link(id);
            return new AuthResponse(sessions.issue(user.id()), user);
        } catch (AuthRejected rejected) {
            if (rejected.reason() != AuthRejected.Reason.NOT_CONFIGURED) {
                throw rejected;
            }
            // Email/Password is still off in the Firebase console. Keep the account here, hashed.
            User user = accounts.registerLocal(body.email(), body.password());
            return new AuthResponse(sessions.issue(user.id()), user);
        }
    }

    @PostMapping("/login")
    public AuthResponse login(HttpServletRequest request, @RequestBody Credentials body) {
        logins.check(clientKey(request));
        try {
            FirebaseTokenVerifier.Identity id = firebase.signIn(body.email(), body.password());
            User user = accounts.link(id);
            return new AuthResponse(sessions.issue(user.id()), user);
        } catch (AuthRejected rejected) {
            if (rejected.reason() == AuthRejected.Reason.NOT_CONFIGURED
                    || rejected.reason() == AuthRejected.Reason.BAD_CREDENTIALS) {
                Optional<User> local = accounts.authenticateLocal(body.email(), body.password());
                if (local.isPresent()) {
                    return new AuthResponse(sessions.issue(local.get().id()), local.get());
                }
                if (rejected.reason() == AuthRejected.Reason.NOT_CONFIGURED) {
                    throw new AuthRejected(AuthRejected.Reason.BAD_CREDENTIALS, "Wrong email or password.");
                }
            }
            throw rejected;
        }
    }

    /** Who the current session belongs to. The filter has already validated the token. */
    @GetMapping("/me")
    public User me(HttpServletRequest request) {
        long userId = AuthFilter.userId(request);
        return accounts.byId(userId).orElseThrow(() -> new IllegalStateException("account missing"));
    }

    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** A small fixed-window limiter so password guessing is slow. Not a replacement for a real WAF. */
    static final class RateLimiter {
        private final int max;
        private final long windowMs;
        private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        RateLimiter(int max, long windowMs) {
            this.max = max;
            this.windowMs = windowMs;
        }

        void check(String key) {
            long now = System.currentTimeMillis();
            long[] start = windows.computeIfAbsent(key, k -> new long[]{now});
            AtomicInteger count = counts.computeIfAbsent(key, k -> new AtomicInteger());
            synchronized (start) {
                if (now - start[0] > windowMs) {
                    start[0] = now;
                    count.set(0);
                }
                if (count.incrementAndGet() > max) {
                    throw new IllegalStateException("too many attempts, wait a minute");
                }
            }
        }
    }
}
