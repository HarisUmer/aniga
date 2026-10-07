package com.aniga.backend.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Checks a Firebase ID token using Google's published signing keys.
 * A service-account private key is not required for this check.
 */
public final class FirebaseTokenVerifier {

    public record Identity(String uid, String email, String name) {
    }

    private static final String KEYS =
            "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";

    private final String projectId;
    private final JWKSource<SecurityContext> keys;

    public FirebaseTokenVerifier(String projectId, JWKSource<SecurityContext> keys) {
        this.projectId = projectId;
        this.keys = keys;
    }

    public static FirebaseTokenVerifier google(String projectId) {
        return new FirebaseTokenVerifier(projectId, new GoogleSecureTokenKeys());
    }

    public Identity verify(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("missing token");
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
                throw new IllegalArgumentException("unexpected algorithm");
            }
            String kid = jwt.getHeader().getKeyID();
            List<JWK> matches = keys.get(new JWKSelector(new JWKMatcher.Builder().keyID(kid).build()), null);
            if (matches.isEmpty() || !jwt.verify(new RSASSAVerifier(matches.get(0).toRSAKey()))) {
                throw new IllegalArgumentException("bad signature");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            String issuer = "https://securetoken.google.com/" + projectId;
            if (!issuer.equals(claims.getIssuer())) {
                throw new IllegalArgumentException("wrong issuer");
            }
            if (claims.getAudience() == null || !claims.getAudience().contains(projectId)) {
                throw new IllegalArgumentException("wrong audience");
            }
            Date exp = claims.getExpirationTime();
            if (exp == null || exp.getTime() + 60_000L < System.currentTimeMillis()) {
                throw new IllegalArgumentException("token expired");
            }
            String uid = claims.getSubject();
            if (uid == null || uid.isBlank()) {
                throw new IllegalArgumentException("missing subject");
            }
            return new Identity(uid, claims.getStringClaim("email"), claims.getStringClaim("name"));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid Firebase token");
        }
    }

    /** Cached copy of Google's securetoken JWKS. */
    static final class GoogleSecureTokenKeys implements JWKSource<SecurityContext> {
        private final HttpClient http = HttpClient.newHttpClient();
        private volatile JWKSet cached;
        private volatile long fetchedAt;

        @Override
        public List<JWK> get(JWKSelector selector, SecurityContext context) throws com.nimbusds.jose.KeySourceException {
            try {
                if (cached == null || System.currentTimeMillis() - fetchedAt > 3_600_000L) {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(KEYS)).GET().build();
                    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) {
                        throw new com.nimbusds.jose.KeySourceException("jwks http " + response.statusCode());
                    }
                    cached = JWKSet.parse(response.body());
                    fetchedAt = System.currentTimeMillis();
                }
                return selector.select(cached);
            } catch (com.nimbusds.jose.KeySourceException e) {
                throw e;
            } catch (Exception e) {
                throw new com.nimbusds.jose.KeySourceException(e.getMessage(), e);
            }
        }
    }
}
