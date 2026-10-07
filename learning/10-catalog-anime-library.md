# 10 — Real catalog, anime metadata, shelf and ratings

## Catalog: 902 real titles from AniList

`tools/media-sync/fetch_anilist_catalog.py` pulls the most popular **SFW** titles from AniList's public GraphQL API (no key needed). Requests are spaced 2.2 s apart because the limit is 90 per minute and often degraded to 30. The script writes `infra/db/init/006_catalog.sql`.

| Type | Rows | With a gallery |
|---|---|---|
| Anime | 300 | 300 |
| Manga (JP) | 250 | 250 (banner) |
| Manhwa (KR) | 201 | 144 |
| Manhua (CN) | 151 | 52 |

Per title we store:

- **titles:** English, romaji and native;
- **text:** a cleaned description;
- **classification:** genres, and tags with rank ≥ 60 (spoiler tags excluded);
- **counts and dates:** episodes or chapters, volumes, minutes per episode, format, status, season and year;
- **people:** studios (anime) or authors (comics);
- **art:** cover, banner and accent colour;
- **JSONB `gallery`:** banner, streaming-episode stills titled "Episode N - Name", and the trailer thumbnail with its YouTube link;
- **JSONB `characters`:** main and supporting characters with native names and portraits.

Rows **upsert on `anilist_id`**. The 33 hand-seeded titles keep their ids, which means the seeded users' ratings stay valid.

Anime is **metadata only**. There is no streaming: the trailer opens on YouTube and episodes are a progress list.

## Library: status, progress, score — one row per user and title

```text
library_entry(user_id, media_id, status, progress, updated_at)   status ∈ PLANNING CURRENT COMPLETED PAUSED DROPPED
rating(user_id, media_id, score 1..10)
```

| Endpoint | Behaviour |
|---|---|
| `GET /api/users/{u}/library?status=` | Entries plus media, newest first |
| `GET /api/users/{u}/library/{m}` | `{status, progress, score}`; status is `NONE` when not shelved |
| `PUT /api/users/{u}/library/{m}` | Partial update; send only what changed |

Rules inside `PUT`, all server-side so both clients behave identically:

1. Progress is clamped to `[0, total]` when the total is known.
2. Progress > 0 on a PLANNING or unshelved title → `CURRENT`.
3. Progress reaching the total → `COMPLETED`, unless the request also sets a status explicitly.
4. `score: 0` deletes the rating; scores outside 1–10 return HTTP 400.
5. **Rating-only on an unshelved title does not shelve it.** This was a bug found in testing: rating from For You used to create a PLANNING entry.
6. Every change is also logged to `event` for the recommender and the future sequence model: `WATCH_EPISODE` or `READ_CHAPTER` with the number of new units, and `DROP` with the completion ratio.

Ratings work the same for anime and comics, because the recommender only sees `(user, media, score, status, completion)`.

## Where rating and progress are possible

| Place | Web | Android |
|---|---|---|
| Detail page shelf panel (status chips, −/+ stepper on the paper-plane route, 1–10 hanko seal) | yes | `ShelfPanel` |
| Anime episode list: click episode N = watched up to N | yes | yes |
| Reader end of chapter: auto-save progress (never moves backwards) | yes | yes |
| For You card "Rate" button | Rate tab | `RateSheet` bottom sheet |
| Library tab: filter by status, progress bar, score seal, edit | yes | `LibraryFragment` + `RateSheet` |
| Shelf home "続 Continue" rail of CURRENT titles with "EP 5/28" badges | yes | yes |

## Anime detail page

```text
banner + seigaiha waves + cover
title · format · N episodes · AniList score
genres
story (full description)
facts: 葬送のフリーレン · Sousou no Frieren · FALL 2023 · FINISHED · 24 min / ep · Studio MADHOUSE
Your shelf (status · EP 5 / 28 · score 9/10)
Stills · banner · trailer   (horizontal strip, click opens the image or the YouTube trailer)
Cast                        (round portraits, MAIN role ringed in the type colour)
Episodes                    (named from the stills: "EP 3 Killing Magic")
More like this              (EASE row + two-tower cosine, franchise-diversified)
```

Verified in Playwright on Frieren: 12 gallery items, 8 cast, 28 episodes, 12 similar titles. Rating 9 and clicking EP 5 gave "Watching · EP 5/28 · 9".

## Next

- Refresh the catalog weekly and diff `updated_at`; add an "airing" badge from AniList's `nextAiringEpisode`.
- Characters as first-class rows (search by character, "more with this voice actor").
- Import a user's AniList/MAL list to skip cold start.
