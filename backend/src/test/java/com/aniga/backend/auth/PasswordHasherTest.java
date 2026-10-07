package com.aniga.backend.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    @Test
    void hashVerifiesAndRejectsADifferentPassword() {
        String stored = PasswordHasher.hash("correct-horse");
        assertTrue(stored.startsWith("pbkdf2-sha256$"));
        assertFalse(stored.contains("correct-horse"));
        assertTrue(PasswordHasher.verify("correct-horse", stored));
        assertFalse(PasswordHasher.verify("wrong-horse", stored));
        assertNotEquals(stored, PasswordHasher.hash("correct-horse"));
    }

    @Test
    void rejectsADamagedHash() {
        assertFalse(PasswordHasher.verify("correct-horse", "not-a-hash"));
        assertFalse(PasswordHasher.verify("correct-horse", null));
    }
}
