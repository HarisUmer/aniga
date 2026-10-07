package com.example.aniga.data.model;

public class FriendRequest {
    public long id;
    public long requesterId;
    public String username;
    public String displayName;
    public String createdAt;

    public String name() {
        return displayName != null && !displayName.isEmpty() ? displayName : username;
    }
}
