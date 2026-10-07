package com.aniga.backend.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** PBKDF2-SHA256 password hashes. The stored form is algorithm, iterations, salt, and hash. */
public final class PasswordHasher {

    static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS);
        Base64.Encoder enc = Base64.getEncoder().withoutPadding();
        return "pbkdf2-sha256$" + ITERATIONS + "$" + enc.encodeToString(salt) + "$" + enc.encodeToString(derived);
    }

    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) {
            return false;
        }
        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (iterations < 1 || iterations > 5_000_000) {
            return false;
        }
        byte[] salt;
        byte[] expected;
        try {
            Base64.Decoder dec = Base64.getDecoder();
            salt = dec.decode(parts[2]);
            expected = dec.decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] actual = derive(password, salt, iterations);
        return MessageDigest.isEqual(actual, expected);
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("could not hash password", e);
        } finally {
            spec.clearPassword();
        }
    }
}
