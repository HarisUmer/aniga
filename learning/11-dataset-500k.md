# 11 — 500,000-user synthetic dataset over the real catalog

## Why synthetic

There are no real users yet. Public datasets (MovieLens, MAL dumps) do not cover manhwa/manhua and include no reading progress. A simulator with **hidden ground-truth taste** lets us:

1. train every model on the same data;
2. measure whether a model recovers taste we know is there;
3. reproduce everything from a seed.

The limitation, and how to say it in an interview: the numbers show which **method** works better under realistic structure. They do not predict real-world accuracy, so the first real users will be needed to re-tune.

## Generator

`ml/synthetic/generate_users_500k.py --users 500000 --seed 11` runs in 47 s (vectorised NumPy). It reads `ml/data/catalog.csv`, exported from Postgres by `ml/data/export_catalog.py`, so the simulation uses the real 902 titles and their real genres and tags.

### Hidden variables per user (never given to the models)

| Variable | Meaning |
|---|---|
| 1–2 of 16 **personas** + personal noise | Taste over 17 genres and 170 tags (dark shonen, romance, power fantasy, cultivation, villainess, cozy, horror, sports, isekai, mecha…) |
| Segment | `watcher` (anime only) 30%, `reader` (comics only) 25%, `both` 45% |
| Mainstreamness | How much popularity pulls the user's picks |
| Activity | Titles consumed; heavy-tailed: mean 31.6, median 22 |
| Patience | Lowers the drop probability |
| Rater bias, spread and propensity | Generous or harsh, narrow or wide, how often they rate at all |

### Per (user, title) event: what a real app records

```text
affinity a = persona·features + noise        z = a standardised within the user's own picks
enjoyment e = sigmoid(1.5 z + 0.35 + 0.25 a)
status:     COMPLETED / CURRENT / DROPPED / PAUSED / PLANNING, driven by e and patience
progress:   episodes or chapters; DROPPED stops early (proportional to e), CURRENT somewhere in the middle
score:      1..10 from e, rater bias and spread; only with probability = rating propensity
day:        ordering, so we can do a time-based split
```

## Output (`ml/data/v2/`, git-ignored)

`users.parquet`, `interactions.parquet`, `ground_truth/*` (personas and true affinities), `summary.json`.

| Statistic | Value |
|---|---|
| Users | 500,000 |
| Interactions | 15,797,156 |
| Ratings | 6,515,041 (mean 7.15, mode 8, like real sites) |
| Status share | completed 52%, current 22.9%, dropped 15%, planning 7%, paused 3.1% |
| By type | anime 8.70 M, manga 4.11 M, manhwa 2.18 M, manhua 0.81 M |

The rating histogram rises from 1 to a peak at 8 and falls at 10, which is the familiar J-ish shape of MAL/AniList scores.

## The training signal, one number per (user, title)

Defined once and used identically in Python and in `RecommenderV2.java`:

```text
rated:   clip((score - pivot) / 4.5, -1, 1) + 0.15 if COMPLETED, - 0.15 if DROPPED
         pivot = user's own mean if they have >= 3 scores, else 5.5        (a harsh 6 ≈ a generous 8)
unrated: COMPLETED 0.5 | CURRENT 0.2 + 0.3·completion | PAUSED 0.1 | PLANNING 0.1
         DROPPED -0.3 - 0.4·(1 - completion)                              (dropping at ep 1 is worse than at ep 20)
```

This directly answers "how many chapters they read, did they complete or drop halfway, and what rating did they give".
