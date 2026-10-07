package com.aniga.backend.media;

import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaRepository media;

    public MediaController(MediaRepository media) {
        this.media = media;
    }

    @GetMapping
    public List<Media> search(@RequestParam(required = false) String q,
                              @RequestParam(required = false) String type,
                              @RequestParam(required = false) String genre,
                              @RequestParam(required = false) String sort,
                              @RequestParam(defaultValue = "60") int limit,
                              @RequestParam(defaultValue = "0") int offset) {
        return media.search(q, type, genre, sort, Math.clamp(limit, 1, 200), Math.max(offset, 0));
    }

    @GetMapping("/genres")
    public List<String> genres() {
        return media.genres();
    }

    @GetMapping("/{id}")
    public MediaRepository.Detail detail(@PathVariable long id) {
        return media.detail(id).orElseThrow(() -> new NoSuchElementException("media " + id + " not found"));
    }
}
