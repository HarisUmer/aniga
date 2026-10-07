package com.example.aniga.data.model;

/** A friend or a found account. */
public class Person {
    public long id;
    public String username;
    public String displayName;

    public String name() {
        return displayName != null && !displayName.isEmpty() ? displayName : username;
    }
}
