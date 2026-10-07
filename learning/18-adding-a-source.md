# Adding a reading source

One Java class is the whole parser. Spring finds it, `ReadController` publishes it, and both the phone and the browser add a chip for it. You do not edit the controller, the Android Discover screen, or `console.js`.

The class reads one source. Official APIs, open licences, and creator-uploaded series were the first adapters. AsuraComic, MangaTown, and MangaGeko are HTML adapters as well ([05](05-sources-and-scraping.md), [09](09-sources-v2.md)).

## Where the file goes

```text
backend/src/main/java/com/aniga/backend/read/YourSiteAdapter.java
```

Same package as `SourceAdapter`. The class is a Spring `@Component` and `implements SourceAdapter`. The app scans `com.aniga.backend`, so a file in this package is registered on startup.

`info().code()` is the source id in every URL (`/api/read/{code}/...`) and on every `Series.source`. Use a short stable lowercase token, unique among adapters (`mangadex`, `xkcd`, `webtoon-canvas`).

HTML pages go through `HtmlFetch` (shared user agent, timeout, and rate limit). JSON APIs use a `RestClient`, the way `PepperCarrotAdapter` and `MangaDexAdapter` do.

## What you implement

| Method | Called by | Returns |
|---|---|---|
| `info()` | `GET /api/read/sources` | One `Info`. Builds the chip and the language list. |
| `popular(lang, page)` | `GET /api/read/{code}/popular?lang=&page=` | `List<Series>`. `page` starts at 0. An empty list means there is no next page. |
| `search(query, lang, page)` | `GET /api/read/{code}/search?q=&lang=&page=` | `List<Series>`. The controller rejects a blank `q` before it reaches you. |
| `series(id)` | `GET /api/read/{code}/series/{id}` | One `Series`. `id` is the id you put on that series earlier. |
| `chapters(seriesId, lang)` | `GET /api/read/{code}/series/{id}/chapters?lang=` | `List<ChapterRef>`, oldest first. |
| `pages(chapterId, lang)` | `GET /api/read/{code}/chapters/{chapterId}/pages?lang=` | One `Pages`. Skip this when the chapter links out (below). |

`lang` is the reader's code (`en`, `ja`, `ko`, `pt-BR`, …). When it is null or blank, use `info().defaultLanguage()`.

`prefetch` and `countChapters` have defaults. Override `countChapters` when a full chapter list is expensive and a count is cheap.

## Records

These are the JSON bodies. Field order is the constructor order.

### Info

```text
code, displayName, kind, homeUrl, license, languages, defaultLanguage, continuousStrip
```

| Field | Format |
|---|---|
| `code` | `[a-z0-9-]+`. Becomes the URL segment and `Series.source`. |
| `displayName` | Chip label. "Pepper&Carrot", "Webtoon Canvas". |
| `kind` | `OFFICIAL_API` or `CMS_FAMILY`. The `source_adapter` table accepts only these two. |
| `homeUrl` | Absolute https URL of the site or API. |
| `license` | One sentence: who holds the work, and the licence or terms. Shown as the chapter credit when you also put it on `Pages.credit`. |
| `languages` | Language codes this source can serve. The app's language dialog lists them. |
| `defaultLanguage` | Used when the client omits `lang`. |
| `continuousStrip` | `true` for a vertical webtoon, `false` for page-by-page. |

### Series

```text
source, id, title, description, coverUrl, contentType, status, year, tags, author, chapterCount, language
```

| Field | Format |
|---|---|
| `source` | Same string as `info().code()`. |
| `id` | Opaque string you can parse again. The client sends it back to `series` and `chapters`. Keep it free of `/`. Webtoon Canvas uses `titleNo:slug`. |
| `title` | Display title. |
| `description` | Plain text, or null on a listing card. |
| `coverUrl` | Absolute image URL, or null. |
| `contentType` | `MANGA`, `MANHWA`, `MANHUA`, or `WEBCOMIC`. |
| `status` | `ongoing`, `completed`, `hiatus`, or `cancelled`. |
| `year` | Integer, or null. |
| `tags` | List of strings. Empty list when you have none. Never null. |
| `author` | String, or null. |
| `chapterCount` | Integer once you know it, or null on a card. |
| `language` | Language of this row, or null on a card. |

There is a shorter constructor that sets `chapterCount` and `language` to null. `withMeta(chapters, lang)` fills them later.

### ChapterRef

```text
id, number, title, language, pages, group, publishedAt, externalUrl
```

