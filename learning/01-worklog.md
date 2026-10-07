# Worklog

## Session 1 — design (2026-10-07)

**Goal:** plan an Android (Java) app for anime metadata, manga/manhwa/manhua reading, and taste-based recommendations.

- Read earlier research in `Z:\pange\animation\knowledge_base\08-reader-app-source-indexes.md` and `08-source-index-catalog.json`.
  Key finding carried forward: `index.json` / `index.min.json` files are **catalogs of scraper plugins** (Mihon APKs, Aidoku .aix), not comic content.
- Compared free metadata APIs: **AniList GraphQL** (anime + manga, no key for reads), **Jikan** (unofficial MyAnimeList REST), **MangaDex** (official API with chapter pages).
- Compared Firebase vs Supabase for later auth + chat. Chose Supabase (Postgres, Realtime channels with row-level security); FCM only for push.
- Compared recommender approaches; decided V1 = genre scoring + TF-IDF, research track = LSTM + feature MLP + RL.
- Locked product choices: anime is metadata only; vertical reader first for all comic types; NSFW deferred.
- Output: system design image and audit canvas.

## Session 2 — first build (2026-10-07)

**Goal:** local Postgres in Docker, test everything in a browser, source list from the knowledge base, recommender research doc, and this learning folder.

| Step | What was done | Result |
|------|---------------|--------|
| 1 | `infra/docker-compose.yml`: Postgres 17, Adminer, backend | One command starts the stack |
| 2 | `infra/db/init/001_schema.sql`: users, media, ratings, library, interaction events, source catalog, adapters, chat tables | Schema reviewed as one unit |
| 3 | `002_seed.sql`: 33 hand-written titles across all four types, 3 demo users with ratings | Recommender works without external APIs |
| 4 | `tools/source-ingest/ingest_sources.py`: parsed the Keiyoushi index from the knowledge base | **1,778 SFW sources** cataloged (148 manga-leaning, 127 manhwa, 90 manhua, 1,413 mixed); NSFW excluded |
| 5 | Spring Boot 4.1 backend (Java 21), built inside Docker | No host Java needed |
| 6 | V1 recommender in plain Java + 3 unit tests | Tests run during every Docker build |
| 7 | MangaDex adapter: search, chapter feed, page URLs | Real chapters readable |
| 8 | Browser test console served by the backend | All features testable without Android |
| 9 | Bug found in browser test: MangaDex@Home cache nodes return 404 on a cold miss | Added origin fallback URLs; a cold 16-page chapter then loaded 16/16 |
| 10 | `ml/synthetic/generate_dataset.py` | 1,000 users, 3,000 items, 494k events, 28k ratings in ~16 s |
| 11 | Research proposal for LSTM + MLP + RL | `research/recommender-research-proposal.md` |

### Verified results

- Demo user **kenji** rated Naruto 5 and Attack on Titan 10. `/api/users/1/taste` shows
  liked terms `dark, revenge, swordsman, mercenary, tragedy` and disliked terms
  `tournament, rivalry, school, training, friendship`. The recommender separates two same-genre titles by their themes, which was the original requirement.
- Bad input returns proper errors (score 11 → HTTP 400 with a problem-detail body).

(Screenshots of the first console were replaced in session 3; see [08-design-system.md](08-design-system.md).)

### Known limitations found while testing

- TF-IDF has no stemming: `tournament` and `tournaments` are separate terms.
- Some weak terms leak in (`against`, `hunts`, `student`); the stopword list is small.
- When text overlap is zero the genre score alone decides (e.g. a title scoring `text = 0.00` ranked 2nd).
- Content hints for catalog sources are heuristic (language + words like "toon" in the name).

### Not done yet (on purpose)

- Android client changes (done in session 3).
- Site-specific scrapers for unlicensed mirror sites — see [05-sources-and-scraping.md](05-sources-and-scraping.md).
- Training the LSTM / MLP / bandit models (milestones M1–M4 in the research proposal).
- Auth and chat (Supabase phase).

## Session 3 — real design, real covers, Android app (2026-10-07)

**Feedback:** the console looked like generic AI output; manga needed images; colours needed to be distinct; animations should be illustrative (a plane flying, a ball rolling), not blinking buttons; the reader colours were bad.

| Step | What was done | Result |
|------|---------------|--------|
| 1 | Researched reading-app colour practice (dark grey vs black, sepia values, halation), manga page anatomy, path animation on Android/SVG | Decisions recorded in [08-design-system.md](08-design-system.md) |
| 2 | Added `cover_url`, `banner_url`, `accent_color`, `anilist_id`, `average_score` to `media`; wrote `tools/media-sync/fetch_anilist_art.py` | 32/33 seed titles matched on AniList; output is `004_media_art.sql` |
| 3 | Made AniList search precise | Query filters by `countryOfOrigin` and `format_in` and sorts by search match, then popularity |
| 4 | Built the Android app (Java, Views): Splash, Shelf, For You, Discover, Detail, Reader | Retrofit 3 + Glide 5; emulator talks to `10.0.2.2:8080` |
| 5 | Wrote custom animation views: paper plane, rolling ball, hanko stamp, speed lines, plane progress | All drawn on Canvas, no Lottie |
| 6 | Four reader tones (Night ink, Sepia, Paper, OLED), auto-hide chrome, immersive mode, webtoon zero-gap | Verified on Pixel 8 Pro emulator |
| 7 | Rebuilt the web console on the same tokens, fonts and animations | Verified in a browser |

