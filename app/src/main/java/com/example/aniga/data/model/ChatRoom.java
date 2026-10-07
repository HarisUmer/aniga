package com.example.aniga.data.model;

public class ChatRoom {
    public long id;
    public String roomType;
    public String title;
    public String peer;

    public String label() {
        if ("GLOBAL".equals(roomType)) {
            return "Global";
        }
        if (title != null && !title.isEmpty()) {
            return title;
        }
        return peer != null ? peer : "Chat";
    }
}
