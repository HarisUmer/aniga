package com.aniga.backend.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

@Service
public class ChatService {

    public record Room(long id, String roomType, String title, String peer) {
    }

    /** A title shared into chat, rendered as a card. kind is "reading" or "rating". */
    public record Share(String kind, Long mediaId, String source, String seriesId, String title,
                        String coverUrl, String mediaType, Integer score, String note) {
    }

    public record Message(long id, long roomId, Long senderId, String sender, String body,
                          String sentAt, List<String> mentions, Share share) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public ChatService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Room> rooms(long userId) {
        return jdbc.sql("""
                        SELECT r.id, r.room_type, r.title,
                               (SELECT u.username FROM chat_member m
                                JOIN app_user u ON u.id = m.user_id
                                WHERE m.room_id = r.id AND m.user_id <> :me
                                ORDER BY u.username LIMIT 1) AS peer
                        FROM chat_room r
                        JOIN chat_member me ON me.room_id = r.id AND me.user_id = :me
                        ORDER BY r.id DESC""")
                .param("me", userId)
                .query((rs, i) -> new Room(rs.getLong("id"), rs.getString("room_type"),
                        rs.getString("title"), rs.getString("peer")))
                .list();
    }

    @Transactional
    public Room openDirect(long me, String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        String name = username.trim().toLowerCase(Locale.ROOT);
        Long other = jdbc.sql("SELECT id FROM app_user WHERE lower(username) = :u")
                .param("u", name).query(Long.class).optional().orElse(null);
        if (other == null) {
            throw new java.util.NoSuchElementException("no user named " + name);
        }
        if (other == me) {
            throw new IllegalArgumentException("pick someone else");
        }
        Long room = jdbc.sql("""
                        SELECT r.id FROM chat_room r
                        JOIN chat_member a ON a.room_id = r.id AND a.user_id = :me
                        JOIN chat_member b ON b.room_id = r.id AND b.user_id = :them
                        WHERE r.room_type = 'DIRECT'
                        LIMIT 1""")
                .param("me", me).param("them", other).query(Long.class).optional().orElse(null);
        if (room == null) {
            room = jdbc.sql("INSERT INTO chat_room (room_type, created_by) VALUES ('DIRECT', :me) RETURNING id")
                    .param("me", me).query(Long.class).single();
            jdbc.sql("INSERT INTO chat_member (room_id, user_id, role) VALUES (:r, :u, 'OWNER')")
                    .param("r", room).param("u", me).update();
            jdbc.sql("INSERT INTO chat_member (room_id, user_id, role) VALUES (:r, :u, 'MEMBER')")
                    .param("r", room).param("u", other).update();
        }
        return new Room(room, "DIRECT", null, name);
    }

    @Transactional
    public Room openGroup(long me, String title, List<String> usernames) {
        String name = title == null || title.isBlank() ? "Group" : title.trim();
        if (name.length() > 80) name = name.substring(0, 80);
        long room = jdbc.sql("INSERT INTO chat_room (room_type, title, created_by) VALUES ('GROUP', :t, :me) RETURNING id")
                .param("t", name).param("me", me).query(Long.class).single();
        jdbc.sql("INSERT INTO chat_member (room_id, user_id, role) VALUES (:r, :u, 'OWNER')")
                .param("r", room).param("u", me).update();
        if (usernames != null) {
            for (String raw : usernames) {
                if (raw == null || raw.isBlank()) continue;
                String uname = raw.trim().toLowerCase(Locale.ROOT);
                jdbc.sql("""
                                INSERT INTO chat_member (room_id, user_id, role)
                                SELECT :r, id, 'MEMBER' FROM app_user WHERE username = :u AND id <> :me
                                ON CONFLICT DO NOTHING""")
                        .param("r", room).param("u", uname).param("me", me).update();
            }
        }
        return new Room(room, "GROUP", name, null);
    }

    @Transactional
    public Room global(long me) {
        Long room = jdbc.sql("SELECT id FROM chat_room WHERE room_type = 'GLOBAL' ORDER BY id LIMIT 1")
                .query(Long.class).optional().orElse(null);
        if (room == null) {
            room = jdbc.sql("INSERT INTO chat_room (room_type, title, created_by) VALUES ('GLOBAL', 'Global', :me) RETURNING id")
                    .param("me", me).query(Long.class).single();
        }
        jdbc.sql("""
                        INSERT INTO chat_member (room_id, user_id, role) VALUES (:r, :u, 'MEMBER')
                        ON CONFLICT DO NOTHING""")
                .param("r", room).param("u", me).update();
        return new Room(room, "GLOBAL", "Global", null);
    }

