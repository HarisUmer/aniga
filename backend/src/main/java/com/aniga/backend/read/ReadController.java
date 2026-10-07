package com.aniga.backend.read;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One reading API for every adapter: /api/read/{source}/popular → series → chapters → pages.
 * Clients never talk to MangaDex/xkcd/Pepper&Carrot directly, so adding a source needs no app update.
 */
@RestController
@RequestMapping("/api/read")
public class ReadController {

    public record SourceView(SourceAdapter.Info info, long catalogExtensions, List<String> catalogLanguages) {
    }

    private final Map<String, SourceAdapter> adapters;
    private final JdbcClient jdbc;
    private final ChapterFinder finder;
    private final com.aniga.backend.source.ParseLog parseLog;

    public ReadController(List<SourceAdapter> adapters, JdbcClient jdbc, ChapterFinder finder,
                          com.aniga.backend.source.ParseLog parseLog) {
        this.adapters = adapters.stream().collect(Collectors.toMap(a -> a.info().code(), Function.identity(),
                (a, b) -> a, LinkedHashMap::new));
        this.jdbc = jdbc;
        this.finder = finder;
        this.parseLog = parseLog;
    }

    private SourceAdapter adapter(String code) {
        SourceAdapter a = adapters.get(code);
        if (a == null) {
            throw new NoSuchElementException("unknown source '" + code + "'; try one of " + adapters.keySet());
        }
        return a;
    }

    @GetMapping("/sources")
    public List<SourceView> sources() {
        List<SourceView> out = new ArrayList<>();
        for (SourceAdapter a : adapters.values()) {
            List<String> langs = jdbc.sql("SELECT lang FROM source_catalog WHERE adapter_code = :c ORDER BY lang")
                    .param("c", a.info().code()).query(String.class).list();
            out.add(new SourceView(a.info(), langs.size(), langs));
        }
        return out;
    }

    @GetMapping("/{source}/popular")
    public List<SourceAdapter.Series> popular(@PathVariable String source, @RequestParam(required = false) String lang,
                                              @RequestParam(defaultValue = "0") int page) {
        return adapter(source).popular(lang, Math.max(page, 0));
    }

    @GetMapping("/{source}/search")
    public List<SourceAdapter.Series> search(@PathVariable String source, @RequestParam String q,
                                             @RequestParam(required = false) String lang,
                                             @RequestParam(defaultValue = "0") int page) {
        if (q.isBlank()) {
            throw new IllegalArgumentException("q must not be blank");
        }
        return adapter(source).search(q.trim(), lang, Math.max(page, 0));
    }

    @GetMapping("/{source}/series/{id}")
    public SourceAdapter.Series series(@PathVariable String source, @PathVariable String id) {
        return adapter(source).series(id);
    }

    @GetMapping("/{source}/series/{id}/chapters")
    public List<SourceAdapter.ChapterRef> chapters(@PathVariable String source, @PathVariable String id,
                                                   @RequestParam(required = false) String lang) {
        return adapter(source).chapters(id, lang);
    }

    @GetMapping("/{source}/chapters/{chapterId}/pages")
    public SourceAdapter.Pages pages(@PathVariable String source, @PathVariable String chapterId,
                                     @RequestParam(required = false) String lang) {
        try {
            SourceAdapter.Pages pages = adapter(source).pages(chapterId, lang);
            if (pages.urls() == null || pages.urls().isEmpty()) {
                parseLog.fail(source, chapterId, "no page links");
            } else {
                parseLog.ok(source, chapterId);
            }
            return pages;
        } catch (RuntimeException e) {
            parseLog.fail(source, chapterId, e.getMessage());
            throw e;
        }
    }

    /**
     * Warms the next chapters' page lists while the user is still reading the current one, so tapping
     * "next chapter" is instant. Best effort, returns immediately.
     */
    @PostMapping("/{source}/prefetch")
    public ResponseEntity<Void> prefetch(@PathVariable String source, @RequestParam List<String> chapters) {
        adapter(source).prefetch(chapters);
        return ResponseEntity.accepted().build();
    }

    /**
     * Searches every adapter, drops novel-only lookalikes, and ranks by title score then chapter count.
     * {@code aliases} is a {@code |} list (romaji, native title) from the catalog.
     */
    @GetMapping("/find")
    public List<SourceAdapter.Series> find(@RequestParam String title, @RequestParam(required = false) String lang,
                                           @RequestParam(required = false) String aliases) {
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        return finder.find(title.trim(), lang, aliases);
    }
}