### Bugs found while testing

- Glide 5.0.9 requires compileSdk 37; bumped from 36.1.
- Theme-level `fontFamily` overrode every TextAppearance (Anton never rendered).
- Material `TextAppearance` parents force `lineHeight` → double-spaced headlines; styles now have no parent.
- Android 12+ shows the default robot icon in the system splash; set `windowSplashScreenAnimatedIcon` to transparent and replaced the launcher icon with the seal.
- Editing a Java file with PowerShell `Set-Content` turned "·" into mojibake; edits now go through UTF-8-safe tools only.

## Session 4 — sources everywhere, real catalog, 500k users, search, Japan v2 (2026-10-07)

**Request:**

- every extension works, with browsing, chapters and reading on web and app;
- per-user recommendations on the home page, trained on 500k users (taste, ratings, chapters read, completed, dropped, half);
- ratings for anime and manga;
- anime with episodes, descriptions and images, rateable from the Shelf and For You;
- more animation and more Japanese elements;
- description search and reverse image search;
- an animated bottom nav and Discover;
- reports and a knowledge base.

| Step | What was done | Result | Doc |
|---|---|---|---|
| 1 | Source adapters and `/api/read/*`; catalog rows linked to adapters; health check | MangaDex in 61 languages + Pepper&Carrot + xkcd; 62/63 extensions read end to end; 1,778 sites health-checked | [09](09-sources-v2.md) |
| 2 | AniList catalog importer | 902 titles with descriptions, episodes, gallery, cast | [10](10-catalog-anime-library.md) |
| 3 | Library API (status, progress, score) | One shelf model for anime and comics; reader auto-saves | [10](10-catalog-anime-library.md) |
| 4 | 500k-user simulator | 15.8 M interactions, 6.5 M ratings in 47 s | [11](11-dataset-500k.md) |
| 5 | Trained pop / content / EASE / two-tower / blend | nDCG@10 0.0841 (blend) vs 0.0406 (pop) | [12](12-training-report.md) |
| 6 | `RecommenderV2` serving + franchise rerank + because anchors | Home "推 For you" per user | [12](12-training-report.md) |
| 7 | BM25 story search, trace.moe + dHash image search | "boy reborn as villain" → correct #1; Frieren still → EP 1 9:55 | [13](13-search.md) |
| 8 | Android: InkNav, sakura, seigaiha, hanko rating, Library, new Discover and Detail | `assembleDebug` passes | [08](08-design-system.md) |
| 9 | Web console parity: Discover (3 modes), Library, new Detail, reader on `/api/read`, Sources health | Playwright-verified | [04](04-backend-and-web-console.md) |

### Bugs found while testing

| Bug | Cause | Fix |
|---|---|---|
| MangaDex chapter lists empty | `includeExternalUrl=1` = external only | Omit the parameter |
| Popular titles that open with zero chapters | Licensed titles keep `availableTranslatedLanguage` | "Readable popular" built from the latest chapters |
| Filipino → HTTP 400 | `fil` is not a valid MangaDex code | `fil` → `tl` |
| Recommendations were four Attack on Titan seasons | Sequels share neighbours | Franchise diversification rerank |
| Rating from For You put the title on the shelf as PLANNING | PUT always upserted | Rating-only on an unshelved title skips the upsert |
| Seigaiha drew overlapping arcs in the wrong order | Rows painted bottom-up | Staggered rows painted top-down |
| Tapping an anime episode raced the shelf refresh | Two writers | Episode tap goes through `ShelfPanel.setProgress` |
| Home showed few manhua | One `/api/media` call is clamped to 200 rows sorted by popularity | One request per type rail |
| Web console threw on load | Old MangaDex search code referenced removed elements | Rewritten; `tools/check_console.js` now guards ids and syntax |

### Known limitations

- The synthetic data proves method ranking, not real-world accuracy.
- trace.moe's anonymous quota is small; covers still work without it.
- dHash only catches near-duplicate covers (no crops).
- No auth yet: the user id is in the path. Chat must wait for auth.

## Session 5 — chapters that are actually there (2026-10-07)

**Request:** better motion; find chapters properly; scrape only sites that allow it; open the source with the most chapters. *The Beginning After the End* and many manhwa showed none; Chainsaw Man stopped around 100.

