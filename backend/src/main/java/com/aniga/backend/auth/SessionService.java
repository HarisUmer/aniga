package com.aniga.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Our own session token. After the backend has checked a password with Firebase it hands the phone
 * one of these, signed with a server-only secret. The phone never holds a Firebase token.
 */
@Service
public class SessionService {

    private static final long TTL_MS = 30L * 24 * 60 * 60 * 1000;

    private final byte[] key;

    public SessionService(@Value("${aniga.session.secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("aniga.session.secret is not set");
        }
        // HS256 needs at least 256 bits; derive a fixed-length key from whatever secret was configured.
        this.key = sha256(secret);
    }

    public String issue(long userId) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(Long.toString(userId))
                    .issuer("aniga")
                    .issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + TTL_MS))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("could not issue a session token", e);
        }
    }

    /** Returns the user id in a valid, unexpired token, or null when the token is missing or bad. */
    public Long userId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(key))) {
                return null;
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp == null || exp.before(new Date())) {
                return null;
            }
            return Long.parseLong(claims.getSubject());
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
