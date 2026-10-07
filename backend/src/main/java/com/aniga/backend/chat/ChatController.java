package com.aniga.backend.chat;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aniga.backend.auth.AuthFilter;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    public record DirectRequest(String username) {
    }

    public record GroupRequest(String title, List<String> usernames) {
    }

    public record PostMessage(String body, ChatService.Share share) {
    }

    private final ChatService chat;
    private final ChatHub hub;

    public ChatController(ChatService chat, ChatHub hub) {
        this.chat = chat;
        this.hub = hub;
    }

    @GetMapping("/rooms")
    public List<ChatService.Room> rooms(HttpServletRequest request) {
        return chat.rooms(AuthFilter.userId(request));
    }

    @PostMapping("/direct")
    public ChatService.Room direct(HttpServletRequest request, @RequestBody DirectRequest body) {
        return chat.openDirect(AuthFilter.userId(request), body.username());
    }

    @PostMapping("/group")
    public ChatService.Room group(HttpServletRequest request, @RequestBody GroupRequest body) {
        return chat.openGroup(AuthFilter.userId(request), body.title(), body.usernames());
    }

    /** The one public room everyone shares. Joins the caller on first open. */
    @GetMapping("/global")
    public ChatService.Room global(HttpServletRequest request) {
        return chat.global(AuthFilter.userId(request));
    }

    @GetMapping("/rooms/{id}/messages")
    public List<ChatService.Message> messages(HttpServletRequest request, @PathVariable long id) {
        return chat.messages(AuthFilter.userId(request), id);
    }

    @PostMapping("/rooms/{id}/messages")
    public ChatService.Message post(HttpServletRequest request, @PathVariable long id, @RequestBody PostMessage body) {
        return chat.post(AuthFilter.userId(request), id, body.body(), body.share());
    }

    /** Live messages for this room. History is {@code GET .../messages}; this stream is what NOTIFY wakes. */
    @GetMapping(value = "/rooms/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(HttpServletRequest request, @PathVariable long id) {
        chat.requireMember(AuthFilter.userId(request), id);
        return hub.subscribe(id);
    }
}
