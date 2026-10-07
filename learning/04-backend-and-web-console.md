# Backend and web test console

**Where:** `backend/` (Spring Boot 4.1, Java 21, Maven). Built and run by Docker Compose.

## Package layout

```text
com.aniga.backend
├── media/       Media record, MediaRepository (JdbcClient), MediaController
├── user/        UserController (users + ratings), RatingRepository
├── recommend/   TfIdfIndex, HybridRecommender (pure Java), RecommendationController
├── source/      SourceController (catalog, stats, adapters)
├── mangadex/    MangaDexClient (RestClient), MangaDexController, Json helpers
└── web/         HealthController, ApiExceptionHandler (RFC 7807 problem details)
```

`HybridRecommender` and `TfIdfIndex` have **no Spring imports**, so the same code can be copied into the Android app for offline recommendations and is unit-tested without a Spring context.

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/health` | DB version + row counts |
| GET | `/api/media?q=&type=` | Search seeded titles |
| GET / POST | `/api/users` | List / create users |
| GET | `/api/users/{id}/ratings` | A user's ratings |
| PUT / DELETE | `/api/users/{id}/ratings/{mediaId}` | Rate 1–10 (upsert, also logs a RATE event) / remove |
| GET | `/api/users/{id}/recommendations?limit=&type=` | V1 recommendations with scores and reasons |
| GET | `/api/users/{id}/taste` | Genre weights + liked/disliked terms (the explanation) |
| GET | `/api/sources?q=&lang=&hint=&page=&size=` | Paginated source catalog |
| GET | `/api/sources/stats`, `/api/sources/adapters` | Catalog counts, working adapters |
| GET | `/api/mangadex/search?q=` | MangaDex search with covers |
| GET | `/api/mangadex/manga/{id}/chapters?lang=en` | Readable chapters + scanlation groups |
| GET | `/api/mangadex/chapters/{id}/pages` | Page URLs + origin fallbacks (legacy; clients now use `/api/read`) |

### Added in session 4

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/media?q=&type=&genre=&sort=popularity&limit=&offset=` | Catalog browse (limit ≤ 200) |
| GET | `/api/media/{id}` | Detail: media + romaji, volumes, minutes per episode, gallery, characters |
| GET | `/api/media/{id}/similar?limit=` | More like this (EASE + two-tower) |
| GET | `/api/users/{id}/library?status=` | Shelf entries with media |
| GET / PUT / DELETE | `/api/users/{id}/library/{mediaId}` | Status / progress / score (partial updates) |
| GET | `/api/read/sources` | Adapters + catalog extensions they serve |
| GET | `/api/read/{src}/popular`, `/search`, `/series/{id}/chapters`, `/chapters/{id}/pages` | Unified reading API, `?lang=` |
| GET | `/api/read/find?title=&lang=&aliases=` | Ranked sources/languages for a catalog title (most chapters first) |
| GET | `/api/search?q=&type=&limit=` | BM25 story search with matched words |
| POST / GET | `/api/search/image?scenes=` (multipart `file`) / `?url=` | Reverse image search |
| GET | `/api/sources?readable=&status=` | Catalog filtered by adapter / health |
| POST / GET | `/api/sources/health/run`, `/api/sources/health` | Start / poll the health check |

Console tabs now: Shelf · For You · Rate · Discover (Title / Story / Image) · Library · Sources, plus the detail drawer and reader. Quick guard after editing: `node tools/check_console.js`.

## Decisions worth explaining

- **Errors:** `ApiExceptionHandler` maps exceptions to HTTP status codes with `ProblemDetail` (RFC 7807): bad input → 400, unknown user → 404, duplicate username → 409, MangaDex failure → 502. Clients get one consistent error shape.
- **MangaDex JSON is read as maps, not records.** MangaDex returns `[]` instead of `{}` for some empty localized fields, which breaks strict binding. Small typed records are built from the maps.
- **Query parameters are passed as URI variables** (`queryParam("title", "{title}")` + `build(title)`) so a title containing `&` or `+` is encoded correctly instead of splitting the query.
- **User-Agent header** identifies the app to MangaDex, as their API guidelines ask.
- **Recommender index is rebuilt per request.** Fine for 33–1,000 titles (milliseconds). Once the catalog is synced from AniList, cache it and rebuild on catalog change.

## The MangaDex@Home bug (good interview story)

- **Symptom:** in the browser, chapter page 1 loaded, pages 2–13 returned 404.
- **Investigation:** the same URL fetched again returned 200. Page 1 worked only because a manual test had already requested it. MangaDex@Home cache nodes 404 on a **cold cache miss**, then serve the file once it's warm.
- **Fix:** the API returns `fallbackUrls` on `uploads.mangadex.org` (the origin) next to the node URLs; the reader swaps to the fallback on image error.
- **Verified:** a never-opened 16-page chapter loaded 16/16.
- **Lesson:** a passing API test ("we got 13 URLs") is not the same as a passing user test ("13 images rendered"). Test at the level the user experiences.

## Web test console

`backend/src/main/resources/static/` (`index.html`, `console.css`, `js/console.js`), served at http://localhost:8080. No build step, no framework.

| Tab | What it tests |
|-----|---------------|
| For You | Pick a user, see recommendations with genre/text sub-scores, reasons, taste profile; create users |
| Rate titles | Search seeded media and rate 1–10; ratings immediately change recommendations |
| Source catalog | Filter the 1,778 cataloged sources by name, language, content hint; see enabled adapters |
| MangaDex reader | Search → chapters → **vertical** reader with scanlation credit |

**Why a web console before Android:** it tests the API contract the Android app will use, in seconds, with browser dev tools. Bugs found here (like the MangaDex 404) would otherwise surface as "images don't load" on a phone, which is harder to debug.

**Security notes:** all API text is HTML-escaped before rendering; `<meta name="referrer" content="no-referrer">` because MangaDex image hosts reject cross-site referrers.
