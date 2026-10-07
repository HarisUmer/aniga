# Synthetic data

**Code:** `ml/synthetic/generate_dataset.py` (numpy only). **Output:** `ml/data/` (git-ignored; regenerate any time).

```powershell
python ml/synthetic/generate_dataset.py --users 1000 --items 3000 --days 180 --seed 7
```

## Why not just random numbers?

Purely random ratings contain no pattern, so any model "learns" noise and every result is meaningless. Instead the generator builds a small **world with hidden rules**, then logs what an app would see. Because we wrote the rules, we can check whether a model rediscovers them. Real data can never tell you the true reason someone rated something 5.

## The world

| Piece | How it's generated | Why |
|-------|--------------------|-----|
| Items | type (35% anime, 25% manga, 25% manhwa, 15% manhua), 1–3 genres, 3–6 tags, synopsis built from tag-specific words + filler, latent quality, release day, hype curve | TF-IDF has real signal to find; quality gives a popularity effect |
| Users | mixture of 6 archetypes (dark_shonen, romance_drama, power_fantasy, cultivation, thinker, cozy) via Dirichlet(0.3), plus noise, rater bias, trend susceptibility, slow tag drift, activity level | Realistic mixed tastes; drift is what the LSTM must track |
| Interactions | 5 planted tag pairs, e.g. dark×school is negative for dark_shonen users | Non-linear effect only an MLP can learn |
| Trends | 6 tags get a Gaussian hype wave somewhere in the 180 days | What the RL re-ranker should exploit |

Hidden utility of user *u* for item *i* on day *t*:

```
U = mean(genre_aff[genres]) + mean((tag_aff + drift·t/T)[tags]) + type_aff[type]
  + 0.35·quality + Σ planted interaction effects
  + trend_susceptibility · (tag trend wave + item release hype)
```

## Logging (what the app would record)

1. Each session, a **logging policy** shows a slate of 10: probability ∝ (popularity·(1+hype))^0.7, mixed with 20% uniform exploration. Each impression stores its **propensity** (needed for unbiased off-policy evaluation of the RL policy).
2. Click probability = sigmoid(1.6·U − 1.2 − 0.15·slot): better items and higher slots get more clicks (position bias).
3. After a click, the number of chapters/episodes consumed is geometric, longer when U is high; ≤ 2 units logs a DROP.
4. If ≥ 3 units were consumed, the user rates with probability 0.6: `score = clip(round(5.5 + bias + 2.2·U + noise), 1, 10)`.

Step 4 makes ratings **missing-not-at-random**: people mostly rate what they finished, so the observed mean (7.1) is higher than the true average opinion. This mirrors real MyAnimeList data, and a good evaluation has to account for it.

## Output files

| File | Rows (default run) | Columns |
|------|--------------------|---------|
| `items.csv` | 3,000 | item_id, media_type, title, genres, tags, synopsis, release_day |
| `users.csv` | 1,000 | user_id (everything else is hidden) |
| `events.csv` | ~494,000 | user_id, item_id, day, event_type, value, slate_position, logging_propensity |
| `ratings.csv` | ~28,000 | user_id, item_id, score, day |
| `summary.json` | — | counts, rating histogram, trending tags, seed |
| `ground_truth/*.json` | — | archetypes, true top/bottom tags, item quality, trend waves, interactions. **Evaluation only, never a training input.** |

`events.csv` deliberately uses the same event types as the Postgres `interaction_event` table, so a model trained on synthetic data can later be fed real app logs.

## Determinism

Same `--seed` → identical files. Report the seed with every experiment.
