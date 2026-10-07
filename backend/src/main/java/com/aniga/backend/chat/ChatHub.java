package com.aniga.backend.chat;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** In-memory subscribers for one backend process. Postgres NOTIFY is the source of new messages. */
@Component
public class ChatHub {

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> rooms = new ConcurrentHashMap<>();

    public SseEmitter subscribe(long roomId) {
        SseEmitter emitter = new SseEmitter(0L);
        rooms.computeIfAbsent(roomId, id -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> {
            List<SseEmitter> list = rooms.get(roomId);
            if (list != null) list.remove(emitter);
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    public void publish(ChatService.Message message) {
        List<SseEmitter> list = rooms.get(message.roomId());
        if (list == null) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("message").data(message));
            } catch (Exception e) {
                emitter.complete();
                list.remove(emitter);
            }
        }
    }
}
