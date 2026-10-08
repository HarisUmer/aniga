# 14 — Knowledge base and roadmap

One page that answers: **what exists, where it lives, what is next, and how we will build it.** Details are in the numbered files.

## 1. What exists today

### System

```text
Android app (Java, Views)  ─┐
                            ├─ HTTP/JSON ─> Spring Boot 4 backend (Docker :8080) ─> Postgres 17 (Docker :5433)
Web console (single HTML) ──┘                    │
                                                 ├─> MangaDex API, Pepper&Carrot, xkcd       (reading)
                                                 ├─> AniList GraphQL                         (catalog, offline tool)
                                                 └─> trace.moe                               (anime scene search)
ML (Python, offline): 500k-user simulator -> training -> 007_model_v2.sql -> served by RecommenderV2.java
```

### Feature map

| Feature | Backend | Web console | Android | Doc |
|---|---|---|---|---|
| Catalog: 902 real titles (300 anime, 250 manga, 201 manhwa, 151 manhua) | `media`, `MediaController` | Shelf, Discover → Catalog | Home rails, Discover | [10](10-catalog-anime-library.md) |
| Anime metadata: episodes, facts, gallery, trailer, cast | `GET /api/media/{id}` | Detail drawer | `DetailActivity` | [10](10-catalog-anime-library.md) |
| Shelf: status, progress, 1–10 rating (anime and comics) | `LibraryController` | Detail shelf, Library tab | `ShelfPanel`, `RateSheet`, `LibraryFragment` | [10](10-catalog-anime-library.md) |
| Source catalog: 1,778 extensions, health check | `SourceController`, `SourceHealthService` | Sources tab | — | [05](05-sources-and-scraping.md), [09](09-sources-v2.md) |
| Reading: MangaDex in 61 languages, Pepper&Carrot, xkcd | `ReadController` + adapters | Discover → source, Detail chapters, Reader | Discover, Detail, `ReaderActivity` | [09](09-sources-v2.md) |
| Reader: 4 tones, webtoon zero-gap, fallback pages, auto-save progress | — | Reader overlay | `ReaderActivity` | [08](08-design-system.md) |
| Recommendations v2 with "because", franchise-diversified | `RecommenderV2` | Shelf "推 For you", For You tab | Home picks rail, For You | [12](12-training-report.md) |
| More like this | `/api/media/{id}/similar` | Detail | Detail | [12](12-training-report.md) |
| 500k-user dataset | `ml/synthetic/generate_users_500k.py` | — | — | [11](11-dataset-500k.md) |
| Story search (BM25) | `/api/search` | Discover → Story | Discover → Story | [13](13-search.md) |
| Image search (trace.moe + dHash) | `/api/search/image` | Discover → Image (drop/paste) | Discover → Image (Photo Picker) | [13](13-search.md) |
| Design system "Sumi & Hanko" v2 | — | CSS tokens, sakura, seigaiha, ink nav | custom Canvas views | [08](08-design-system.md) |

### Verified this session

| Check | Result |
|---|---|
| Every readable extension: popular → chapters → pages | 62 OK, 1 empty (af), 0 failed |
| Health check over 1,778 extensions | 1,128 up · 412 down · 224 blocked · 14 moved |
| Web console end to end (Playwright) | Discover 48 series → 63 chapters → 45 pages; Frieren detail; rate 9 + EP 5 → Library row; story search; image search |
| Recommender test nDCG@10 | blend 0.0841 vs popularity 0.0406 |
| Android | `assembleDebug` passes |

### Where things live

```text
backend/src/main/java/com/aniga/backend/
  media/        catalog + detail          read/       source adapters + /api/read
  user/         users, library            recommend/  V1 + V2 recommenders
  source/       extension catalog, health search/     BM25 + image search
backend/src/main/resources/static/                  index.html, console.css, js/*.js (core, shelf, detail, reader, …)
app/src/main/java/com/example/aniga/
  ui/home ui/foryou ui/discover ui/detail ui/reader ui/library ui/shelf ui/widget
  data/ (Api, Session, models)
infra/db/init/  001 schema · 002 seed · 003 sources · 004 art · 005 adapters · 006 catalog · 007 model
ml/             synthetic/ train/ models/v2/metrics.json
tools/          media-sync/ (AniList) · source-ingest/ (extensions, smoke tests) · check_console.js
```

