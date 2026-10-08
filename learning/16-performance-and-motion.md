# 16 — Performance and motion: scraping, reverse search, virtualization, animation

**Request (session 6):**

- optimise scraping and reverse search;
- make long lists load fast with front-end virtualization;
- show how many episodes or chapters each title has and how many you have seen;
- run sakura petals behind the whole dashboard, not only the header;
- research and add better animations.

All numbers below were measured on the local Docker stack (`tools/bench_read.js`, `tools/image_variants.ps1`, Playwright).

## 1. Reading sources (MangaDex)

MangaDex allows about 5 requests/s globally and 40/min on `/at-home`, and asks clients to cache. The work is "make fewer calls, and never make the user wait on one".

| Technique | Where | Effect |
|---|---|---|
| **Single-flight** (concurrent misses for a key share one call) | `TtlCache.get` | 4 identical cold "popular fr" requests: one upstream call, 1.2 s total |
| **Stale-while-revalidate** (an expired value is served instantly for one more TTL while a virtual thread refreshes it) | `TtlCache` | Popular lists never block after first load: warm 11–12 ms |
| Failures are never cached; eviction drops expired entries, then the oldest 10% | `TtlCache.put` | A 429 does not poison the cache (`TtlCacheTest`) |
| **Token bucket** 4.5/s, burst 5, plus retry with backoff on 429 | `RateLimiter`, `MangaDexAdapter.get` | Parallel calls are safe; no 429 storms |
| **Virtual-thread fan-out** | feed pages, readable-popular, per-language counts | 1,004 One Piece chapter rows (11 feed pages) in 2.8 s cold; the pages are fetched together instead of one after another |
| **Page prefetch** of the next 2 chapters, skipped if already fresh | `POST /api/read/{source}/prefetch` | The next chapter's pages are already cached when you tap it |
| Page lists cached 5 min only | `pagesCache` | MangaDex@Home URLs carry a token that expires after about 15 min |
| gzip responses ≥ 1 KB, `Cache-Control` on static files | `application.yml` | The popular list goes over the wire at 15.6 KB instead of 42.4 KB |

### "Find across sources" bottleneck

Opening a title searches every source for the catalog title and its aliases, then counts chapters per language to open the richest translation. Two problems made a cold search take **18.0 s**:

1. Each MangaDex hit asked `/aggregate` for 10 languages, so 12 hits meant about 120 calls. At 4.5/s that alone is about 27 s of rate-limit tokens.
2. `invokeAll` had no deadline, so one slow source held everything up.

Fixes:

- `availableTranslatedLanguages` from the search result is remembered per title, and languages with no chapters are never counted.
- Only the **4 best** MangaDex matches get the cross-language comparison; the others are counted in the requested language.
- Counting runs in parallel.
- Search has an 8 s deadline and counting a 10 s one. Late sources are cancelled, not awaited.

Result: cold **18.0 s → 3.8 s**, with the same payload. Chainsaw Man still opens on the long translations (it 235, es-la 233, pt-br 230) in 4.8 s cold.

## 2. Reverse image search

| Technique | Why |
|---|---|
| All 902 cover hashes held in two `long[]` arrays, reloaded after 10 min or after indexing | A full scan is 1,804 XOR+popcount operations: microseconds |
| **Query variants:** trimmed, mirrored, untrimmed, centre crops at 85% and 70%, and left/right 2:3 windows when the image is wide | Screenshots are rarely the bare cover |
| A second stored hash per cover, its **centre 80%** (`cover_hash_zoom`, `009_cover_zoom.sql`) | A query cropped tighter than the cover still lines up |
| **Trim by edge majority:** background = the colour at least 40% of the border agrees on | Corner voting failed when the cover touched an edge of the page |
| trace.moe started first on a virtual thread; covers matched meanwhile; uploads over 300 KB are re-encoded to a ≤ 960 px JPEG | Total time ≈ trace.moe time, not the sum of both |
| SHA-256 result cache (only when trace.moe succeeded) | Repeat uploads are free and spend no trace.moe quota |

Robustness (`tools/image_variants.ps1`, Your Lie in April cover): original, mirrored, zoomed 80% and pasted into a wide dark page all rank the right title **#1**, at distances 0, 2, 2 and 1 (threshold 18). A covers-only search takes 28–37 ms. `ImageHashTest` locks this behaviour in with synthetic images.

