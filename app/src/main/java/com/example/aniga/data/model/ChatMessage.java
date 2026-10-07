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
    public Share share;

    /** A title shared into chat, rendered as a card. kind is "reading" or "rating". */
    public static class Share {
        public String kind;
        public Long mediaId;
        public String source;
        public String seriesId;
        public String title;
        public String coverUrl;
        public String mediaType;
        public Integer score;
        public String note;
    }
}
