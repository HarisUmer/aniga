package com.aniga.backend.mangadex;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mangadex")
public class MangaDexController {

    private final MangaDexClient client;

    public MangaDexController(MangaDexClient client) {
        this.client = client;
    }

    @GetMapping("/search")
    public List<MangaDexClient.MangaSummary> search(@RequestParam String q,
                                                    @RequestParam(defaultValue = "12") int limit) {
        if (q.isBlank()) {
            throw new IllegalArgumentException("q must not be blank");
        }
        return client.search(q.trim(), Math.clamp(limit, 1, 50));
    }

    @GetMapping("/manga/{mangaId}/chapters")
    public List<MangaDexClient.Chapter> chapters(@PathVariable String mangaId,
                                                 @RequestParam(defaultValue = "en") String lang,
                                                 @RequestParam(defaultValue = "100") int limit) {
        return client.chapters(mangaId, lang, Math.clamp(limit, 1, 500));
    }

    @GetMapping("/chapters/{chapterId}/pages")
    public MangaDexClient.ChapterPages pages(@PathVariable String chapterId) {
        return client.pages(chapterId);
    }
}
