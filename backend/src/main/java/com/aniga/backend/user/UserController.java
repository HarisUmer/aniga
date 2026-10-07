package com.aniga.backend.user;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    public record User(long id, String username, String displayName) {
    }

    public record NewUser(String username, String displayName) {
    }

    public record RatingRequest(int score) {
    }

    public record RatedMedia(long mediaId, String title, String mediaType, int score) {
    }

    private final JdbcClient jdbc;
    private final RatingRepository ratings;

    public UserController(JdbcClient jdbc, RatingRepository ratings) {
        this.jdbc = jdbc;
        this.ratings = ratings;
    }

    @GetMapping
    public List<User> list() {
        return jdbc.sql("SELECT id, username, display_name FROM app_user ORDER BY id")
                .query((rs, i) -> new User(rs.getLong(1), rs.getString(2), rs.getString(3)))
                .list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public User create(@RequestBody NewUser body) {
        if (body.username() == null || !body.username().matches("[a-zA-Z0-9_]{3,32}")) {
            throw new IllegalArgumentException("username must be 3-32 chars: letters, digits, underscore");
        }
        return jdbc.sql("""
                        INSERT INTO app_user (username, display_name) VALUES (:u, :d)
                        RETURNING id, username, display_name""")
                .param("u", body.username())
                .param("d", body.displayName())
                .query((rs, i) -> new User(rs.getLong(1), rs.getString(2), rs.getString(3)))
                .single();
    }

    @GetMapping("/{userId}/ratings")
    public List<RatedMedia> ratings(@PathVariable long userId) {
        return jdbc.sql("""
                        SELECT m.id, m.title, m.media_type, r.score
                        FROM rating r JOIN media m ON m.id = r.media_id
                        WHERE r.user_id = :u ORDER BY r.rated_at DESC""")
                .param("u", userId)
                .query((rs, i) -> new RatedMedia(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)))
                .list();
    }

    @PutMapping("/{userId}/ratings/{mediaId}")
    @Transactional
    public Map<String, Object> rate(@PathVariable long userId, @PathVariable long mediaId,
                                    @RequestBody RatingRequest body) {
        if (body.score() < 1 || body.score() > 10) {
            throw new IllegalArgumentException("score must be between 1 and 10");
        }
        ratings.upsert(userId, mediaId, body.score());
        return Map.of("userId", userId, "mediaId", mediaId, "score", body.score());
    }

    @DeleteMapping("/{userId}/ratings/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unrate(@PathVariable long userId, @PathVariable long mediaId) {
        jdbc.sql("DELETE FROM rating WHERE user_id = :u AND media_id = :m")
                .param("u", userId).param("m", mediaId).update();
    }
}
