# 09 — Sources v2: every extension readable, health-checked, same API for web and app

## What was asked

"Make sure all the extensions work, and that I can see different manga and their chapters in every one of them, on the web and in the app."

## What "an extension" means here

The catalog (`source_catalog`, 1,778 SFW rows) is built from the public Keiyoushi/Mihon extension indexes ([05](05-sources-and-scraping.md)). Each row is one site in one language, e.g. "MangaDex (ko)".

There are two different questions about each row:

| Question | How it is answered | Where it shows |
|---|---|---|
| Is the site alive? | Health check: HTTP GET of the home URL | `source_health`, Sources tab "Health" column |
| Can Aniga read it? | A legal **adapter** exists for that site | `source_catalog.adapter_code`, "In Aniga" column |

The first adapters read sources that publish content themselves (official APIs, open licences, or creator-uploaded series). Most catalog rows are still health-checked and not scraped. Three HTML readers were added later and do serve pages in-app: AsuraComic, MangaTown, and MangaGeko ([05](05-sources-and-scraping.md)).

## Adapter pattern (Mihon-shaped)

```text
SourceAdapter
  info()                            code, name, kind, languages, licence, continuousStrip
  popular(lang, page)               -> List<Series>
  search(q, lang, page)             -> List<Series>
  series(id, lang)                  -> Series
  chapters(seriesId, lang)          -> List<ChapterRef>   (pages = 0 + externalUrl => official site link-out)
  pages(chapterId, lang)            -> Pages(urls, fallbackUrls, captions, credit)
```

| Adapter | Kind | Languages | Notes |
|---|---|---|---|
| `MangaDexAdapter` | official API | 61 (one catalog extension per language) | Hosted scanlations plus official external links |
| `PepperCarrotAdapter` | open webcomic, CC BY 4.0 | the languages the episode index reports | Credit line shown at the chapter end |
| `XkcdAdapter` | official JSON API, CC BY-NC 2.5 | en | The title text is shown as a caption under each strip |
| `WebtoonCanvasAdapter` | public HTML (creator-uploaded) | en | Ranking + search scraped; chapters link out to the official viewer |
| `AsuraComicAdapter` | HTML | en | Series slug; chapter id `slug:chapterSlug`; vertical strip |
| `MangaTownAdapter` | HTML | en | Series slug; chapter id `slug:chapterSlug`; page-by-page |
| `MangaGekoAdapter` | HTML | en | Series slug; chapter id `slug:chapterSlug`; vertical strip |

`005_sources_v2.sql` links catalog rows to adapters:

- every MangaDex extension → `mangadex` with its own language;
- Pepper&Carrot → `peppercarrot`;
- xkcd (English original only; the es/fr/ru/zh rows are independent fan sites) → `xkcd`.

`013_html_readers.sql` links the English Asura Scans, Mangatown, and MangaGeko rows (`asura`, `mangatown`, `mangageko`). The Asura row's home URL is `https://asurascans.com`, which is the site that adapter reads.

### Unified endpoints (`ReadController`)

```text
GET /api/read/sources                                  adapters + how many catalog extensions each serves
GET /api/read/{source}/popular?lang=&page=
GET /api/read/{source}/search?q=&lang=
GET /api/read/{source}/series/{id}/chapters?lang=
GET /api/read/{source}/chapters/{chapterId}/pages?lang=
GET /api/read/find?title=&lang=                        "which sources have this catalog title?"
```

The web console and the Android app call exactly these. Responses are cached in a small `TtlCache` to respect upstream rate limits: MangaDex lists, chapters and pages for 10 minutes (2,000 entries), the xkcd archive and Pepper&Carrot index for 6 hours, and individual xkcd strips for 30 days.

## MangaDex bugs found and fixed

| Symptom | Root cause | Fix |
|---|---|---|
| Chapter lists empty for most titles | `includeExternalUrl=1` means **only** external chapters, not "also external" | Omit the parameter (returns hosted and external chapters) |
| "Popular" showed titles that open with zero chapters | `availableTranslatedLanguage` still matches titles whose chapters were removed after licensing | **Readable popular**: take the latest 300 chapters in the language (`includeExternalUrl=0`), collect their distinct manga ids, then `/manga?ids[]=…&order[followedCount]=desc`. Every result has at least one readable chapter |
| Filipino extension returned HTTP 400 | Catalog uses ISO 639-3 `fil`; MangaDex validates `^[a-z]{2}(-[a-z]{2})?$` | Map `fil` → `tl` |
| Some pages 404 on the MangaDex@Home node | Volunteer nodes go stale | Every page has a `fallbackUrl` on the main uplink; both web and app switch automatically |
| A chapter number appears 3 times | Several groups upload the same chapter | Keep one per number; a hosted scan beats an external link |

