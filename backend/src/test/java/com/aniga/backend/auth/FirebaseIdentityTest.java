package com.aniga.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import com.aniga.backend.chat.Mentions;
import com.aniga.backend.db.SqlScript;

class FirebaseIdentityTest {

    @Test
    void usernameComesFromTheEmailLocalPart() {
        assertEquals("kenji", AccountService.usernameFor("Kenji@example.com", "uid"));
        assertTrue(AccountService.usernameFor("a@b.co", "AbC123456789").startsWith("u_"));
    }

    @Test
    void mentionsAreUsernamesNotEmails() {
        assertEquals(List.of("aoi", "kenji"), Mentions.usernames("hey @Aoi, ask @kenji"));
        assertEquals(List.of(), Mentions.usernames("mail kenji@example.com"));
        assertEquals(List.of("aoi"), Mentions.usernames("@aoi @aoi @ab"));
    }

    @Test
    void migrationKeepsTheNotifyFunctionInOneStatement() throws Exception {
        try (var in = SqlScript.class.getResourceAsStream("/db/010_firebase_auth.sql")) {
            String sql = new String(in.readAllBytes());
            List<String> statements = SqlScript.statements(sql);
            assertTrue(statements.stream().anyMatch(s -> s.contains("pg_notify") && s.contains("RETURN NEW")));
            assertTrue(statements.stream().noneMatch(s -> s.trim().equals("RETURN NEW")));
            assertTrue(statements.stream().anyMatch(s -> s.startsWith("CREATE TABLE IF NOT EXISTS source_report")));
        }
    }

    @Test
    void acceptsATokenSignedByTheProjectKey() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
        FirebaseTokenVerifier verifier = new FirebaseTokenVerifier("aniga-test", source(key.toPublicJWK()));
        FirebaseTokenVerifier.Identity identity = verifier.verify(token(key, "aniga-test",
                "https://securetoken.google.com/aniga-test", Instant.now().plusSeconds(600)));
        assertEquals("uid-1", identity.uid());
        assertEquals("kenji@example.com", identity.email());
    }

    @Test
    void rejectsATokenForAnotherProject() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
        FirebaseTokenVerifier verifier = new FirebaseTokenVerifier("aniga-test", source(key.toPublicJWK()));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(token(key, "other-project",
                "https://securetoken.google.com/other-project", Instant.now().plusSeconds(600))));
    }

    @Test
    void rejectsAnExpiredToken() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
        FirebaseTokenVerifier verifier = new FirebaseTokenVerifier("aniga-test", source(key.toPublicJWK()));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(token(key, "aniga-test",
                "https://securetoken.google.com/aniga-test", Instant.now().minusSeconds(300))));
    }

    @Test
    void privateRoutesRequireASession() {
        assertTrue(AuthFilter.requiresAuth("GET", "/api/chat/rooms"));
        assertTrue(AuthFilter.requiresAuth("POST", "/api/reports"));
        assertTrue(AuthFilter.requiresAuth("GET", "/api/people/friends"));
        assertTrue(AuthFilter.requiresAuth("GET", "/api/me/recommendations"));
        assertTrue(AuthFilter.requiresAuth("GET", "/api/users/5/library"));
        assertFalse(AuthFilter.requiresAuth("GET", "/api/read/mangadex/popular"));
        assertFalse(AuthFilter.requiresAuth("POST", "/api/auth/login"));
    }

    @Test
    void adminRoutesAreTaggedSeparately() {
        assertTrue(AuthFilter.isAdminRoute("POST", "/api/users"));
        assertTrue(AuthFilter.isAdminRoute("POST", "/api/model/reload"));
        assertFalse(AuthFilter.isAdminRoute("GET", "/api/users"));
        assertFalse(AuthFilter.isAdminRoute("POST", "/api/people/requests"));
    }

    @Test
    void sessionTokenRoundTrips() {
        SessionService sessions = new SessionService("a-test-secret-value");
        String token = sessions.issue(42L);
        assertEquals(42L, sessions.userId(token));
        assertEquals(null, sessions.userId("not-a-token"));
        assertEquals(null, sessions.userId(sessions.issue(42L) + "x"));
    }

    private static JWKSource<SecurityContext> source(JWK key) {
        JWKSet set = new JWKSet(key);
        return (selector, context) -> selector.select(set);
    }

    private static String token(RSAKey key, String audience, String issuer, Instant exp) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .subject("uid-1")
                        .issuer(issuer)
                        .audience(audience)
                        .expirationTime(Date.from(exp))
                        .claim("email", "kenji@example.com")
                        .claim("name", "Kenji")
                        .build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
