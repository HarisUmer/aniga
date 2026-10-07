package com.aniga.backend.recommend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.aniga.backend.mangadex.Json;
import com.aniga.backend.media.CatalogCache;
import com.aniga.backend.media.Media;
import com.aniga.backend.media.MediaRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * Serves the model trained by ml/train/train_recommenders.py on 500k synthetic users, then adapts it online:
 * <pre>
 *   base   = Σ w_k · zscore(part_k)   parts = EASE item-item, two-tower, V1 content, popularity, media-type prior
 *   final  = base + ONLINE_W · θ_u·x_i  (OnlineLearner: per-user weights updated by every reward)
 *               + preference boosts / mutes the user set by hand
 *               − fatigue for titles shown ≥ 3 times and never opened
 *   list   = franchise-diversified top-k, with 1–2 exploration slots (Thompson-style sample on uncertainty)
 * </pre>
 * Both learned parts are inductive (built from the user's own history), so a user who rated three titles a
 * minute ago gets personalised results without retraining. Weights come from the validation split.
 */
@Service
public class RecommenderV2 {

    /** slot: EXPLOIT (best guess) or EXPLORE (deliberately testing a taste we know little about). */
    public record Recommendation(Media media, double score, double genreScore, double textScore,
                                 Map<String, Double> parts, List<String> reasons, Long becauseId, String becauseTitle,
                                 String slot) {
    }

    static final double ONLINE_W = 0.9;
    // ~0.3σ of the blend: a boosted genre climbs a few places and gets a fair share, it does not take over the feed.
    static final double PREF_BOOST = 0.3;
    static final double PREF_MUTE = -4.0;
    // Feedback simulation: β=0 43.8, 0.8 42.9, 1.6 42.8 cumulative reward; the simulated users have no tastes
    // outside the known features (where exploration pays), so it is kept but small.
    static final double EXPLORE_BETA = 0.8;

    /** blendShort is used for users with at most shortMax titles (tuned separately; cold start differs). */
    private record Model(Map<Long, float[]> vectors, Map<Long, Map<Long, Double>> neighbors,
                         Map<String, Double> blend, Map<String, Double> blendShort, int shortMax,
                         double[][] wPos, double[][] wNeg) {
    }

    private final JdbcClient jdbc;
    private final MediaRepository media;
    private final CatalogCache catalog;
    private final UserSignals signals;
    private final OnlineLearner learner;
    private final FeedbackRepository feedback;
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile Model model;
    private record Hybrid(CatalogCache.Snapshot snap, HybridRecommender recommender) {
    }

    private volatile Hybrid hybrid;

    public RecommenderV2(JdbcClient jdbc, MediaRepository media, CatalogCache catalog, UserSignals signals,
                         OnlineLearner learner, FeedbackRepository feedback) {
        this.jdbc = jdbc;
        this.media = media;
        this.catalog = catalog;
        this.signals = signals;
        this.learner = learner;
        this.feedback = feedback;
    }

    /** TF-IDF over the catalog is rebuilt only when the catalog snapshot changes. */
    HybridRecommender hybrid(CatalogCache.Snapshot snap) {
        Hybrid h = hybrid;
        if (h == null || h.snap() != snap) {
            h = new Hybrid(snap, new HybridRecommender(snap.all()));
            hybrid = h;
        }
        return h.recommender();
    }

    public boolean available() {
        return model() != null;
    }

    public synchronized void reload() {
        model = null;
        model();
    }

    @SuppressWarnings("unchecked")
    private Model model() {
        Model m = model;
        if (m != null) {
            return m;
        }
        boolean exists = jdbc.sql("SELECT to_regclass('public.model_meta') IS NOT NULL").query(Boolean.class).single();
        if (!exists) {
            return null;
        }
        var meta = jdbc.sql("SELECT payload::text FROM model_meta WHERE name = 'recommender_v2'").query(String.class).optional();
        if (meta.isEmpty()) {
            return null;
        }
        Map<String, Object> payload = json.readValue(meta.get(), Map.class);
        Map<String, Double> blend = new HashMap<>();
        Json.map(payload.get("blend")).forEach((k, v) -> blend.put(k, ((Number) v).doubleValue()));
        Map<String, Double> blendShort = new HashMap<>(blend);
        if (payload.get("blend_short") != null) {
            blendShort.clear();
            Json.map(payload.get("blend_short")).forEach((k, v) -> blendShort.put(k, ((Number) v).doubleValue()));
        }
        int shortMax = payload.get("short_max") == null ? 0 : ((Number) payload.get("short_max")).intValue();
        Map<Long, float[]> vectors = new HashMap<>();
        jdbc.sql("SELECT media_id, vec FROM item_vector").query(rs -> {
            Float[] arr = (Float[]) rs.getArray(2).getArray();
            float[] v = new float[arr.length];
            for (int i = 0; i < arr.length; i++) {
                v[i] = arr[i];
            }
            vectors.put(rs.getLong(1), v);
        });
        Map<Long, Map<Long, Double>> neighbors = new HashMap<>();
        jdbc.sql("SELECT media_id, neighbor_id, weight FROM item_neighbor").query(rs -> {
            neighbors.computeIfAbsent(rs.getLong(1), k -> new HashMap<>()).put(rs.getLong(2), rs.getDouble(3));
        });
        m = new Model(vectors, neighbors, blend, blendShort, shortMax, matrix(payload.get("w_pos")),
                matrix(payload.get("w_neg")));
        model = m;
        return m;
    }

    private static double[][] matrix(Object o) {
        List<Object> rows = Json.list(o);
        double[][] out = new double[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            List<Object> r = Json.list(rows.get(i));
            out[i] = r.stream().mapToDouble(x -> ((Number) x).doubleValue()).toArray();
        }
        return out;
    }

    public List<Recommendation> recommend(long userId, int limit, String typeFilter) {
        return recommend(userId, limit, typeFilter, false);
    }

    /** track = the list is about to be shown, so count impressions (feeds fatigue and click-through rewards). */
    public List<Recommendation> recommend(long userId, int limit, String typeFilter, boolean track) {
        CatalogCache.Snapshot snap = catalog.get();
        List<Media> all = snap.all();
        Map<Long, Media> byId = snap.byId();
        Map<Long, UserSignals.Signal> sig = signals.load(userId);
        Map<String, OnlineLearner.Weight> online = learner.weights(userId);
        Map<String, Integer> prefs = feedback.prefs(userId);
        Map<Long, FeedbackRepository.Impression> seen = feedback.impressions(userId);
        int rewards = feedback.rewardCount(userId);
        List<Media> candidates = all.stream()
                .filter(m -> !sig.containsKey(m.id()))
                .filter(m -> typeFilter == null || typeFilter.isBlank() || m.mediaType().equalsIgnoreCase(typeFilter))
                .toList();
        Model mdl = model();
        int n = candidates.size();
        List<Recommendation> base = new ArrayList<>(n);

        if (sig.isEmpty()) {
            double[] p = new double[n];
            for (int c = 0; c < n; c++) {
                Media m = candidates.get(c);
                p[c] = Math.log1p(m.popularity()) + 0.02 * (m.averageScore() == null ? 60 : m.averageScore());
            }
            double[] zp = zscore(p);
            for (int c = 0; c < n; c++) {
                base.add(new Recommendation(candidates.get(c), zp[c], 0, 0, new HashMap<>(Map.of("pop", round(zp[c]))),
                        new ArrayList<>(List.of("popular on Aniga — rate or track a few titles to personalise this")),
                        null, null, "EXPLOIT"));
            }
            return finish(userId, base, online, prefs, seen, rewards, limit, track);
        }

        // V1 content part, fed with pseudo-scores so unrated library entries count too.
        Map<Long, Integer> pseudo = new HashMap<>();
        sig.values().forEach(s -> pseudo.put(s.mediaId(),
                s.score() != null ? s.score() : (int) Math.round(Math.max(1, Math.min(10, 5.5 + 4.5 * s.value())))));
        var v1 = hybrid(snap).recommend(pseudo, all.size(), typeFilter).stream()
                .collect(Collectors.toMap(r -> r.media().id(), Function.identity()));

        // Media-type prior: log share of the user's history per type (an anime-only watcher should not get manhua).
        Map<String, Integer> typeCount = new HashMap<>();
        sig.keySet().forEach(id -> {
            Media m = byId.get(id);
            if (m != null) {
                typeCount.merge(m.mediaType(), 1, Integer::sum);
            }
        });
        double histSize = typeCount.values().stream().mapToInt(Integer::intValue).sum();
        // Personalised popularity: how mainstream the user's history is × how popular the candidate is.
        double[] logPop = all.stream().mapToDouble(m -> Math.log1p(m.popularity())).toArray();
        double popMean = java.util.Arrays.stream(logPop).average().orElse(0);
        double popSd = Math.sqrt(java.util.Arrays.stream(logPop).map(v -> (v - popMean) * (v - popMean)).average().orElse(1));
        double mainstream = sig.keySet().stream().map(byId::get).filter(java.util.Objects::nonNull)
                .mapToDouble(m -> (Math.log1p(m.popularity()) - popMean) / Math.max(popSd, 1e-9)).average().orElse(0);

        double[] content = new double[n];
        double[] pop = new double[n];
        double[] ease = new double[n];
        double[] tower = new double[n];
        double[] type = new double[n];
        double[] mpop = new double[n];
        long[] anchor = new long[n];
        double[] user = mdl == null ? null : userVector(mdl, sig);
        for (int c = 0; c < n; c++) {
            Media m = candidates.get(c);
            var r = v1.get(m.id());
            content[c] = r == null ? 0 : r.score();
            pop[c] = Math.log1p(m.popularity());
            type[c] = Math.log((typeCount.getOrDefault(m.mediaType(), 0) + 0.5) / (histSize + 2.0));
            mpop[c] = mainstream * (pop[c] - popMean) / Math.max(popSd, 1e-9);
            if (mdl != null) {
                double best = 0;
                for (UserSignals.Signal s : sig.values()) {
                    Double w = mdl.neighbors().getOrDefault(s.mediaId(), Map.of()).get(m.id());
                    if (w != null) {
                        double contrib = s.value() * w;
                        ease[c] += contrib;
                        if (contrib > best) {
                            best = contrib;
                            anchor[c] = s.mediaId();
                        }
                    }
                }
                float[] v = mdl.vectors().get(m.id());
                if (v != null && user != null) {
                    for (int d = 0; d < v.length; d++) {
                        tower[c] += v[d] * user[d];
                    }
                }
            }
        }
        Map<String, double[]> parts = new HashMap<>();
        parts.put("content", zscore(content));
        parts.put("pop", zscore(pop));
        parts.put("type", zscore(type));
        parts.put("mpop", zscore(mpop));
        Map<String, Double> weights = new HashMap<>();
        if (mdl == null) {
            weights.put("content", 1.0);
        } else {
            parts.put("ease", zscore(ease));
            parts.put("tower", zscore(tower));
            weights.putAll(sig.size() <= mdl.shortMax() ? mdl.blendShort() : mdl.blend());
        }

        for (int c = 0; c < n; c++) {
            Media m = candidates.get(c);
            double score = 0;
            Map<String, Double> contrib = new HashMap<>();
            for (var e : weights.entrySet()) {
                double[] p = parts.get(e.getKey());
                if (p != null && e.getValue() != 0) {
                    double v = e.getValue() * p[c];
                    contrib.put(e.getKey(), round(v));
                    score += v;
                }
            }
            var r = v1.get(m.id());
            List<String> reasons = new ArrayList<>();
            Long because = anchor[c] == 0 ? null : anchor[c];
            String becauseTitle = because == null || !byId.containsKey(because) ? null : byId.get(because).title();
            if (becauseTitle != null) {
                UserSignals.Signal s = sig.get(because);
                String verb = "COMPLETED".equals(s.status()) ? "finished" : s.score() != null ? "rated " + s.score()
                        : s.status() == null && "LIKE".equals(s.feedback()) ? "liked it" : "are following";
                reasons.add("readers who loved " + becauseTitle + " (you " + verb + ") go on to this");
            }
            if (r != null) {
                reasons.addAll(r.reasons().stream().limit(3).toList());
            }
            base.add(new Recommendation(m, score, r == null ? 0 : r.genreScore(), r == null ? 0 : r.textScore(),
                    contrib, reasons, because, becauseTitle, "EXPLOIT"));
        }
        return finish(userId, base, online, prefs, seen, rewards, limit, track);
    }

    /** Online weights, explicit preferences and fatigue on top of the trained score; then diversify and explore. */
    private List<Recommendation> finish(long userId, List<Recommendation> base, Map<String, OnlineLearner.Weight> online,
                                        Map<String, Integer> prefs, Map<Long, FeedbackRepository.Impression> seen,
                                        int rewards, int limit, boolean track) {
        List<Recommendation> scored = new ArrayList<>(base.size());
        Map<Long, Double> unc = new HashMap<>();
        for (Recommendation r : base) {
            Map<String, Double> x = OnlineLearner.features(r.media());
            double on = ONLINE_W * OnlineLearner.score(online, x);
            double pref = 0;
            String boosted = null;
            for (String f : x.keySet()) {
                Integer p = prefs.get(f);
                if (p != null && p > 0) {
                    pref += PREF_BOOST;
                    boosted = boosted == null ? label(f) : boosted;
                } else if (p != null && p < 0) {
                    pref += PREF_MUTE;
                }
            }
            FeedbackRepository.Impression imp = seen.get(r.media().id());
            double fatigue = imp == null || imp.clicked() > 0 || imp.shown() < 3 ? 0 : -Math.min(0.9, 0.15 * (imp.shown() - 2));
            Map<String, Double> parts = new HashMap<>(r.parts());
            List<String> reasons = new ArrayList<>(r.reasons());
            if (on != 0) {
                parts.put("online", round(on));
                String top = topFeature(online, x);
                // Only when feedback clearly moved this title; otherwise every row would say the same thing.
                if (on > 0.3 && top != null) {
                    reasons.add("your recent ratings and feedback lean towards " + top);
                }
            }
            if (pref != 0) {
                parts.put("pref", round(pref));
                if (boosted != null) {
                    reasons.add(0, "you asked for more " + boosted);
                }
            }
            if (fatigue != 0) {
                parts.put("fatigue", round(fatigue));
            }
            unc.put(r.media().id(), OnlineLearner.uncertainty(online, x));
            scored.add(new Recommendation(r.media(), round(r.score() + on + pref + fatigue), r.genreScore(),
                    r.textScore(), parts, reasons, r.becauseId(), r.becauseTitle(), "EXPLOIT"));
        }
        scored.sort(Comparator.comparingDouble(Recommendation::score).reversed());
        int slots = limit < 6 ? 0 : rewards < 30 ? 2 : 1;
        List<Recommendation> picked = new ArrayList<>(diversify(scored, limit - slots));
        explore(userId, scored, picked, unc, online, rewards, slots);
        if (track && !picked.isEmpty()) {
            feedback.recordImpressions(userId, picked.stream().map(r -> r.media().id()).toList(),
                    picked.stream().map(Recommendation::slot).toList());
        }
        return picked;
    }

    /**
     * Exploration: from the plausible part of the ranking (top 25%, nothing muted), sample
     * score + β·uncertainty·N(0,1) and take the best — Thompson sampling with a Gaussian posterior whose width
     * shrinks as the user gives feedback on those features. Seeded per user, day and reward count, so the list is
     * stable on refresh and changes once the user reacts.
     */
    void explore(long userId, List<Recommendation> ranked, List<Recommendation> picked, Map<Long, Double> unc,
                 Map<String, OnlineLearner.Weight> online, int rewards, int slots) {
        if (slots == 0 || ranked.size() <= picked.size()) {
            return;
        }
        java.util.Set<Long> taken = new java.util.HashSet<>();
        java.util.Set<String> franchises = new java.util.HashSet<>();
        picked.forEach(r -> {
            taken.add(r.media().id());
            franchises.add(franchise(r.media().title()));
        });
        int poolSize = Math.max(40, ranked.size() / 4);
        java.util.Random rnd = new java.util.Random(userId * 1_000_003L + java.time.LocalDate.now().toEpochDay() * 31 + rewards);
        List<Recommendation> pool = ranked.stream().limit(poolSize)
                .filter(r -> !taken.contains(r.media().id()) && r.parts().getOrDefault("pref", 0.0) >= 0)
                .toList();
        List<double[]> samples = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            Recommendation r = pool.get(i);
            samples.add(new double[]{r.score() + EXPLORE_BETA * unc.getOrDefault(r.media().id(), 1.0) * rnd.nextGaussian(), i});
        }
        samples.sort((a, b) -> Double.compare(b[0], a[0]));
        int[] positions = {3, 7};
        int added = 0;
        for (double[] s : samples) {
            if (added >= slots) {
                break;
            }
            Recommendation r = pool.get((int) s[1]);
            if (!franchises.add(franchise(r.media().title()))) {
                continue;
            }
            List<String> reasons = new ArrayList<>();
            reasons.add("exploring — we know little about how you feel about "
                    + leastKnown(online, OnlineLearner.features(r.media())) + "; tell us if it lands");
            reasons.addAll(r.reasons());
            Recommendation e = new Recommendation(r.media(), r.score(), r.genreScore(), r.textScore(), r.parts(),
                    reasons, r.becauseId(), r.becauseTitle(), "EXPLORE");
            picked.add(Math.min(positions[added], picked.size()), e);
            added++;
        }
    }

    private static String topFeature(Map<String, OnlineLearner.Weight> online, Map<String, Double> x) {
        String best = null;
        double bv = 0;
        for (var e : x.entrySet()) {
            OnlineLearner.Weight w = online.get(e.getKey());
            if (w != null && w.weight() * e.getValue() > bv) {
                bv = w.weight() * e.getValue();
                best = e.getKey();
            }
        }
        return best == null ? null : label(best);
    }

    /** The title's genre (else tag) with the least feedback evidence: what this exploration slot is testing. */
    static String leastKnown(Map<String, OnlineLearner.Weight> online, Map<String, Double> x) {
        String best = null;
        double bn = Double.MAX_VALUE;
        for (String prefix : new String[] {"g:", "t:"}) {
            for (String f : x.keySet()) {
                if (f.startsWith(prefix)) {
                    OnlineLearner.Weight w = online.get(f);
                    double n = w == null ? 0 : w.n();
                    if (n < bn) {
                        bn = n;
                        best = f;
                    }
                }
            }
            if (best != null && bn < 1) {
                break;
            }
        }
        return best == null ? "this kind of title" : label(best);
    }

    /** g:Romance → Romance, t:time-skip → time skip, type:MANHWA → manhwa */
    static String label(String feature) {
        int i = feature.indexOf(':');
        String v = i < 0 ? feature : feature.substring(i + 1);
        return feature.startsWith("type:") ? v.toLowerCase(java.util.Locale.ROOT) : v.replace('-', ' ');
    }

    /**
     * Sequels share neighbours and embeddings, so a raw top-10 is often "Season 2, Season 3, Final Season".
     * Greedy rerank: one title per franchise in the first pass, then fill from what was skipped.
     */
    static List<Recommendation> diversify(List<Recommendation> ranked, int limit) {
        List<Recommendation> picked = new ArrayList<>();
        List<Recommendation> skipped = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Recommendation r : ranked) {
            if (picked.size() >= limit) {
                break;
            }
            if (seen.add(franchise(r.media().title()))) {
                picked.add(r);
            } else {
                skipped.add(r);
            }
        }
        for (Recommendation r : skipped) {
            if (picked.size() >= limit) {
                break;
            }
            picked.add(r);
        }
        return picked;
    }

    static String franchise(String title) {
        if (title == null) {
            return "";
        }
        String t = title.toLowerCase(java.util.Locale.ROOT)
                .split(":| season| part| the movie| movie| final| \\d| [ivx]+$| 2nd| 3rd| \\d+th")[0]
                .replaceAll("[^\\p{L}\\p{N} ]", "").trim();
        String[] words = t.split("\\s+");
        return String.join(" ", java.util.Arrays.copyOf(words, Math.min(3, words.length)));
    }

    /** "More like this" for a detail page: EASE row plus two-tower cosine. */
    public List<Media> similar(long mediaId, int limit) {
        Model mdl = model();
        if (mdl == null) {
            return List.of();
        }
        Map<Long, Double> score = new HashMap<>(mdl.neighbors().getOrDefault(mediaId, Map.of()));
        float[] v = mdl.vectors().get(mediaId);
        if (v != null) {
            mdl.vectors().forEach((id, w) -> {
                if (id != mediaId) {
                    double dot = 0;
                    for (int d = 0; d < v.length; d++) {
                        dot += v[d] * w[d];
                    }
                    score.merge(id, 0.05 * dot, Double::sum);
                }
            });
        }
        List<Long> ids = score.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit).map(Map.Entry::getKey).toList();
        Map<Long, Media> found = media.byIds(ids).stream().collect(Collectors.toMap(Media::id, Function.identity()));
        return ids.stream().map(found::get).filter(m -> m != null).toList();
    }

    private static double[] userVector(Model mdl, Map<Long, UserSignals.Signal> sig) {
        int dim = mdl.wPos().length;
        double[] pos = new double[dim];
        double[] neg = new double[dim];
        double pw = 0;
        double nw = 0;
        for (UserSignals.Signal s : sig.values()) {
            float[] v = mdl.vectors().get(s.mediaId());
            if (v == null || s.value() == 0) {
                continue;
            }
            // Recent signals pull harder, so a taste that changed this month outweighs one from last year.
            double w = Math.abs(s.value()) * UserSignals.recency(s.ageDays());
            double[] acc = s.value() > 0 ? pos : neg;
            for (int d = 0; d < dim; d++) {
                acc[d] += w * v[d];
            }
            if (s.value() > 0) {
                pw += w;
            } else {
                nw += w;
            }
        }
        double[] u = new double[dim];
        for (int i = 0; i < dim; i++) {
            double a = 0;
            for (int d = 0; d < dim; d++) {
                a += mdl.wPos()[i][d] * (pw == 0 ? 0 : pos[d] / pw) - mdl.wNeg()[i][d] * (nw == 0 ? 0 : neg[d] / nw);
            }
            u[i] = a;
        }
        double norm = 0;
        for (double x : u) {
            norm += x * x;
        }
        norm = Math.sqrt(Math.max(norm, 1e-12));
        for (int i = 0; i < dim; i++) {
            u[i] /= norm;
        }
        return u;
    }

    private static double[] zscore(double[] x) {
        double mean = 0;
        for (double v : x) {
            mean += v;
        }
        mean /= Math.max(x.length, 1);
        double var = 0;
        for (double v : x) {
            var += (v - mean) * (v - mean);
        }
        double sd = Math.sqrt(var / Math.max(x.length, 1));
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            out[i] = sd < 1e-9 ? 0 : (x[i] - mean) / sd;
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
