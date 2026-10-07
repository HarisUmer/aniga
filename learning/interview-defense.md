# Interview defense: questions and answers

Short answers first; the linked docs have depth.

## Architecture

**Q: Walk me through the architecture.**
Android (Java) client → Spring Boot REST API → Postgres. The API also calls MangaDex (chapters) and later AniList/Jikan (metadata). Recommendations run in the API today; the research models will be exported to ONNX and served there too. Supabase replaces the local Postgres when we need auth and realtime chat. ([02](02-architecture-decisions.md))

**Q: Why not let Android talk to the database or MangaDex directly?**
Credentials can't be kept secret in an APK, and a server layer gives us caching, rate limiting, one place to change a source, and logging of every impression for ML.

**Q: Why Postgres and not Firebase/Firestore?**
Our core data is relational (users × titles × ratings × events) and our ML pipeline needs SQL exports. Supabase is Postgres plus auth and realtime, so we get Firebase-like features without leaving SQL. Firebase's strength, offline sync, is covered by Room on the device.

**Q: Why Docker for local dev?**
One command gives everyone the same Postgres version, schema and seed. Resetting is `down -v`. The backend also compiles inside Docker, so nobody needs a matching JDK.

**Q: Why no ORM?**
Arrays, GIN indexes and upserts are clearer in SQL, and the domain is small. I'd add JPA if the domain grew lots of relationships and write logic. ([ADR-5](02-architecture-decisions.md))

**Q: What would you change for production?**
Flyway migrations, connection pool tuning, caching MangaDex/AniList responses, auth (Supabase JWT), rate limiting per user, structured logging and metrics, cached recommender index, CI that runs the Docker build and tests.

## Sources and scraping

**Q: How do you support many manga sources?**
A `SourceAdapter` interface (popular, latest, search, details, chapters, pages) with one implementation per API or CMS family and per-site config in the database. That's how Mihon's community extensions scale to over a thousand sites. ([05](05-sources-and-scraping.md))

**Q: Where did the 1,778 sources come from?**
Parsed from Keiyoushi's public extension index, the same file our earlier research identified. We only store metadata (name, language, URL, NSFW flag); the script never contacts the sites.

**Q: Why only MangaDex for reading?**
It's the official API with clear terms (credit MangaDex and scanlation groups, no ads). Most mirror sites republish licensed work without permission, so I designed the adapter system but chose not to ship scrapers for them. The architecture is the same; only the legal target differs.

**Q: Tell me about a bug you found.**
MangaDex@Home cache nodes return 404 on a cold miss. The API test passed (we got 13 URLs), but in the browser only 1 of 13 images rendered. I reproduced it, confirmed the origin server had the files, and added fallback URLs. A never-opened chapter then loaded 16/16. Lesson: test what the user sees, not just the API response. ([04](04-backend-and-web-console.md))

## Recommender V1

**Q: How does your recommender work?**
Centre each rating on the user's mean, build genre weights with shrinkage, build a signed TF-IDF profile over synopsis and tags, blend 0.55 genre + 0.45 text, and return reasons. ([06](06-recommender-v1.md))

**Q: Why centre ratings?**
Raters have different scales. A 7 from a harsh rater is positive; a 7 from someone who averages 9 is negative. Without centring, every rated item counts as "liked" and the 5-vs-10 distinction disappears.

**Q: How does it explain "Naruto 5, Attack on Titan 10"?**
Both are action/shonen, so their genre contributions roughly cancel. In text space, Naruto's terms (tournament, friendship, training) get negative weight and AoT's (dark, survival, political) positive. The taste endpoint shows exactly those lists for the demo user.

**Q: Why TF-IDF and not embeddings?**
It works with a few hundred documents, needs no training, every weight is inspectable, and it runs in plain Java on a phone. Embeddings are a planned upgrade when the catalog is large and synopses are longer.

