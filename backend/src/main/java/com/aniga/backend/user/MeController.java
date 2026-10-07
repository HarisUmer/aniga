package com.aniga.backend.user;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aniga.backend.auth.AuthFilter;
import com.aniga.backend.recommend.RecommenderV2;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Convenience routes bound to the signed-in user, so the phone never has to put its own id in the path.
 * The filter has already resolved the session.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final RecommenderV2 v2;

    public MeController(RecommenderV2 v2) {
        this.v2 = v2;
    }

    @GetMapping("/recommendations")
    public List<RecommenderV2.Recommendation> recommendations(HttpServletRequest request,
                                                              @RequestParam(defaultValue = "12") int limit,
                                                              @RequestParam(required = false) String type,
                                                              @RequestParam(defaultValue = "false") boolean track) {
        long me = AuthFilter.userId(request);
        return v2.recommend(me, Math.clamp(limit, 1, 60), type, track);
    }
}
