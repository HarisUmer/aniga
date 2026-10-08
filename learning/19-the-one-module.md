# 19 — The whole thing, once

The other files in this folder each follow one thread. This one is the sitting where they meet: what Aniga is, how a tap becomes a chapter, and the decisions we looked at and did not ship.

## What it is

Aniga is a shelf for anime, manga, manhwa, and manhua. You rate what you finish. The shelf ranks what to open next, and the phone or the browser opens the chapter. A friend can receive one title in chat, or a link to a taste report: your scores, and the genres that pull you in.

Two clients, one API.

```text
Android app (Java)  ─┐
                     ├─ HTTP/JSON ─ Spring Boot 4 ─ Postgres 17
Web console (HTML) ──┘                    │
                                          ├─ reading adapters (MangaDex, Asura, MangaGeko, MangaTown, and the small free ones)
                                          ├─ AniList catalog, already imported
                                          └─ trace.moe, for a frame from an episode
```

The phone is the product. The console is the same shelf, wider, used to see the page, the reasons, and the sources without a device.

## How a tap becomes a chapter

1. You open a title. If it came from the catalog, `ChapterFinder` searches the reading sources for that name (and the native and romaji aliases) and ranks Asura, MangaGeko, and MangaTown ahead of MangaDex when they hit.
2. The detail page shows those hits as chips. The first chip loads. Tapping another chip throws that list away and loads the other source. That is the choice of where to read.
3. Chapters come back as a list. The list lives in its own box: on the web, `#dChapters` scrolls inside a panel about half a screen tall; on the phone, the chapter `RecyclerView` is 420dp with its own scrollbar, and a finger on it does not drag the cover. Read from the start, the latest, or a number you type.
4. The reader asks that source for page URLs and draws them. Leaving the reader writes your place onto the library row, including which source you were on.
5. Library later asks those sources whether a newer chapter exists than the one you had seen. If it does, the shelf says so.

Anime is the same shelf and the same 1–10 score. It does not have pages here. The episode list is metadata: tapping one marks you watched up to there. The hanko under "Your score" saves for anime and for comics through the same library call.

## How the ranking works

Two layers, and they stay.

**V1, always available.** Your scores are centred on your own average, so a 5 from someone who rates everything a 9 is a dislike. Genres and the words in the synopsis pull in opposite directions. A title you have not touched gets `0.55` from genres and `0.45` from that text, plus a small popularity tie-break. Cold start, before any rating, is popularity. With nobody rated yet, a high catalog score (the AniList percent) now moves a title more than it used to, so a beloved smaller work can pass a bigger, poorly scored one.

**V2, when the trained model is loaded.** Item-item neighbours, a two-tower vector, the V1 content score, popularity, and a media-type prior are blended with weights from the validation split. An anime-only shelf is not handed a pile of manhua. Every score, finish, or drop updates a small per-user weight on top. Titles shown often and never opened sink. The list is spread across franchises, with one or two slots that try a taste the model is unsure about.

What we did not change: we did not retrain. The blend already learns from ratings. A chatbot or a new model would sit beside this, not replace the reasons under each cover.

## Security, as it stands

| What | Where |
|---|---|
| Session token on private routes. `/api/users/{id}` must be you. | `AuthFilter` |
| Admin reload and health-run need the admin token, compared without stopping at the first wrong byte. | `AuthFilter.same` |
| Every response says not to sniff the type, not to frame the page off-site, and not to send a referrer. | `AuthFilter.harden` |
| The taste report is a random token, not your user id. The HTML escapes what you typed. Covers are included only if the URL is https. | `ShareReportController` |
| Passwords are hashed. The phone never sends a Firebase token to our API. | `PasswordHasher`, `AuthFilter` |

What we left alone on purpose. A strict script policy would break the console, which is one page with inline layout. A sign-in throttle keyed on the socket address would lock everyone, because the public site sits behind one proxy and every request would look like the same machine. That throttle belongs on the real client address, once the proxy is configured to pass it.

## A small model on the server

Thought about, not built. The live machine has 908 MB of RAM. A chat model worth talking to wants more than that, on top of Postgres and the API. The disk was full for a different reason: Ubuntu, a 2 GB swap file, and the three container images. The database is 18 MB. The measurements and the shrink to a 512 MB swap file are in [20-disk-and-memory.md](20-disk-and-memory.md). The useful first version of "ask for an anime" would call the three searches we have: title, story, and a picture. A local model, later, would only turn a sentence into those calls.

## Motion

The shelf already opens as a manga page: three panels, a caption, a burst. Petals run behind the console. The cover you press flies into the detail sheet.

The figures that cross the bottom of the console are original ink drawings. Naruto, the Straw Hats, a Ghibli still: those pictures belong to someone else, and we do not have a license to animate them. If a title you are reading ever lends a figure, it has to be art we made or art we are allowed to use. The runner is the placeholder that can be swapped for that, not a copy of a character.

`prefers-reduced-motion` hides the runners and stops the petals.

## How the code is split

The backend was already one package per job: `auth`, `read`, `recommend`, `user`, `chat`, `share`, `search`, `people`, `source`, `web`. A new source is a new class in `read`. A new screen does not reach into another screen's tables.

The console was one script. It is now nine files, loaded in this order, and they share the names at the top of `core.js` (`api`, `state`, `$`):

| File | Job |
|---|---|
| `core.js` | The request helper, the page chrome, petals, the windowed list |
| `shelf.js` | Tonight's three-panel page |
| `foryou.js` | Taste bars and the ranked list |
| `rate.js` | The 1–10 hanko |
| `detail.js` | The title sheet and its chapter box |
| `discover.js` | Title, story, and picture search |
| `library.js` | The record |
| `reader.js` | The page |
| `sources.js` | The catalog of sites |

On the phone, `DetailActivity` is the cover, the story, and the score. `ReadingPane` is the source chips and the chapter box. `check_console.js` still checks that every id the scripts touch exists on the page.

## Where to read next

| If you want | Open |
|---|---|
| The diary | [01](01-worklog.md) |
| Why these pieces | [02](02-architecture-decisions.md) |
| The tables | [03](03-database.md) |
| The console | [04](04-backend-and-web-console.md) |
| Sources | [05](05-sources-and-scraping.md), [09](09-sources-v2.md), [18](18-adding-a-source.md) |
| Recommendations | [06](06-recommender-v1.md), [12](12-training-report.md), [15](15-feedback-learning.md) |
| The look | [08](08-design-system.md), [16](16-performance-and-motion.md) |
| The live machine | [17](17-the-live-stack.md), [20](20-disk-and-memory.md) |
| The map of what is next | [14](14-knowledge-base-and-roadmap.md) |
