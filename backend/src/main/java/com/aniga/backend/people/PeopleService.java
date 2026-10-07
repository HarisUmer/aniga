package com.aniga.backend.people;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aniga.backend.chat.ChatService;

/**
 * Friends, shared shelves, taste matches, and a predicted "reading personality".
 * All of this reads the same Postgres rows the recommender already uses — no second datastore.
 */
@Service
public class PeopleService {

    public record Person(long id, String username, String displayName) {
    }

    public record RequestView(long id, long requesterId, String username, String displayName, String createdAt) {
    }

    public record Match(long userId, String username, String displayName, int shared,
                        List<String> sharedTitles, List<String> theirPicks, double affinity, String blurb) {
    }

    public record ShelfItem(long mediaId, String title, String mediaType, String coverUrl,
                            String status, Integer score) {
    }

    public record Trait(String name, int score) {
    }

    public record Personality(String type, String summary, List<Trait> traits, List<String> topGenres) {
    }

    public record Outcome(String status) {
    }

    private final JdbcClient jdbc;
    private final ChatService chat;

    public PeopleService(JdbcClient jdbc, ChatService chat) {
        this.jdbc = jdbc;
        this.chat = chat;
    }

    public List<Person> friends(long me) {
        return jdbc.sql("""
                        SELECT u.id, u.username, u.display_name
                        FROM friend_request fr
                        JOIN app_user u ON u.id = CASE WHEN fr.requester_id = :me
                                                       THEN fr.addressee_id ELSE fr.requester_id END
                        WHERE fr.status = 'ACCEPTED' AND (fr.requester_id = :me OR fr.addressee_id = :me)
                        ORDER BY u.username""")
                .param("me", me)
                .query((rs, i) -> new Person(rs.getLong(1), rs.getString(2), rs.getString(3)))
                .list();
    }

