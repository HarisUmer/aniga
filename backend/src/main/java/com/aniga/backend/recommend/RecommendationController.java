package com.aniga.backend.recommend;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aniga.backend.media.Media;
import com.aniga.backend.media.MediaRepository;
import com.aniga.backend.user.RatingRepository;

@RestController
@RequestMapping("/api")
public class RecommendationController {

    private final MediaRepository media;
    private final RatingRepository ratings;
    private final RecommenderV2 v2;

    public RecommendationController(MediaRepository media, RatingRepository ratings, RecommenderV2 v2) {
        this.media = media;
        this.ratings = ratings;
        this.v2 = v2;
    }

    /** model=v2 (default): trained blend; model=v1: the original genre + TF-IDF scorer, kept for comparison. */
    @GetMapping("/users/{userId}/recommendations")
    public List<?> recommend(@PathVariable long userId,
                             @RequestParam(defaultValue = "10") int limit,
                             @RequestParam(required = false) String type,
                             @RequestParam(defaultValue = "v2") String model,
                             @RequestParam(defaultValue = "false") boolean track) {
        requireUser(userId);
        int n = Math.clamp(limit, 1, 60);
        if ("v1".equalsIgnoreCase(model)) {
            return new HybridRecommender(media.findAll()).recommend(ratings.scoresByMedia(userId), n, type);
        }
        return v2.recommend(userId, n, type, track);
    }

    @GetMapping("/users/{userId}/taste")
    public HybridRecommender.TasteProfile taste(@PathVariable long userId) {
        requireUser(userId);
        return new HybridRecommender(media.findAll()).taste(ratings.scoresByMedia(userId));
    }

    @GetMapping("/media/{mediaId}/similar")
    public List<Media> similar(@PathVariable long mediaId, @RequestParam(defaultValue = "12") int limit) {
        return v2.similar(mediaId, Math.clamp(limit, 1, 40));
    }

    @PostMapping("/model/reload")
    public Map<String, Object> reload() {
        v2.reload();
        return Map.of("recommenderV2", v2.available());
    }

    private void requireUser(long userId) {
        if (!ratings.userExists(userId)) {
            throw new NoSuchElementException("user " + userId + " not found");
        }
    }
}