**Q: What are its weaknesses?**
Linear (can't learn "dark + school is bad"), no time order, no trends, no stemming, hand-set blend weights. Each one maps to a research question.

## Research: LSTM + MLP + RL

**Q: Why an LSTM? Aren't Transformers better?**
On large dense datasets, often yes. On small or sparse data, recurrent models stay competitive and are cheaper (Betello et al. 2024 found GRU4Rec ahead at small embedding sizes). I picked LSTM as the primary model for its size and simplicity, and included SASRec as a baseline so the choice is tested, not assumed.

**Q: What does the feature MLP add?**
Non-linear crosses between tags, genres and text features: things like "this user likes dark, but not dark school settings". The simulator plants five such interactions so we can measure whether the MLP finds them.

**Q: Where does reinforcement learning come in?**
A re-ranker on top of the scorer, framed as a contextual bandit: context = model scores + trend features + slot, reward = click + progress + centred rating − drops. Discounted LinUCB forgets old observations, so it adapts when trends change. Full deep RL (SlateQ) is a simulator-only extension because it's data-hungry and hard to evaluate offline.

**Q: Why a bandit rather than DQN?**
Rewards are immediate, the non-stationarity is in the reward (trends), the state is tiny (matrices you can update in Java), it has regret guarantees, and it's evaluable offline with logged propensities. DQN-style RL earns its complexity when you optimise long-term outcomes like retention.

**Q: How do you evaluate an RL policy without deploying it?**
Two ways. In the simulator, compare cumulative reward to the oracle that knows true utility. On logged data, use inverse propensity scoring, self-normalised IPS and doubly robust estimators, which is why every logged impression stores its propensity.

**Q: Isn't training on synthetic data cheating?**
It's a development tool, not evidence of real-world performance. The simulator has known ground truth, so it can tell us whether a model recovers planted effects; that's impossible with real data. Real-data results (public MAL datasets, later our own logs) are reported separately, and the ground-truth files are never used for training or tuning.

**Q: How do you avoid data leakage?**
Split by time (train days 0–120, validate 120–150, test 150–180), never randomly, because trends and drift would leak the future into training.

**Q: How do you stop the recommender from only pushing popular titles?**
Measure it (coverage, exposure Gini), keep exploration in the bandit, and test an exposure-aware reward that discounts slots that already get attention.

**Q: Which metrics?**
RMSE/MAE for ratings, NDCG@10 / Recall@10 for ranking, coverage, diversity and novelty beyond accuracy, cumulative reward/regret and IPS/DR estimates for the bandit, and precision@5 of attributions against planted reasons for explainability.

**Q: What if the LSTM doesn't beat the baselines?**
Then that's the result, and it's reported. Published work has shown neural recommenders often fail to beat well-tuned simple baselines (Dacrema et al. 2019). The staged design still stands: each stage can be swapped for whatever wins.

## Sources v2

**Q: How do you know "all extensions work"?**
A smoke script walks every readable catalog extension through the same endpoints the apps use: popular → chapters → pages. 62 of 63 pass. Afrikaans is genuinely empty on MangaDex, and the UI says so instead of failing. Separately, a health check hits all 1,778 site home pages and classifies them UP / MOVED / BLOCKED / DOWN.

**Q: Why not scrape the other 1,700 sites?**
Most host unlicensed scans. We list and health-check them. Reading in-app started with official APIs and openly licensed comics; AsuraComic, MangaTown, and MangaGeko were added later as HTML adapters. Adding a source is one class implementing `SourceAdapter`.

**Q: The nastiest bug?**
MangaDex's `includeExternalUrl=1` reads like "include external too" but means "only external". Every chapter list came back empty for hosted titles. The second was licensed titles still matching the language filter after their chapters were removed. I fixed that by deriving "popular" from recently published readable chapters.

## Recommender v2

**Q: Why a synthetic dataset, and isn't that cheating?**
There are no users yet, and public datasets lack manhwa/manhua and reading progress. The simulator has hidden personas, so I can check whether a model recovers taste I know exists. I state the limit plainly: it ranks *methods*, and real data must re-tune the weights.

**Q: Results?**
On 50k test users, nDCG@10 is 0.0406 for popularity, 0.0445 for content, 0.0815 for EASE, 0.0825 for the two-tower and 0.0841 for the blend. With only 3 known titles, the two-tower wins because its item side uses genre/tag features.

**Q: EASE is strong; why does it get weight 0 in the blend?**
It is strongly correlated with the two-tower, so it adds little once the tower is in. Content adds more diversity. EASE still powers "More like this", where an item-item row is exactly what you want.

**Q: How does a brand-new user get personal results without retraining?**
The user tower has no per-user parameters. The user vector is computed from the embeddings of what they liked and disliked, so three ratings immediately give a vector.

**Q: Why were the top results all sequels, and how did you fix it?**
Sequels share neighbours and embeddings. A greedy rerank allows one title per franchise key in the first pass, then fills in what it skipped. It costs nothing in accuracy and makes the list far more useful.

**Q: How do ratings and progress become one signal?**
A score is centred on the user's own mean (a harsh 6 equals a generous 8) and clipped to ±1. Completion adds a small bonus and dropping a small penalty. Unrated titles use status and completion, and dropping early is worse than dropping late. The same formula runs in Python and Java.

## Search

**Q: Why BM25 instead of embeddings for description search?**
902 documents, no GPU, explainable (we show the matched words), and instant. A synonym table bridges fan words ("isekai", "op") to description vocabulary. Embeddings in pgvector are the next step for paraphrases.

**Q: How does reverse image search work?**
Two engines. trace.moe finds the anime, episode and timestamp from a screenshot. A 64-bit difference hash over our 901 covers finds a cover by Hamming distance. dHash survives resizing and recompression. Crops, mirrors and covers embedded in a wide page are handled by hashing several query variants: trimmed by edge-majority background, mirrored, centre crops, and 2:3 side windows. Each cover also stores a second "centre 80%" hash, and the best distance wins. CLIP embeddings are next for drawings and fan art. The URL variant blocks private and loopback hosts (SSRF).

## Feedback learning and performance (session 6)

**Q: Is this really reinforcement learning?**
It is a contextual bandit, the one-step form of RL. Show a list (action), observe a reward (rating, ♥, ✕, open, finish, drop), update the policy, and explore where uncertain. Full deep RL needs millions of logged sessions and is hard to explain. The bandit updates on every event, and each weight has a readable name.

**Q: A user loved gore, then rates a gore title 3/10. What happens, exactly?**
The model predicts `p = σ(2·θ·x)` before seeing the rating, say 0.8. The reward is (3−1)/9 = 0.22. Each of the title's features (Horror, gore, its type, other genres) moves by `η·(r−p)·x_f`, about −0.25 for genres in the e2e test. Romance does not move, because it is not in this title's features. The change is logged with the expectation, so the UI says "expected 80%, got 22%: Horror −0.25".

**Q: How do you stop one boost from taking over the feed?**
Boost is +0.3σ of the blend score, tuned until a boosted Romance took 11 of 20 slots, not 20 of 20. Mute is −4σ, which removes it. Both are shown first in the taste view and can be cleared.

**Q: How did you choose η, the online weight and β without real users?**
With a simulator: 1,500 users with hidden tastes from the dataset generator, 30 weekly rounds, and a scenario where every taste flips at round 15. Online learning gives +31% cumulative reward under drift and recovers in about 10 rounds; the trained model alone never recovers. β = 0 scored best only because simulated users have no hidden tastes to discover, so β was kept small (0.8) rather than removed.

**Q: Why does a click only count if we recommended the title?**
A click on something found via search says nothing about the list. Impressions are logged with `track=true`, and only clicks on logged impressions become a 0.7 reward. This also keeps CTR measurable for a later A/B test.

**Q: "Find chapters" took 18 s. How did you get it to under 4?**
I profiled with a benchmark script. The cost was not CPU but rate-limit tokens: about 120 per-language `/aggregate` calls at 4.5 per second. The fixes:
- skip languages the search result says do not exist;
- compare languages for the 4 best matches only;
- run the counts in parallel;
- put deadlines on slow sources.

That took it from 18.0 s to 3.8 s cold, with the same results.

**Q: Why virtualize the episode list instead of paginating it?**
Users jump to "episode 420", and a windowed list keeps the native scrollbar and jump-to while rendering only about 16 rows. Rows are a fixed 64 px, so the offset maths is exact, and a ResizeObserver repaints when content above the list changes height.

## Behavioural

**Q: What would you do differently?**
Add Flyway from day one and write the Android client against the API contract earlier. I'd also prototype the MangaDex reader in the browser on day one, since that's where the image-loading bug showed up.

**Q: What are you proudest of?**
That every decision is measurable: the recommender explains itself, the simulator has ground truth, and the logging schema was designed for the RL evaluation before any model exists.
