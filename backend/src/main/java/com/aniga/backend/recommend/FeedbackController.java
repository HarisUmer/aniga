package com.aniga.backend.recommend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
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

import com.aniga.backend.media.CatalogCache;
import com.aniga.backend.media.Media;
import com.aniga.backend.user.RatingRepository;

/**
 * The user's side of the feedback loop: react to a recommendation, see what the model believes about you,
 * and correct it (boost or mute a genre, tag or media type; reset what was learned online).
 */
@RestController
@RequestMapping("/api/users/{userId}")
public class FeedbackController {

    static final Set<String> KINDS = Set.of("LIKE", "NOT_INTERESTED", "CLICK", "CLEAR");
    static final Pattern FEATURE = Pattern.compile("^(g|t|type):[\\p{L}\\p{N} &'\\-]{1,60}$");

    public record FeedbackBody(long mediaId, String kind) {
    }

    public record PrefBody(String feature, int weight) {
    }

    public record FeatureView(String feature, String label, String kind, double history, double online, int pref,
                              double effective, double evidence) {
    }

    public record FeedbackItem(Media media, String kind) {
    }

    public record TasteView(int signals, int rewards, int exploreSlots, List<FeatureView> features,
                            List<OnlineLearner.Change> changes, List<FeedbackItem> feedback) {
    }

    private final RatingRepository ratings;
    private final FeedbackRepository feedback;
    private final OnlineLearner learner;
    private final UserSignals signals;
    private final CatalogCache catalog;
    private final ApplicationEventPublisher events;

    public FeedbackController(RatingRepository ratings, FeedbackRepository feedback, OnlineLearner learner,
                              UserSignals signals, CatalogCache catalog, ApplicationEventPublisher events) {
        this.ratings = ratings;
        this.feedback = feedback;
        this.learner = learner;
        this.signals = signals;
        this.catalog = catalog;
        this.events = events;
    }

    /**
     * LIKE (reward 1) and NOT_INTERESTED (reward 0) are explicit; CLICK on a recommended title is a weak positive
     * (0.7); CLEAR undoes a button press (the learned weights keep the step, like any SGD).
     */
    @PostMapping("/feedback")
    @Transactional
    public Map<String, Object> react(@PathVariable long userId, @RequestBody FeedbackBody body) {
        requireUser(userId);
        String kind = body.kind() == null ? "" : body.kind().toUpperCase();
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("kind must be one of " + KINDS);
        }
        if (!catalog.get().byId().containsKey(body.mediaId())) {
            throw new NoSuchElementException("media " + body.mediaId() + " not found");
        }
        boolean learned = true;
        switch (kind) {
            case "LIKE" -> {
                feedback.setFeedback(userId, body.mediaId(), kind);
                feedback.event(userId, body.mediaId(), kind, 1f);
                events.publishEvent(new FeedbackEvent(userId, body.mediaId(), 1.0, "liked a recommendation"));
            }
            case "NOT_INTERESTED" -> {
                feedback.setFeedback(userId, body.mediaId(), kind);
                feedback.event(userId, body.mediaId(), kind, 0f);
                events.publishEvent(new FeedbackEvent(userId, body.mediaId(), 0.0, "not interested"));
            }
            case "CLICK" -> {
                learned = feedback.recordClick(userId, body.mediaId());
                feedback.event(userId, body.mediaId(), kind, 1f);
                if (learned) {
                    events.publishEvent(new FeedbackEvent(userId, body.mediaId(), 0.7, "opened a recommendation"));
                }
            }
            default -> {
                feedback.clearFeedback(userId, body.mediaId());
                learned = false;
            }
        }
        return Map.of("mediaId", body.mediaId(), "kind", kind, "learned", learned);
    }

    @PutMapping("/prefs")
    public Map<String, Object> pref(@PathVariable long userId, @RequestBody PrefBody body) {
        requireUser(userId);
        if (body.feature() == null || !FEATURE.matcher(body.feature()).matches()) {
            throw new IllegalArgumentException("feature must look like g:Romance, t:time-skip or type:MANHWA");
        }
        if (body.weight() < -1 || body.weight() > 1) {
            throw new IllegalArgumentException("weight must be -1 (mute), 0 (clear) or 1 (boost)");
        }
        feedback.setPref(userId, body.feature(), body.weight());
        return Map.of("feature", body.feature(), "weight", body.weight());
    }

    @DeleteMapping("/taste/online")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetOnline(@PathVariable long userId) {
        requireUser(userId);
        learner.reset(userId);
    }

    /**
     * What the recommender believes, per feature:
     *   history   = shrunk average signal of the titles you rated/tracked that have the feature
     *   online    = weight learned from rewards (moves with every rating and button press)
     *   pref      = what you set by hand
     *   effective = history + online + pref, what actually ranks titles
     */
    @GetMapping("/taste/v2")
    public TasteView taste(@PathVariable long userId) {
        requireUser(userId);
        Map<Long, Media> byId = catalog.get().byId();
        Map<Long, UserSignals.Signal> sig = signals.load(userId);
        Map<String, double[]> hist = new HashMap<>();
        for (UserSignals.Signal s : sig.values()) {
            Media m = byId.get(s.mediaId());
            if (m == null) {
                continue;
            }
            OnlineLearner.features(m).forEach((f, x) -> {
                double[] a = hist.computeIfAbsent(f, k -> new double[2]);
                a[0] += s.value() * x;
                a[1] += x;
            });
        }
        Map<String, OnlineLearner.Weight> online = learner.weights(userId);
        Map<String, Integer> prefs = feedback.prefs(userId);
        Set<String> keys = new java.util.HashSet<>(hist.keySet());
        keys.addAll(online.keySet());
        keys.addAll(prefs.keySet());
        List<FeatureView> features = new ArrayList<>();
        for (String f : keys) {
            double[] a = hist.getOrDefault(f, new double[2]);
            double h = a[0] / (a[1] + 1.0);
            OnlineLearner.Weight w = online.get(f);
            double on = w == null ? 0 : w.weight();
            int p = prefs.getOrDefault(f, 0);
            String kind = f.startsWith("g:") ? "genre" : f.startsWith("type:") ? "type" : "tag";
            features.add(new FeatureView(f, RecommenderV2.label(f), kind, round(h), round(on), p,
                    round(h + on + p * 0.5), round(a[1] + (w == null ? 0 : w.n()))));
        }
        features.sort(Comparator.comparingDouble((FeatureView v) -> v.pref() != 0 ? 10 : 0)
                .thenComparingDouble(v -> Math.abs(v.effective())).reversed());
        int rewards = feedback.rewardCount(userId);
        List<FeedbackItem> fb = feedback.feedback(userId).entrySet().stream()
                .filter(e -> byId.containsKey(e.getKey()))
                .map(e -> new FeedbackItem(byId.get(e.getKey()), e.getValue())).toList();
        return new TasteView(sig.size(), rewards, rewards < 30 ? 2 : 1,
                features.stream().limit(60).toList(), learner.recentChanges(userId, 12), fb);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private void requireUser(long userId) {
        if (!ratings.userExists(userId)) {
            throw new NoSuchElementException("user " + userId + " not found");
        }
    }
}
