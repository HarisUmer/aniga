package com.example.aniga.data.model;

/** What the backend returns after it checks the password with Firebase: our session token + the user. */
public class AuthResponse {
    public String token;
    public User user;
}