| Field | Format |
|---|---|
| `id` | Opaque string sent back to `pages`. Free of `/`. xkcd uses `1-25`. |
| `number` | Display number, as a string (`"12"`, `"12.5"`). |
| `title` | Chapter title. |
| `language` | Language code of this chapter. |
| `pages` | Page count when you know it. `0` when the chapter is a link-out. |
| `group` | Scanlation group or author, or null. |
| `publishedAt` | ISO date or the site's date string, or null. |
| `externalUrl` | Absolute URL of the official viewer, or null. |

A non-null `externalUrl` means the app opens that URL and does not call `pages`. Pair it with `pages = 0`. `WebtoonCanvasAdapter` does this, and its `pages` method throws `NoSuchElementException`.

### Pages

```text
chapterId, urls, fallbackUrls, captions, credit
```

| Field | Format |
|---|---|
| `chapterId` | The id the client asked for. |
| `urls` | Absolute image URLs, first page first. Empty means the read failed; the controller logs it. |
| `fallbackUrls` | Same length as `urls`, or an empty list. The reader tries `fallbackUrls[i]` when `urls[i]` fails. |
| `captions` | Same length as `urls`, or an empty list. Drawn under the page (xkcd alt text). |
| `credit` | Line shown at the end of the chapter. Name the author and the licence. |

Return `Pages` when the chapter should open inside Aniga (MangaDex, CC comics, and the AsuraComic, MangaTown, and MangaGeko adapters). A creator-uploaded series that should stay on its own site uses `externalUrl` instead.

## Skeleton

```java
@Component
public class YourSiteAdapter implements SourceAdapter {

    private final HtmlFetch html;

    public YourSiteAdapter(HtmlFetch html) {
        this.html = html;
    }

    @Override
    public Info info() {
        return new Info("yoursite", "Your Site", "OFFICIAL_API",
                "https://example.test", "Who holds the work, and on what terms.",
                List.of("en"), "en", false);
    }

    @Override
    public List<Series> popular(String lang, int page) { /* parse a listing */ }

    @Override
    public List<Series> search(String query, String lang, int page) { /* parse a search page */ }

    @Override
    public Series series(String id) { /* parse one series page */ }

    @Override
    public List<ChapterRef> chapters(String seriesId, String lang) { /* parse the chapter list */ }

    @Override
    public Pages pages(String chapterId, String lang) {
        // In-app images, or throw when every chapter has an externalUrl.
    }
}
```

Fetch HTML with `html.get(url)`. Pass `HtmlFetch.BROWSER` as the user agent when the site only answers a browser. Parse with Jsoup on the `Document` you get back. Keep the CSS selectors and the id format inside this class. Cache with `TtlCache` when the same page is hit on every open; `WebtoonCanvasAdapter` is the pattern.

Put a test beside it:

```text
backend/src/test/java/com/aniga/backend/read/YourSiteAdapterTest.java
```

Make the parse methods package-visible and static, feed them `Jsoup.parse(fixtureHtml)`, and assert ids and titles. `WebtoonCanvasAdapterTest` is the pattern. The test does not call the network.

## What you change for each parser

**Required.** The new class above. Restart the backend. The chip appears in Discover on the phone and in the browser because both call `GET /api/read/sources` and then `/api/read/{code}/popular` and `/search`.

**Nothing else in Java, Kotlin, or the web console.** `ReadController` collects every `SourceAdapter` bean. `ChapterFinder` searches every bean when a catalog title asks "where can I read this?".

**Optional, only if this site already has a row in `source_catalog`.** That row is a pointer. Linking it makes the Sources tab say the extension is readable in Aniga. On a database that already exists, run:

```sql
INSERT INTO source_adapter (code, display_name, kind, base_url, enabled, notes) VALUES
('yoursite', 'Your Site', 'OFFICIAL_API', 'https://example.test', TRUE, 'One sentence.')
ON CONFLICT (code) DO NOTHING;

UPDATE source_catalog
SET adapter_code = 'yoursite', adapter_lang = 'en'
WHERE home_url = 'https://example.test';
```

`kind` is `OFFICIAL_API` or `CMS_FAMILY`. Put the same `INSERT` in a new file under `infra/db/init/` so a fresh database gets it too. `005_sources_v2.sql` and `013_html_readers.sql` are the existing examples. An existing database does not re-run init scripts; apply the new file with `psql`.

The Sources admin strip (`GET /api/sources/adapters`) reads that table. Discover does not. A parser with no SQL row is still selectable.