## 3. Front-end virtualization (web console)

| Problem | Technique | Measured |
|---|---|---|
| Naruto Shippuden has 500 episodes; One Piece has 1,004 chapter rows | **Windowed list** (`virtualList`): fixed 64 px rows, an absolutely positioned window of the visible rows plus 10 overscan, repainted on `requestAnimationFrame` | 16 DOM rows for 500 or 1,004 items; detail opens in about 46 ms |
| The list did not refresh when content above it loaded | `ResizeObserver` on the list and the scroller | Rows appear without a scroll |
| Jump to episode 420 | `vl.scrollTo(i)` puts the row a third of the way down | Episodes 416–431 shown. An apparently blank full-page screenshot was a capture artefact; the element screenshot shows the rows |
| The 902-title catalog grid | Pages of 48, `IntersectionObserver` sentinel, `content-visibility: auto` with `contain-intrinsic-size` | 48 tiles first; 336 after scrolling; off-screen tiles skip layout and paint |

Android already uses `RecyclerView` everywhere, which is virtualization by design.

The chapter list used to grow the whole detail sheet. Scrolling a long series moved the cover, the synopsis, and the score with it. Both clients now keep that list in its own box:

- **Web:** `virtualList` scrolls inside `#dChapters` (max about 58vh, its own scrollbar). Jump-to writes `scrollTop` on that box, not on the sheet.
- **Android:** the chapter `RecyclerView` is 420dp tall with a visible scrollbar. A touch on it calls `requestDisallowInterceptTouchEvent`, so the page behind it does not move.

## 4. Episodes and progress on every cover

- **Web:** `metaOf(m)` shows "EP 3 / 220 · 217 left", or "500 EP" when the title is not on your shelf. A thin bar on the cover, coloured by status, shows the fraction seen.
- **Android:**
  - A `Progress` cache maps media id to (status, progress). It is loaded once per user from `/library`, then patched locally whenever the shelf saves.
  - Its `LiveData` version ticks, and every `CoverAdapter` rebinds.
  - Each tile shows the same "EP x / y · n left" line and a 3 dp progress bar along the cover's bottom edge.
  - Running series without a final count show a short stub plus the status.

## 5. Motion research and what was used

| Source / principle | Decision |
|---|---|
| Material 3 motion: emphasized easing `cubic-bezier(.2,0,0,1)`, 200–500 ms | One easing token for entrances on both platforms |
| **View Transitions API** (Chrome 111+, Safari 18+) | The cover morphs from its tile into the detail sheet (`view-transition-name: cover-morph`); tab switches cross-fade. Feature-detected, with a plain fallback |
| `IntersectionObserver` reveal instead of scroll handlers | Rails and tiles rise in once, staggered, without main-thread scroll work |
| Canvas sprite petals, not DOM nodes | One fixed full-viewport `<canvas>` behind everything, pre-rendered petal sprites, pauses when the tab is hidden. Petals wrap vertically when the page scrolls, so they never pile up above the viewport |
| Delight at the moment of intent | A petal burst where you press ♥; count-up numbers on episode totals; the ✕ row slides out (Android) |
| `prefers-reduced-motion` / `areAnimatorsEnabled()` | Web: canvas and reveals stop. Android: petals draw a still frame and bars snap to value |

**Android petals:** one `SakuraView` (22 petals) now sits behind the tab container in `activity_main.xml`, and the four tab roots are transparent, so it runs behind Home, For You, Discover and Library. Alpha is lowered (0.18–0.50) because petals now pass behind text. It only animates while visible.

## 6. How to re-measure

```powershell
node tools/bench_read.js                                   # cold/warm source latencies, single-flight, find
powershell -File tools/image_variants.ps1 build/bench/cover7.jpg 7
node tools/e2e_feedback.js                                 # feedback loop end to end (18 checks)
node tools/check_console.js                                # console ids + syntax
docker run --rm -v "${PWD}\backend:/src" -v aniga-m2:/root/.m2 -w /src maven:3.9-eclipse-temurin-21 mvn -q -B test
```
