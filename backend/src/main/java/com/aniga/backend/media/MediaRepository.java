package com.aniga.backend.media;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.annotation.JsonRawValue;

@Repository
public class MediaRepository {

    static final String COLUMNS =
            "id, media_type, title, synopsis, genres, tags, episodes, chapters, start_year, popularity, "
                    + "cover_url, banner_url, accent_color, average_score, native_title, format, status, season, makers";

    /** gallery/characters are JSONB strings passed through untouched. */
    public record Detail(Media media, String romajiTitle, Integer volumes, Integer episodeMinutes, Integer anilistId,
                         Integer malId, @JsonRawValue String gallery, @JsonRawValue String characters) {
    }

    private final JdbcClient jdbc;

    public MediaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Media> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM media ORDER BY popularity DESC, id")
                .query(MediaRepository::map)
                .list();
    }

    public Optional<Detail> detail(long id) {
        return jdbc.sql("SELECT " + COLUMNS + ", romaji_title, volumes, episode_minutes, anilist_id, mal_id, "
                        + "gallery::text AS gallery, characters::text AS characters FROM media WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> new Detail(map(rs, i), rs.getString("romaji_title"),
                        (Integer) rs.getObject("volumes"), (Integer) rs.getObject("episode_minutes"),
                        (Integer) rs.getObject("anilist_id"), (Integer) rs.getObject("mal_id"),
                        rs.getString("gallery"), rs.getString("characters")))
                .optional();
    }

    public List<Media> byIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM media WHERE id IN (:ids)")
                .param("ids", ids)
                .query(MediaRepository::map)
                .list();
    }

    public Optional<Media> byAnilistId(int anilistId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM media WHERE anilist_id = :a")
                .param("a", anilistId).query(MediaRepository::map).optional();
    }

    public List<Media> search(String query, String type, String genre, String sort, int limit, int offset) {
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM media WHERE 1=1");
        if (query != null && !query.isBlank()) {
            sql.append(" AND (title ILIKE :q OR romaji_title ILIKE :q OR native_title ILIKE :q)");
        }
        if (type != null && !type.isBlank()) {
            sql.append(" AND media_type = :type");
        }
        if (genre != null && !genre.isBlank()) {
            sql.append(" AND :genre = ANY(genres)");
        }
        sql.append(switch (sort == null ? "" : sort) {
            case "score" -> " ORDER BY average_score DESC NULLS LAST, id";
            case "new" -> " ORDER BY start_year DESC NULLS LAST, popularity DESC, id";
            case "title" -> " ORDER BY title, id";
            default -> " ORDER BY popularity DESC, id";
        });
        sql.append(" LIMIT :limit OFFSET :offset");
        var stmt = jdbc.sql(sql.toString()).param("limit", limit).param("offset", offset);
        if (query != null && !query.isBlank()) {
            stmt = stmt.param("q", "%" + query.trim() + "%");
        }
        if (type != null && !type.isBlank()) {
            stmt = stmt.param("type", type.toUpperCase());
        }
        if (genre != null && !genre.isBlank()) {
            stmt = stmt.param("genre", genre);
        }
        return stmt.query(MediaRepository::map).list();
    }

    public List<String> genres() {
        return jdbc.sql("SELECT g FROM (SELECT unnest(genres) AS g FROM media) x GROUP BY g ORDER BY count(*) DESC")
                .query(String.class).list();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM media").query(Long.class).single();
    }

    public static Media map(ResultSet rs, int row) throws SQLException {
        return new Media(
                rs.getLong("id"),
                rs.getString("media_type"),
                rs.getString("title"),
                rs.getString("synopsis"),
                textArray(rs.getArray("genres")),
                textArray(rs.getArray("tags")),
                (Integer) rs.getObject("episodes"),
                (Integer) rs.getObject("chapters"),
                (Integer) rs.getObject("start_year"),
                rs.getInt("popularity"),
                rs.getString("cover_url"),
                rs.getString("banner_url"),
                rs.getString("accent_color"),
                (Integer) rs.getObject("average_score"),
                rs.getString("native_title"),
                rs.getString("format"),
                rs.getString("status"),
                rs.getString("season"),
                textArray(rs.getArray("makers")));
    }

    private static List<String> textArray(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