## 2. What is next, in order, and how

### Next 1 — Chat (the user's next request)

**Goal:** 1:1 and per-title discussion rooms ("Frieren · EP 5"), spoiler-safe.

**How:**

1. **Transport:** Spring WebSocket + STOMP (`/ws`, topics `/topic/room.{id}`). A single node needs no broker; when we scale out, add Redis pub/sub or move to Supabase Realtime (already the long-term plan in [02](02-architecture-decisions.md)).
2. **Schema:**
   - `chat_room(id, kind DM|TITLE|EPISODE, media_id, episode)`
   - `chat_member(room_id, user_id, last_read_id)`
   - `chat_message(id, room_id, user_id, body, spoiler_upto, created_at)`, indexed on `(room_id, id)` for keyset pagination.
3. **Spoiler guard:** a message posted from EP 12 carries `spoiler_upto = 12`. A reader whose shelf progress is below that sees it blurred, with "tap to reveal". This reuses `library_entry.progress`, so it is a real reason the shelf exists.
4. **History:** `GET /api/rooms/{id}/messages?before=` returns 50 messages at a time; the client merges in live messages.
5. **Moderation:** per-user rate limit (token bucket, 5 messages per 10 s), a word-list filter, and a report button. The NSFW policy follows the source policy.
6. **Clients:**
   - Android: OkHttp WebSocket with a small STOMP frame helper, plus a `ChatFragment` with a sumi-ink bubble style.
   - Web: native `WebSocket`.
7. **Auth prerequisite:** today `userId` is in the path. Before chat, add login (Supabase Auth or Spring Security + JWT) so a user can't post as someone else.

### Next 2 — Authentication

Spring Security resource server validating JWTs, with `userId` taken from the token instead of the path. A local profile keeps the demo users.

### Next 3 — Semantic search and image embeddings

pgvector in the same Postgres:

- sentence-embedding column on `media`;
- hybrid ranking: BM25 + cosine merged with reciprocal rank fusion;
- CLIP image vectors for panels and fan art.

See [13](13-search.md).

### Next 4 — Recommender v3

- Add the LSTM sequence model from the research proposal as a blend part.
- Log impressions to measure CTR and completion lift.
- Nightly retrain job; the model version is stored next to the vectors so the backend can hot-swap.

### Next 5 — Sources

- Webtoon Canvas / Tapas free episodes via public feeds.
- Nightly scheduled health check with history.
- Per-source rate-limit budget.

### Next 6 — Release hygiene

- Git repository and CI: backend tests, the console check script, and an Android build.
- Release signing.
- Hosted backend (Fly.io or Render) with managed Postgres; HTTPS required for Android release builds.

## A small model on the live machine

Not now. The box has 908 MB of RAM, and a chat model that is actually useful wants several gigabytes on top of Postgres and the API. The disk itself was Ubuntu, a swap file, and the container images; the database is 18 MB. The numbers are in [20-disk-and-memory.md](20-disk-and-memory.md). A chatbot for "find this anime" would not need a model of its own first: it would call the search, the source finder, and the reverse-image route that already exist, and only then a small local model to turn the question into those calls. That second step waits until the machine has room.

## 3. How to resume work (checklist)

```powershell
cd infra; docker compose up -d --build              # db + backend
node tools/check_console.js                         # web console ids + syntax
python tools/source-ingest/smoke_all_extensions.py  # all readable extensions
.\gradlew.bat :app:installDebug
```

- Web console changes: the HTML is baked into the jar, so rebuild with `docker compose -f infra/docker-compose.yml up -d --build backend`.
- New DB init scripts only run on an empty volume (`docker compose down -v`). For a live DB, apply them with `docker exec -i aniga-postgres psql -U aniga -d aniga < file.sql`.
- Regenerate the model: `python ml/data/export_catalog.py`, then `generate_users_500k.py`, then `train_recommenders.py` (GPU recommended, ~5 min), then load `007_model_v2.sql`.
