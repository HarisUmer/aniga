package com.aniga.backend.chat;

import java.sql.Connection;
import java.sql.Statement;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.stereotype.Component;

import com.aniga.backend.db.FirebaseSchema;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Listens on the Postgres subject {@code chat_message}. Each insert wakes every
 * backend instance that is connected, so chat does not need a second database.
 */
@Component
public class ChatListener {

    private final DataSource dataSource;
    private final ChatHub hub;
    private final ChatService chat;
    private volatile boolean running = true;

    public ChatListener(DataSource dataSource, ChatHub hub, ChatService chat, FirebaseSchema schema) {
        this.dataSource = dataSource;
        this.hub = hub;
        this.chat = chat;
        if (schema == null) throw new IllegalStateException("schema");
    }

    @PostConstruct
    void start() {
        Thread.ofVirtual().name("chat-listen").start(this::loop);
    }

    @PreDestroy
    void stop() {
        running = false;
    }

    private void loop() {
        while (running) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(true);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN chat_message");
                }
                PGConnection pg = connection.unwrap(PGConnection.class);
                while (running && !connection.isClosed()) {
                    PGNotification[] notes = pg.getNotifications(10_000);
                    if (notes == null) continue;
                    for (PGNotification note : notes) deliver(note.getParameter());
                }
            } catch (Exception e) {
                if (!running) return;
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void deliver(String parameter) {
        try {
            hub.publish(chat.get(Long.parseLong(parameter)));
        } catch (RuntimeException ignored) {
            // A dropped message id should not stop the listener.
        }
    }
}
