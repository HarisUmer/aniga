# Sources and scraping

## What the knowledge base taught us

From `Z:\pange\animation\knowledge_base\08-reader-app-source-indexes.md`:

- `index.json` / `index.min.json` are published by **extension repositories** (Keiyoushi for Mihon on Android, Aidoku community lists for iOS). Comic sites themselves never host them.
- One index lists hundreds or thousands of sources, each with a site name, language, home URL and an NSFW flag.
- Sites cluster into **CMS families** (Madara, MangaThemesia, FoolSlide…). Real reader apps implement one scraper per family and configure each site, rather than one scraper per site.
- Four index formats exist; the catalog JSON documents how to detect each.

## What we built

`tools/source-ingest/ingest_sources.py`

1. Reads the Keiyoushi `index.json` snapshot (`tools/source-ingest/data/keiyoushi-index.json`) — or, with `--catalog`, every verified repo in `08-source-index-catalog.json`.
2. Detects the format (mihon-repo-v2, tachiyomi-legacy, aidoku-v1; aidoku-legacy is skipped because it has no URL field).
3. Flattens extensions → sources, drops NSFW unless `--include-nsfw`.
4. Assigns a **content hint**: `zh*` or names with "manhua" → MANHUA; `ko` or "toon/webtoon/manhwa" → MANHWA; `ja` → MANGA; otherwise MIXED.
5. Writes `infra/db/init/003_source_catalog.sql` in 500-row batches.

Result on the snapshot: 2,397 sources → **1,778 SFW** kept. 148 manga-leaning, 127 manhwa, 90 manhua, 1,413 mixed. Top languages: en 356, ja 151, es 111, pt-BR 103.

**No site is contacted** by the ingest script; it only reads the index file.

## Catalog ≠ adapter

| | `source_catalog` | `source_adapter` |
|---|---|---|
| What | A pointer: "this site exists" | Working code that fetches chapters |
| Count | 1,778 | MangaDex, Pepper&Carrot, xkcd, Webtoon Canvas, plus AsuraComic, MangaTown, and MangaGeko |
| Source | Public extension index | Our Java code |

The adapter interface we will grow into (mirrors Keiyoushi's `lib-multisrc`):

```java
interface SourceAdapter {
    List<MangaSummary> popular(int page);
    List<MangaSummary> latest(int page);
    List<MangaSummary> search(String query, int page);
    MangaDetails details(String mangaId);
    List<Chapter> chapters(String mangaId);
    List<String> pages(String chapterId);
}
// One implementation per API or CMS family; per-site config (baseUrl, lang, selectors) comes from the DB.
```

## Mirror sites

The original goal was to scrape 10–20 sites from the catalog. The first pass built the catalog and the official MangaDex adapter, and left most aggregator sites as catalog rows only. Three of those sites are HTML adapters now: AsuraComic, MangaTown, and MangaGeko (see below). The rest of the catalog is still a pointer, not a reader.

### Legitimate ways to reach 10–20 sources

| Option | Coverage | Notes |
|--------|----------|-------|
| MangaDex (done) | Manga, manhwa, manhua, many languages | Credit MangaDex + groups, no ads |
| Official free readers with public or partner APIs | Varies | Check each site's terms; many forbid scraping, so partner APIs or links-out are the safe path |
| AniList / Jikan / Kitsu for metadata | All types | Metadata only, link out to official readers ("Where to read") |
| Creator-hosted or public-domain/CC comics | Small | Fully legal to read in-app |
| Practice scrapers on sandboxes (e.g. `books.toscrape.com`) | n/a | Learn CSS selectors, pagination, rate limiting, retries without legal risk |
| Webtoon Canvas (done, session 5) | Creator-uploaded vertical comics | Public ranking/search HTML + official viewer links; Originals are not scraped |

A CMS-family adapter written against a sandbox or a site that explicitly permits it teaches exactly the same skills (HTML parsing with Jsoup, pagination, image header requirements, rate limiting) as one written against a mirror.

Three catalog sites are HTML adapters anyway, by a later decision: **AsuraComic** (`asura`), **MangaTown** (`mangatown`), and **MangaGeko** (`mangageko`). English only. The series id is the site slug. The chapter id is `slug:chapterSlug`. Any other language returns an empty list. They show up from `GET /api/read/sources` as soon as the backend restarts.

## Interview angles

- "Why separate catalog and adapter?" → honest UI, mirrors Mihon, decouples data import from code.
- "How would you scale to 100 sources?" → family adapters + per-site config in DB, health checks per adapter, automatic disable on repeated failures, domain-rotation handled by config not code.
- "How do you handle site changes?" → contract tests per adapter against recorded fixtures, alert when selectors return empty.
