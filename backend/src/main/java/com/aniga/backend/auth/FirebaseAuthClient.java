package com.aniga.backend.auth;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Talks to Firebase Authentication over its REST API using the project's Web API key.
 * The phone sends email and password to our backend; only the backend talks to Firebase.
 */
@Service
public class FirebaseAuthClient {

    private final RestClient http;
    private final String apiKey;

    public FirebaseAuthClient(@Value("${aniga.firebase.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.http = RestClient.builder()
                .baseUrl("https://identitytoolkit.googleapis.com/v1")
                .build();
    }

    public FirebaseTokenVerifier.Identity signUp(String email, String password) {
        return call("accounts:signUp", email, password);
    }

    public FirebaseTokenVerifier.Identity signIn(String email, String password) {
        return call("accounts:signInWithPassword", email, password);
    }

    @SuppressWarnings("unchecked")
    private FirebaseTokenVerifier.Identity call(String path, String email, String password) {
        if (email == null || !email.contains("@") || password == null || password.length() < 6) {
            throw new AuthRejected(AuthRejected.Reason.WEAK, "use an email and a password of at least 6 characters");
        }
        try {
            Map<String, Object> body = http.post()
                    .uri(uri -> uri.path("/" + path).queryParam("key", apiKey).build())
                    .body(Map.of("email", email.trim(), "password", password, "returnSecureToken", true))
                    .retrieve()
                    .body(Map.class);
            if (body == null || body.get("localId") == null) {
                throw new IllegalStateException("Firebase returned no user");
            }
            return new FirebaseTokenVerifier.Identity(
                    (String) body.get("localId"),
                    (String) body.get("email"),
                    (String) body.get("displayName"));
        } catch (HttpClientErrorException e) {
            throw rejected(e.getResponseBodyAsString());
        }
    }

    /** Firebase error codes are terse; turn the common ones into something a person can act on. */
    private static AuthRejected rejected(String responseBody) {
        String body = responseBody == null ? "" : responseBody;
        if (body.contains("CONFIGURATION_NOT_FOUND")) {
            return new AuthRejected(AuthRejected.Reason.NOT_CONFIGURED,
                    "Firebase Authentication is not enabled yet. In the Firebase console open Authentication, "
                            + "click Get started, then enable Email/Password.");
        }
        if (body.contains("EMAIL_EXISTS")) {
            return new AuthRejected(AuthRejected.Reason.EMAIL_TAKEN,
                    "That email already has an account. Sign in instead.");
        }
        if (body.contains("EMAIL_NOT_FOUND") || body.contains("INVALID_LOGIN_CREDENTIALS")
                || body.contains("INVALID_PASSWORD")) {
            return new AuthRejected(AuthRejected.Reason.BAD_CREDENTIALS, "Wrong email or password.");
        }
        if (body.contains("WEAK_PASSWORD")) {
            return new AuthRejected(AuthRejected.Reason.WEAK, "Use a password of at least 6 characters.");
        }
        if (body.contains("TOO_MANY_ATTEMPTS")) {
            return new AuthRejected(AuthRejected.Reason.BUSY, "Too many attempts. Wait a bit and try again.");
        }
        return new AuthRejected(AuthRejected.Reason.OTHER, "Sign-in failed.");
    }
}
