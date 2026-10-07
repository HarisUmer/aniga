# 13 — Search by description and by image

Discover has three modes, the same on the web and in the app: **探 Title · 語 Story · 絵 Image**.

## Story search — BM25 over what a title is about

`GET /api/search?q=girl reborn as the villainess&type=&limit=` → `DescriptionIndex.search`

- **Documents:** one per catalog title, built from the title, native title, synopsis, tags, genres and **character names**, so "Himmel" finds Frieren.
- **Field weights:** the title counts 3×, tags and genres 2×, and the native title, synopsis and character names 1×. Weights are applied by repeating term counts.
- **Tokens:** NFKD-normalised, lowercase, split on non-letters, ~70 stopwords removed (including "anime", "manhwa", "story" and "looking"). A crude suffix stemmer joins `reincarnated` and `reincarnation`, or `dungeons` and `dungeon`.
- **Synonyms:** fan vocabulary is expanded at half weight into the words AniList descriptions actually use, e.g. `isekai` → another, world, transported, summoned; `op` → overpowered, strongest; `regression` → return, past, second chance.
- **Scoring:** BM25 with `k1 = 1.2`, `b = 0.75`, plus a tiny popularity prior (`0.002·popularity`) so the famous title wins ties:

```text
score(d, q) = Σ_t IDF(t) · tf(t,d)·(k1+1) / (tf(t,d) + k1·(1 − b + b·|d|/avgdl))
```

- The index is built in memory on the first request (well under a second for 902 docs) and rebuilt when the catalog row count changes.
- Each hit returns the `matched` terms, which are shown as a badge so the user sees **why** it matched.

Verified: "boy reborn as the villain of a novel" → *I Become A Villain In My Novel* first, then *The Art of Reincarnation*, *Reborn to be Supreme*.

**Why BM25 and not embeddings?** It needs no model, no GPU and no vector database; it is explainable through the matched words; and at 902 documents it is instant. Semantic embeddings (e.g. a small sentence transformer stored in pgvector) are the planned upgrade for paraphrases such as "sad elf after the hero dies".

## Image search — two complementary engines

`POST /api/search/image?scenes=true` (multipart `file`, ≤ 8 MB) or `GET /api/search/image?url=`.

| Input | Engine | Output |
|---|---|---|
| Anime screenshot | [trace.moe](https://soruly.github.io/trace.moe-api/) (free API, anonymous quota) | AniList id, **episode, timestamp**, similarity, preview clip → mapped to our catalog row by `anilist_id` |
| Cover or panel | Local **perceptual hash** over all 901 covers | Closest covers by Hamming distance |

### dHash (difference hash), in `ImageSearchService`

1. Convert to grayscale and resize to 9×8.
2. For each row, bit = `left pixel > right pixel`, giving 64 bits.
3. Distance = `popcount(a XOR b)`; similarity = `1 − d/64`.

The hash survives scaling, JPEG recompression and small colour shifts, but not crops or rotations. All 901 covers are hashed in the background once the app is ready (`ApplicationReadyEvent`), downloaded in parallel on virtual threads, and kept in memory.

Verified:

- the Chainsaw Man cover → itself at distance 0 (similarity 1.0);
- a Frieren still → episode 1 at 9:55 with 98% similarity (trace.moe), mapped to the local entry;
- a 3-byte fake image → HTTP 400.

### Safety

- The URL variant rejects non-http(s) schemes and any host that resolves to loopback, private or link-local addresses (SSRF guard).
- Uploads are capped at 8 MB.
- The Android client downscales to 1280 px JPEG before upload.
- trace.moe is opt-in (`scenes=true`) because the anonymous quota is small; if it fails, `sceneError` is returned and cover matches still work.

## UI

- **Web:** a drag-and-drop zone, file picker, or **paste** (Ctrl+V a screenshot). Scene hits show an "EP 1 · 9:55" badge, covers "100% cover".
- **Android:** Photo Picker (`PickVisualMedia`, no storage permission). Downscaling and upload run on an executor, with the same badges.

## Next

- pgvector + sentence embeddings for semantic story search; hybrid BM25 + vector with reciprocal rank fusion.
- CLIP image embeddings for panels and fan art (dHash only catches near-duplicates).
- Self-host trace.moe's open-source stack for our own anime if the quota becomes a problem.
