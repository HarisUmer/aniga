package com.aniga.backend.read;

import java.util.List;

/**
 * One class per reading source. Annotate it {@code @Component} in this package and Spring hands it to
 * {@link ReadController}; the app and the web console list it from {@code GET /api/read/sources}.
 * The records below are the only shapes those clients understand. Field-by-field notes:
 * {@code learning/18-adding-a-source.md}.
 */
public interface SourceAdapter {

    /** MANGA / MANHWA / MANHUA come from the series' original language; WEBCOMIC for western strips. */
    record Series(String source, String id, String title, String description, String coverUrl,
                  String contentType, String status, Integer year, List<String> tags, String author,
                  Integer chapterCount, String language) {

        Series(String source, String id, String title, String description, String coverUrl,
               String contentType, String status, Integer year, List<String> tags, String author) {
            this(source, id, title, description, coverUrl, contentType, status, year, tags, author, null, null);
        }

        Series withMeta(Integer chapters, String lang) {
            return new Series(source, id, title, description, coverUrl, contentType, status, year, tags, author,
                    chapters, lang);
        }
    }

    /** externalUrl != null means the chapter is hosted by the official publisher; the app links out (pages = 0). */
    record ChapterRef(String id, String number, String title, String language, int pages, String group,
                      String publishedAt, String externalUrl) {
    }

    /** captions are optional per-page text (xkcd alt text); fallbackUrls are tried when a page URL fails. */
    record Pages(String chapterId, List<String> urls, List<String> fallbackUrls, List<String> captions,
                 String credit) {
    }

    record Info(String code, String displayName, String kind, String homeUrl, String license,
                List<String> languages, String defaultLanguage, boolean continuousStrip) {
    }

    Info info();

    List<Series> popular(String lang, int page);

    List<Series> search(String query, String lang, int page);

    Series series(String id);

    List<ChapterRef> chapters(String seriesId, String lang);

    Pages pages(String chapterId, String lang);

    /** Optionally warm page lists for chapters likely to be opened next. Sources with cheap pages ignore it. */
    default void prefetch(List<String> chapterIds) {
    }

    /** Fast chapter count for ranking; default walks the full list. */
    default int countChapters(String seriesId, String lang) {
        return chapters(seriesId, lang).size();
    }
}