| Step | What was done | Result |
|---|---|---|
| 1 | Live MangaDex probe | TBATE's first unfiltered hit is the **book** (0 ch). Chainsaw Man EN = 100 unique ch; **es-la = 233**, pt-br = 230. Same pattern on TBATE / ORV |
| 2 | `TitleMatch` + `ChapterFinder` | Novel-only rows dropped; aliases (native/romaji) searched; hits ranked by title score then chapter count |
| 3 | MangaDex `/aggregate` per language | Detail chips are `mangadex · es-la · 233 CH`, not a single empty English row |
| 4 | Feed pagination by returned size | Never skip 101–499 if the API caps a page below 500 |
| 5 | English title from `altTitles` | "Solo Leveling" matches even when the primary title is `ko-ro` |
| 6 | `WebtoonCanvasAdapter` | Ranking + search scraped; every episode is an official WEBTOON link-out |
| 7 | Motion | Plane draws its route in ink; ball crouches before the hop; splash splatters; chapter list wipes in |

Unlicensed index/mirror scrapers were not added. Extra chapters come from other legal MangaDex translations and official external links.

## Session 6 — the model learns from you; faster everywhere (2026-10-07)

**Request:**
- test the recommender thoroughly and improve it;
- add reinforcement-style learning (see and change your taste; a low rating on a liked genre moves it; disliked recommendations count as negatives);
- run petals behind the whole dashboard;
- add better motion;
- show episodes seen out of the total;
- virtualize long lists;
- optimise scraping and reverse search.

| Step | What was done | Result | Doc |
|---|---|---|---|
| 1 | Retrain with diagnostics: short-history weights, type part | Blend nDCG@10 0.0841 → 0.0850; cold start 0.0430 → 0.0489 (now beats the tower) | [12](12-training-report.md) |
| 2 | Online learner: per-user logistic SGD on rewards, boost/mute prefs, exploration slots, taste-change log with expectation | 18/18 e2e checks; Horror 0.34 → 0.09 after one low rating | [15](15-feedback-learning.md) |
| 3 | Feedback simulation (1,500 users × 30 rounds, taste drift) to tune η, online weight, β, half-life | +8% good@10 steady, +31% reward under drift | [15](15-feedback-learning.md) |
| 4 | Sources: single-flight + stale-while-revalidate cache, token bucket, parallel feed pages, page prefetch, gzip | Warm 3–12 ms; 1,004 chapters in 2.8 s; find 18.0 s → 3.8 s | [16](16-performance-and-motion.md) |
| 5 | Image search: query variants, zoom hash ( 09_cover_zoom.sql), edge-majority trim, parallel trace.moe, digest cache | Mirrored / zoomed / wide-page queries all rank #1; 28–37 ms | [16](16-performance-and-motion.md) |
| 6 | Web: full-page canvas sakura, cover-morph view transition, windowed episode/chapter lists, infinite catalog, progress on covers, ♥/✕ + taste editor | Playwright: 0 errors, 16 DOM rows for 1,004 items | [16](16-performance-and-motion.md) |
| 7 | Android: one SakuraView behind all tabs, Progress cache + bar on every cover, ♥/✕/Undo and Exploring tag on picks, editable taste bars that glide on change, "lately it learned", reset | ssembleDebug passes; endpoints verified by e2e (UI behind the new Firebase sign-in) | [15](15-feedback-learning.md) |
| 8 | Tests | 36 JUnit tests pass (20 new: OnlineLearner, RecommenderV2 helpers, TtlCache, ImageHash) | — |

### Bugs found while testing

| Bug | Cause | Fix |
|---|---|---|
| Small taste changes were not shown | Only deltas ≥ 0.01 were logged | Always log, with the model's prior `expected` |
| A boost took 17 of 20 slots | Boost too large (1σ) | 0.3σ, giving 10–11 of 20 |
| Zoomed cover query missed | One stored hash per cover | Second centre-80% hash |
| A cover in a wide page ranked 3rd | Corner vote picked the cover's colour as background | Edge-majority background |
| Every row said "lean towards Action" | Online reason threshold too low | Threshold 0.3, reason last; explore reason names the least-known genre |
| Windowed list blank until scrolled | No repaint when content above loaded | ResizeObserver |
| Petals piled above the viewport after scrolling | No vertical wrap | Wrap by H + 120 |
| Find across sources took 18 s | About 120 rate-limited per-language calls; no deadline | Known-language filter, top-4 expansion, parallel counts, deadlines |

## Session 7 — the box, the lock, and an APK (2026-10-07)

**Request:** put the backend on EC2, keep Firebase as the password check only, add friends, chat, a reading type, and matches, then ship an APK and write the walkthrough.

| Step | What was done | Result |
|---|---|---|
| 1 | Postgres and the API on EC2, Caddy on 80/443, database port closed | Health over HTTPS; secrets only in the server `.env` |
| 2 | Session JWT after Firebase sign-in; `AuthFilter` on private routes | 401 without a token, 403 when the path user is not the token user |
| 3 | Friend requests, global and group rooms, shared shelves, reading type, taste matches | Circle tab showed The Explorer, Mina, and a shared Attack on Titan |
| 4 | APK built against the EC2 URL, served at `/download/aniga.apk` | Phone browsers fetch it over HTTP; the app still calls the API over HTTPS |
| 5 | [17-the-live-stack.md](17-the-live-stack.md) | The picture, the steps, and the Spring Boot path |