## How "all extensions work" was verified

`tools/source-ingest/smoke_all_extensions.py` walks every readable catalog extension through the backend:

```text
for each extension: popular(lang) -> first 3 series -> chapters -> pages of first readable chapter
```

Result (`tools/source-ingest/data/extension-smoke.json`):

| Outcome | Count | Notes |
|---|---|---|
| OK (series → chapters → pages) | 62 | including Filipino after the `tl` fix (48 series) |
| EMPTY | 1 | Afrikaans: MangaDex has no readable chapters in `af`; the app explains "no chapters in this language" |
| FAIL | 0 | |

Playwright check in the console: Discover → MangaDex (en) shows 48 series. Opening *Tonari no Kurokawa-san* lists 63 chapters; chapter 1 loaded 45 pages, 31 of them via the fallback after node 404s.

## Health check (all 1,778 extensions)

`POST /api/sources/health/run` starts a background check (virtual threads with a concurrency cap, 6 s connect and 10 s request timeout, redirects followed). Progress is at `GET /api/sources/health`.

| Status | Rule | Result |
|---|---|---|
| UP | 2xx/3xx on the same host | 1,128 |
| DOWN | timeout, DNS failure, 4xx/5xx | 412 |
| BLOCKED | 403/429, or 503 from Cloudflare (bot wall) | 224 |
| MOVED | 2xx after redirecting to another domain | 14 |

The Sources tab filters by health, by "readable in Aniga", language and content hint. Clicking "read via mangadex · ko" jumps to Discover with that source and language selected.

## Where it shows

| Feature | Web console | Android |
|---|---|---|
| Source chips: Catalog, MangaDex (61), Pepper&Carrot, xkcd | Discover → Title | Discover → Title |
| Language picker (per adapter) | `<select>` saved in localStorage | Dialog saved in `Session.readingLang` |
| Chapters of a catalog title on every source | Detail → source chips (`/api/read/find`) | Detail → source chips |
| Official-site chapters | Shown with ↗, open a new tab | Shown as "OFFICIAL SITE ↗", ACTION_VIEW |
| Reader progress → shelf | End-of-chapter observer saves `progress` | `ReaderActivity.chapterFinished()` |

## Session 5 — find the real chapters, pick the longest list

Opening a catalog title used to take the first MangaDex search hit in the reading language. That failed in two ways we measured live:

| Title | What went wrong | What MangaDex actually has |
|---|---|---|
| *The Beginning After the End* | Unfiltered search ranks **(Book Version)** first — 0 chapters | Comic: 58 EN (official links), **230 pt-br**, 197 es-la |
| *Chainsaw Man* | English feed is ~100 unique numbers (hosted + a few official links) | **233 es-la**, 230 pt-br — same API, other translations |
| *Omniscient Reader* | Book version ranks first without a language filter | Comic: 4 EN hosted, 43 pt-br |
| *Solo Leveling* | Licensed; EN is official-link only | 16 EN official links |

`/api/read/find` now goes through `ChapterFinder`:

1. Search every adapter (title + catalog aliases: native / romaji).
2. Drop novel-only lookalikes (`Book Version`, `light novel`, …) unless the query is a novel.
3. Score remaining names (`TitleMatch`: exact > prefix > word overlap; side stories / doujinshi penalised).
4. Ask MangaDex `/aggregate` for the main languages and **emit one chip per language that has a real list**.
5. Sort by title score, then **chapter count descending**. The first chip is the longest legal list.

The chapter feed now pages by the number of rows actually returned (limit 100). A hardcoded `limit=500` would skip 101–499 if the API capped the page.

English is still preferred when it is close; a poorer language is only shown when it has at least a quarter of the richest count (or is the language the user asked for).

Unlicensed mirror sites are still not scraped. The extra chapters come from **other MangaDex translations** and from official external links, not from pirate indexes.

### Webtoon Canvas adapter (`webtoon-canvas`)

Creator-uploaded Canvas only (not WEBTOON Originals). Ranking + search pages are scraped with Jsoup; episode numbers come off the public list page; every chapter is a **link-out** to the official viewer. That is the scraping lesson (selectors, polite rate limit, cache) without hosting someone else's pages.

## Next

- Tapas originals' free episodes and Comikey/INKR are licensed (link-out only).
- Run the health check nightly (`@Scheduled`) and show trend sparklines.
