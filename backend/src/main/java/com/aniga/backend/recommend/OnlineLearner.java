package com.aniga.backend.recommend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.aniga.backend.media.CatalogCache;
import com.aniga.backend.media.Media;

import tools.jackson.databind.json.JsonMapper;

/**
 * The reinforcement part of the recommender: a per-user logistic model over title features that is updated the
 * moment a reward arrives (rating, drop, "not interested", a click on a recommendation).
 *
 * <pre>
 *   x       = L2-normalised features of the title: genres (1.0), media type (1.0), top tags (0.5)
 *   p       = sigmoid(2 · θ·x)                         predicted chance the user likes it
 *   update  θ_f += η · (r − p) · x_f,   n_f += x_f     one SGD step on the log-loss, r ∈ [0, 1]
 *   serve   online(i)  = θ·x_i                         added to the trained blend
 *           explore(i) = Σ_f x_f / sqrt(1 + n_f)        large when we know little about the title's features
 * </pre>
 *
 * Because every update moves only the features of that one title, rating a gore series 3/10 lowers "gore" and
 * "Horror" for this user immediately, while their other tastes stay where they were. η and the clamp were chosen
 * in the offline feedback simulation (ml/eval/simulate_feedback.py).
 */
@Service
public class OnlineLearner {

    static final double ETA = 1.2;
    static final double CLAMP = 3.0;
    static final int MAX_TAGS = 8;

    public record Weight(double weight, double n) {
    }

    private final JdbcClient jdbc;
    private final CatalogCache catalog;
    private final JsonMapper json = JsonMapper.builder().build();

    public OnlineLearner(JdbcClient jdbc, CatalogCache catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    public static Map<String, Double> features(Media m) {
        Map<String, Double> x = new LinkedHashMap<>();
        for (String g : m.genres()) {
            x.put("g:" + g, 1.0);
        }
        x.put("type:" + m.mediaType(), 1.0);
        m.tags().stream().limit(MAX_TAGS).forEach(t -> x.putIfAbsent("t:" + t, 0.5));
        double norm = Math.sqrt(x.values().stream().mapToDouble(v -> v * v).sum());
        x.replaceAll((k, v) -> v / Math.max(norm, 1e-9));
        return x;
    }

    public Map<String, Weight> weights(long userId) {
        Map<String, Weight> out = new HashMap<>();
        jdbc.sql("SELECT feature, weight, n FROM user_online_weight WHERE user_id = :u").param("u", userId)
                .query(rs -> {
                    out.put(rs.getString(1), new Weight(rs.getDouble(2), rs.getDouble(3)));
                });
        return out;
    }

    public static double score(Map<String, Weight> w, Map<String, Double> x) {
        double s = 0;
        for (var e : x.entrySet()) {
            Weight wf = w.get(e.getKey());
            if (wf != null) {
                s += wf.weight() * e.getValue();
            }
        }
        return s;
    }

    public static double uncertainty(Map<String, Weight> w, Map<String, Double> x) {
        double u = 0;
        for (var e : x.entrySet()) {
            Weight wf = w.get(e.getKey());
            u += e.getValue() / Math.sqrt(1 + (wf == null ? 0 : wf.n()));
        }
        return u;
    }

    /** One SGD step. Pure function so the unit tests and the simulation can check the exact rule. */
    static Map<String, Weight> step(Map<String, Weight> w, Map<String, Double> x, double reward) {
        double err = reward - predict(w, x);
        Map<String, Weight> changed = new LinkedHashMap<>();
        for (var e : x.entrySet()) {
            Weight old = w.getOrDefault(e.getKey(), new Weight(0, 0));
            double nw = Math.max(-CLAMP, Math.min(CLAMP, old.weight() + ETA * err * e.getValue()));
            changed.put(e.getKey(), new Weight(nw, old.n() + e.getValue()));
        }
        return changed;
    }

    @EventListener
    public void onFeedback(FeedbackEvent ev) {
        Media m = catalog.get().byId().get(ev.mediaId());
        if (m == null) {
            return;
        }
        Map<String, Weight> w = weights(ev.userId());
        Map<String, Double> x = features(m);
        double expected = predict(w, x);
        Map<String, Weight> changed = step(w, x, Math.max(0, Math.min(1, ev.reward())));
        Map<String, Double> deltas = new LinkedHashMap<>();
        for (var e : changed.entrySet()) {
            double before = w.getOrDefault(e.getKey(), new Weight(0, 0)).weight();
            jdbc.sql("""
                            INSERT INTO user_online_weight (user_id, feature, weight, n, updated_at)
                            VALUES (:u, :f, :w, :n, now())
                            ON CONFLICT (user_id, feature) DO UPDATE SET weight = EXCLUDED.weight, n = EXCLUDED.n,
                                updated_at = now()""")
                    .param("u", ev.userId()).param("f", e.getKey())
                    .param("w", (float) e.getValue().weight()).param("n", (float) e.getValue().n()).update();
            double d = e.getValue().weight() - before;
            if (Math.abs(d) >= 0.01) {
                deltas.put(e.getKey(), Math.round(d * 1000) / 1000.0);
            }
        }
        // Logged even when nothing moved: "we expected this, so nothing to learn" is part of the explanation.
        jdbc.sql("""
                        INSERT INTO taste_change (user_id, media_id, cause, reward, expected, deltas)
                        VALUES (:u, :m, :c, :r, :e, :d::jsonb)""")
                .param("u", ev.userId()).param("m", ev.mediaId()).param("c", ev.cause())
                .param("r", (float) ev.reward()).param("e", (float) expected)
                .param("d", json.writeValueAsString(deltas)).update();
    }

    /** p(like) the model assigns before seeing the reward. */
    static double predict(Map<String, Weight> w, Map<String, Double> x) {
        return 1 / (1 + Math.exp(-2 * score(w, x)));
    }

    public record Change(Long mediaId, String title, String cause, double reward, Double expected,
                         Map<String, Double> deltas, String at) {
    }

    @SuppressWarnings("unchecked")
    public List<Change> recentChanges(long userId, int limit) {
        Map<Long, Media> byId = catalog.get().byId();
        List<Change> out = new ArrayList<>();
        jdbc.sql("""
                        SELECT media_id, cause, reward, expected, deltas::text, created_at::text FROM taste_change
                        WHERE user_id = :u ORDER BY created_at DESC, id DESC LIMIT :n""")
                .param("u", userId).param("n", limit)
                .query(rs -> {
                    Long mid = (Long) rs.getObject(1);
                    Media m = mid == null ? null : byId.get(mid);
                    Object exp = rs.getObject(4);
                    out.add(new Change(mid, m == null ? null : m.title(), rs.getString(2), rs.getDouble(3),
                            exp == null ? null : Math.round(((Number) exp).doubleValue() * 1000) / 1000.0,
                            json.readValue(rs.getString(5), LinkedHashMap.class), rs.getString(6)));
                });
        return out;
    }

    public void reset(long userId) {
        jdbc.sql("DELETE FROM user_online_weight WHERE user_id = :u").param("u", userId).update();
        jdbc.sql("DELETE FROM taste_change WHERE user_id = :u").param("u", userId).update();
    }
}
