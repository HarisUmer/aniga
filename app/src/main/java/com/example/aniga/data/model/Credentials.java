package com.example.aniga.data.model;

/** Sent to /api/auth/login and /api/auth/signup. */
public class Credentials {
    public String email;
    public String password;

    public Credentials(String email, String password) {
        this.email = email;
        this.password = password;
    }
}