    public void requireMember(long userId, long roomId) {
        assertMember(userId, roomId);
    }

    public List<Message> messages(long me, long roomId) {
        assertMember(me, roomId);
        List<Long> ids = jdbc.sql("""
                        SELECT id FROM chat_message WHERE room_id = :r ORDER BY sent_at DESC LIMIT 400""")
                .param("r", roomId).query(Long.class).list();
        List<Message> out = new ArrayList<>();
        for (int i = ids.size() - 1; i >= 0; i--) out.add(get(ids.get(i)));
        return out;
    }

    @Transactional
    public Message post(long me, long roomId, String body, Share share) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty() && share == null) {
            throw new IllegalArgumentException("message is empty");
        }
        if (text.length() > 2000) {
            throw new IllegalArgumentException("message is too long");
        }
        assertMember(me, roomId);
        share = withScore(me, share);
        String shareJson = writeShare(share);
        long id = jdbc.sql("""
                        INSERT INTO chat_message (room_id, sender_id, body, share)
                        VALUES (:r, :s, :b, CAST(:share AS jsonb)) RETURNING id""")
                .param("r", roomId).param("s", me).param("b", text).param("share", shareJson)
                .query(Long.class).single();
        for (String name : Mentions.usernames(text)) {
            jdbc.sql("""
                            INSERT INTO chat_mention (message_id, user_id)
                            SELECT :m, id FROM app_user WHERE username = :n AND id <> :me""")
                    .param("m", id).param("n", name).param("me", me).update();
        }
        return get(id);
    }

    /** A shared title shows the sender's score when they have rated it. */
    private Share withScore(long me, Share share) {
        if (share == null || share.mediaId() == null || share.score() != null) {
            return share;
        }
        Integer score = jdbc.sql("SELECT score FROM rating WHERE user_id = :u AND media_id = :m")
                .param("u", me).param("m", share.mediaId()).query(Integer.class).optional().orElse(null);
        if (score == null) {
            return share;
        }
        return new Share("rating", share.mediaId(), share.source(), share.seriesId(), share.title(),
                share.coverUrl(), share.mediaType(), score, share.note());
    }

    public Message get(long id) {
        Message message = jdbc.sql("""
                        SELECT m.id, m.room_id, m.sender_id, u.username, m.body, m.sent_at, m.share::text AS share
                        FROM chat_message m
                        LEFT JOIN app_user u ON u.id = m.sender_id
                        WHERE m.id = :id""")
                .param("id", id)
                .query((rs, i) -> {
                    long senderRaw = rs.getLong("sender_id");
                    Long senderId = rs.wasNull() ? null : senderRaw;
                    return new Message(rs.getLong("id"), rs.getLong("room_id"), senderId,
                            rs.getString("username"), rs.getString("body"),
                            rs.getTimestamp("sent_at").toInstant().toString(), List.of(),
                            readShare(rs.getString("share")));
                })
                .optional()
                .orElseThrow(() -> new java.util.NoSuchElementException("message not found"));
        List<String> mentions = jdbc.sql("""
                        SELECT u.username FROM chat_mention cm
                        JOIN app_user u ON u.id = cm.user_id
                        WHERE cm.message_id = :id ORDER BY u.username""")
                .param("id", id).query(String.class).list();
        return new Message(message.id(), message.roomId(), message.senderId(), message.sender(),
                message.body(), message.sentAt(), mentions, message.share());
    }

    private String writeShare(Share share) {
        return share == null ? null : json.writeValueAsString(share);
    }

    private Share readShare(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readValue(raw, Share.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void assertMember(long userId, long roomId) {
        String type = jdbc.sql("SELECT room_type FROM chat_room WHERE id = :r")
                .param("r", roomId).query(String.class).optional()
                .orElseThrow(() -> new java.util.NoSuchElementException("room not found"));
        if ("GLOBAL".equals(type)) {
            return;
        }
        long n = jdbc.sql("SELECT count(*) FROM chat_member WHERE room_id = :r AND user_id = :u")
                .param("r", roomId).param("u", userId).query(Long.class).single();
        if (n == 0) throw new IllegalArgumentException("not a member of this room");
    }
}
