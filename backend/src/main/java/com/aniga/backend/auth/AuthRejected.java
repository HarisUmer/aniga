package com.aniga.backend.auth;

/**
 * A sign-in or sign-up the caller can correct. The message is safe to show on the phone.
 */
public class AuthRejected extends IllegalArgumentException {

    public enum Reason {
        NOT_CONFIGURED,
        EMAIL_TAKEN,
        BAD_CREDENTIALS,
        WEAK,
        BUSY,
        OTHER
    }

    private final Reason reason;

    public AuthRejected(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