    public List<RequestView> incoming(long me) {
        return jdbc.sql("""
                        SELECT fr.id, fr.requester_id, u.username, u.display_name, fr.created_at::text
                        FROM friend_request fr JOIN app_user u ON u.id = fr.requester_id
                        WHERE fr.addressee_id = :me AND fr.status = 'PENDING'
                        ORDER BY fr.created_at DESC""")
                .param("me", me)
                .query((rs, i) -> new RequestView(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)))
                .list();
    }

    public List<RequestView> outgoing(long me) {
        return jdbc.sql("""
                        SELECT fr.id, fr.addressee_id, u.username, u.display_name, fr.created_at::text
                        FROM friend_request fr JOIN app_user u ON u.id = fr.addressee_id
                        WHERE fr.requester_id = :me AND fr.status = 'PENDING'
                        ORDER BY fr.created_at DESC""")
                .param("me", me)
                .query((rs, i) -> new RequestView(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)))
                .list();
    }

    /** Username, display name, or the email they signed up with. Partial match, eight rows. */
    public List<Person> search(long me, String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String q = raw.trim().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "");
        String like = "%" + q + "%";
        return jdbc.sql("""
                        SELECT id, username, display_name FROM app_user
                        WHERE id <> :me
                          AND (username ILIKE :q
                            OR lower(coalesce(display_name, '')) ILIKE :q
                            OR lower(coalesce(email, '')) ILIKE :q)
                        ORDER BY username = :exact DESC, username
                        LIMIT 8""")
                .param("me", me).param("q", like).param("exact", q)
                .query((rs, i) -> new Person(rs.getLong(1), rs.getString(2), rs.getString(3)))
                .list();
    }

    @Transactional
    public Outcome request(long me, String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        String name = username.trim().toLowerCase(Locale.ROOT);
        Long other = jdbc.sql("""
                        SELECT id FROM app_user
                        WHERE username = :u
                           OR lower(coalesce(email, '')) = :u
                           OR lower(coalesce(display_name, '')) = :u
                        ORDER BY username = :u DESC
                        LIMIT 1""")
                .param("u", name).query(Long.class).optional().orElse(null);
        if (other == null) {
            throw new NoSuchElementException("no user named " + name);
        }
        if (other == me) {
            throw new IllegalArgumentException("you cannot add yourself");
        }
        String existing = jdbc.sql("""
                        SELECT status FROM friend_request
                        WHERE (requester_id = :me AND addressee_id = :o)
                           OR (requester_id = :o AND addressee_id = :me)
                        ORDER BY status = 'ACCEPTED' DESC LIMIT 1""")
                .param("me", me).param("o", other).query(String.class).optional().orElse(null);
        if ("ACCEPTED".equals(existing)) {
            return new Outcome("ACCEPTED");
        }
        // If they already asked us, accept straight away instead of a second pending request.
        Long reverse = jdbc.sql("""
                        SELECT id FROM friend_request
                        WHERE requester_id = :o AND addressee_id = :me AND status = 'PENDING'""")
                .param("o", other).param("me", me).query(Long.class).optional().orElse(null);
        if (reverse != null) {
            accept(me, reverse);
            return new Outcome("ACCEPTED");
        }
        jdbc.sql("""
                        INSERT INTO friend_request (requester_id, addressee_id, status)
                        VALUES (:me, :o, 'PENDING')
                        ON CONFLICT (requester_id, addressee_id)
                        DO UPDATE SET status = 'PENDING', created_at = now(), responded_at = NULL""")
                .param("me", me).param("o", other).update();
        return new Outcome("PENDING");
    }

    @Transactional
    public Outcome accept(long me, long requestId) {
        var row = jdbc.sql("""
                        SELECT requester_id, status FROM friend_request
                        WHERE id = :id AND addressee_id = :me""")
                .param("id", requestId).param("me", me)
                .query((rs, i) -> new long[]{rs.getLong(1), "PENDING".equals(rs.getString(2)) ? 1 : 0})
                .optional()
                .orElseThrow(() -> new NoSuchElementException("request not found"));
        long requester = row[0];
        jdbc.sql("UPDATE friend_request SET status = 'ACCEPTED', responded_at = now() WHERE id = :id")
                .param("id", requestId).update();
        String requesterName = jdbc.sql("SELECT username FROM app_user WHERE id = :id")
                .param("id", requester).query(String.class).single();
        // Open a direct room so new friends can message straight away.
        chat.openDirect(me, requesterName);
        return new Outcome("ACCEPTED");
    }

    @Transactional
    public Outcome decline(long me, long requestId) {
        int updated = jdbc.sql("""
                        UPDATE friend_request SET status = 'DECLINED', responded_at = now()
                        WHERE id = :id AND addressee_id = :me AND status = 'PENDING'""")
                .param("id", requestId).param("me", me).update();
        if (updated == 0) {
            throw new NoSuchElementException("request not found");
        }
        return new Outcome("DECLINED");
    }

    public boolean areFriends(long me, long other) {
        return jdbc.sql("""
                        SELECT count(*) FROM friend_request
                        WHERE status = 'ACCEPTED'
                          AND ((requester_id = :me AND addressee_id = :o)
                            OR (requester_id = :o AND addressee_id = :me))""")
                .param("me", me).param("o", other).query(Long.class).single() > 0;
    }

    public List<ShelfItem> shelf(long me, long other) {
        if (me != other && !areFriends(me, other)) {
            throw new IllegalArgumentException("add this person as a friend to see their shelf");
        }
        return jdbc.sql("""
                        SELECT m.id, m.title, m.media_type, m.cover_url, l.status, r.score
                        FROM library_entry l JOIN media m ON m.id = l.media_id
                        LEFT JOIN rating r ON r.user_id = l.user_id AND r.media_id = l.media_id
                        WHERE l.user_id = :o ORDER BY l.updated_at DESC LIMIT 100""")
                .param("o", other)
                .query((rs, i) -> new ShelfItem(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), (Integer) rs.getObject(6)))
                .list();
    }

    /** Other users who rated the same titles highly, scored by shared favourites plus genre overlap. */
    public List<Match> matches(long me) {
        Set<String> myGenres = likedGenres(me);
        List<Object[]> rows = jdbc.sql("""
                        SELECT o.user_id, u.username, u.display_name, count(*) AS shared,
                               array_agg(m.title ORDER BY (me.score + o.score) DESC) AS titles
                        FROM rating me
                        JOIN rating o ON o.media_id = me.media_id AND o.user_id <> me.user_id
                        JOIN app_user u ON u.id = o.user_id
                        JOIN media m ON m.id = me.media_id
                        WHERE me.user_id = :me AND me.score >= 7 AND o.score >= 7
                        GROUP BY o.user_id, u.username, u.display_name
                        ORDER BY shared DESC, u.username
                        LIMIT 12""")
                .param("me", me)
                .query((rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), textArray(rs.getArray(5))})
                .list();
        List<Match> out = new ArrayList<>();
        for (Object[] r : rows) {
            long otherId = (Long) r[0];
            String username = (String) r[1];
            String display = (String) r[2];
            int shared = (Integer) r[3];
            @SuppressWarnings("unchecked")
            List<String> titles = (List<String>) r[4];
            List<String> topShared = titles.stream().limit(3).toList();
            Set<String> theirGenres = likedGenres(otherId);
            long overlap = theirGenres.stream().filter(myGenres::contains).count();
            double affinity = shared + 0.5 * overlap;
            List<String> picks = theirPicks(me, otherId);
            String blurb = blurb(topShared, myGenres, theirGenres);
            out.add(new Match(otherId, username, display, shared, topShared, picks,
                    Math.round(affinity * 100) / 100.0, blurb));
        }
        out.sort((a, b) -> Double.compare(b.affinity(), a.affinity()));
        return out;
    }

    private static List<String> textArray(java.sql.Array array) {
        try {
            if (array == null || array.getArray() == null) {
                return List.of();
            }
            Object raw = array.getArray();
            if (raw instanceof String[] strings) {
                return List.of(strings);
            }
            if (raw instanceof Object[] objects) {
                List<String> out = new ArrayList<>();
                for (Object item : objects) {
                    if (item != null) {
                        out.add(item.toString());
                    }
                }
                return out;
            }
        } catch (java.sql.SQLException ignored) {
            return List.of();
        }
        return List.of();
    }

    private List<String> theirPicks(long me, long other) {
        return jdbc.sql("""
                        SELECT m.title FROM rating o JOIN media m ON m.id = o.media_id
                        WHERE o.user_id = :o AND o.score >= 8
                          AND NOT EXISTS (SELECT 1 FROM rating me WHERE me.user_id = :me AND me.media_id = o.media_id)
                        ORDER BY o.score DESC, m.popularity DESC LIMIT 3""")
                .param("o", other).param("me", me).query(String.class).list();
    }

    private Set<String> likedGenres(long userId) {
        return new java.util.HashSet<>(jdbc.sql("""
                        SELECT DISTINCT g FROM rating r JOIN media m ON m.id = r.media_id,
                             unnest(m.genres) AS g
                        WHERE r.user_id = :u AND r.score >= 7""")
                .param("u", userId).query(String.class).list());
    }

    private static String blurb(List<String> shared, Set<String> myGenres, Set<String> theirGenres) {
        if (!shared.isEmpty()) {
            return "You both rated " + shared.get(0) + " highly.";
        }
        String common = myGenres.stream().filter(theirGenres::contains).findFirst().orElse(null);
        return common != null ? "You both lean towards " + common + "." : "Similar taste on Aniga.";
    }

    // --- Reading personality --------------------------------------------------

    private static final Map<String, String[]> AXES = Map.of(
            "Explorer", new String[]{"Action", "Adventure", "Fantasy", "Sci-Fi", "Mecha"},
            "Romantic", new String[]{"Romance", "Slice of Life", "Music", "Drama"},
            "Strategist", new String[]{"Mystery", "Psychological", "Thriller", "Supernatural", "Horror"},
            "Jester", new String[]{"Comedy", "Sports", "Ecchi", "Harem"});

    private static final Map<String, String> AXIS_LABEL = Map.of(
            "Explorer", "The Explorer",
            "Romantic", "The Romantic",
            "Strategist", "The Strategist",
            "Jester", "The Jester");

    public Personality personality(long me) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT g, count(*) FROM rating r JOIN media m ON m.id = r.media_id,
                             unnest(m.genres) AS g
                        WHERE r.user_id = :u AND r.score >= 6
                        GROUP BY g ORDER BY 2 DESC""")
                .param("u", me)
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));

        Map<String, Long> axisTotals = new LinkedHashMap<>();
        for (String axis : AXES.keySet()) {
            long total = 0;
            for (String genre : AXES.get(axis)) {
                total += counts.getOrDefault(genre, 0L);
            }
            axisTotals.put(axis, total);
        }
        long grand = axisTotals.values().stream().mapToLong(Long::longValue).sum();
        List<Trait> traits = new ArrayList<>();
        String top = null;
        long topVal = -1;
        for (var e : axisTotals.entrySet()) {
            int pct = grand == 0 ? 0 : (int) Math.round(100.0 * e.getValue() / grand);
            traits.add(new Trait(e.getKey(), pct));
            if (e.getValue() > topVal) {
                topVal = e.getValue();
                top = e.getKey();
            }
        }
        traits.sort((a, b) -> Integer.compare(b.score(), a.score()));
        List<String> topGenres = counts.keySet().stream().limit(5).toList();
        String type = grand == 0 ? "The Newcomer" : AXIS_LABEL.getOrDefault(top, "The Reader");
        String summary = grand == 0
                ? "Rate a few titles and we'll read your taste."
                : "You gravitate towards " + String.join(", ", topGenres.stream().limit(3).toList()) + ".";
        return new Personality(type, summary, traits, topGenres);
    }
}
