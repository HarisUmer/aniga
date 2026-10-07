package com.example.aniga.data.model;

import java.util.List;

public class ChatMessage {
    public long id;
    public long roomId;
    public Long senderId;
    public String sender;
    public String body;
    public String sentAt;
    public List<String> mentions;
}
