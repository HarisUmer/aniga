"""500k synthetic users over the REAL Aniga catalog (exported from Postgres), with hidden ground-truth taste.

Each user consumes titles; for each one we log what a reading/watching app actually records:
  status (PLANNING / CURRENT / COMPLETED / DROPPED / PAUSED), progress in episodes or chapters,
  completion ratio, an optional 1-10 score, and the day it happened.

Hidden rules (ground truth, never a training input):
  - 16 taste personas over AniList genres and tags; each user mixes 1-2 personas + personal noise
  - type preference: anime-only watchers, readers-only, and both
  - mainstreamness (how much popularity pulls the user), activity level, patience, rater bias/spread,
    rating propensity
  - enjoyment e_ui = sigmoid(1.5 * z + 0.35 + 0.25 * affinity), z = affinity standardised within the user's own
    picks; it drives completion, dropping and the score

Usage:
  python ml/data/export_catalog.py            # writes ml/data/catalog.csv from the running Postgres
  python ml/synthetic/generate_users_500k.py --users 500000 --seed 11
Output (ml/data/v2/): users.parquet, interactions.parquet, ground_truth/*.parquet|json, summary.json
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "ml" / "data" / "catalog.csv"
OUT = ROOT / "ml" / "data" / "v2"

TYPES = ["ANIME", "MANGA", "MANHWA", "MANHUA"]
STATUS = ["PLANNING", "CURRENT", "COMPLETED", "DROPPED", "PAUSED"]

# persona -> (genre weights, tag keyword weights, type bias). Tag keywords match AniList tag slugs by substring,
# so the personas survive catalog refreshes even if individual tags come and go.
PERSONAS: dict[str, tuple[dict, dict, dict]] = {
    "dark_shonen": ({"Action": .6, "Drama": .4, "Horror": .4, "Comedy": -.4, "Romance": -.3, "Slice of Life": -.6},
                    {"revenge": .9, "tragedy": .7, "gore": .6, "survival": .6, "anti-hero": .6, "war": .4,
                     "school": -.4, "cgdct": -.8, "iyashikei": -.8}, {"ANIME": .2, "MANGA": .3}),
    "battle_shonen": ({"Action": .8, "Adventure": .5, "Comedy": .3, "Romance": -.2},
                      {"shounen": .8, "super-power": .7, "martial-arts": .4, "male-protagonist": .3, "josei": -.6},
                      {"ANIME": .4, "MANGA": .2}),
    "romance": ({"Romance": .9, "Drama": .6, "Slice of Life": .3, "Horror": -.8, "Action": -.3},
                {"love-triangle": .6, "heterosexual": .5, "shoujo": .6, "josei": .5, "coming-of-age": .3,
                 "gore": -.9}, {"MANHWA": .3, "ANIME": .1}),
    "romcom": ({"Comedy": .8, "Romance": .7, "Drama": -.2, "Horror": -.6},
               {"school": .5, "romantic": .5, "slapstick": .4, "tsundere": .5, "tragedy": -.6, "gore": -.8},
               {"ANIME": .2, "MANGA": .3}),
    "power_fantasy": ({"Action": .6, "Fantasy": .7, "Adventure": .3, "Romance": -.3, "Slice of Life": -.4},
                      {"dungeon": .9, "video-games": .6, "reincarnation": .5, "full-color": .5, "revenge": .4,
                       "magic": .3, "time-manipulation": .3}, {"MANHWA": .8, "MANHUA": .2}),
    "cultivation": ({"Fantasy": .7, "Action": .6, "Sci-Fi": -.3, "Romance": -.2},
                    {"cultivation": .9, "martial-arts": .8, "wuxia": .8, "xianxia": .8, "chinese": .4,
                     "reincarnation": .4, "revenge": .3}, {"MANHUA": 1.0, "MANHWA": .2}),
    "thinker": ({"Mystery": .8, "Psychological": .8, "Thriller": .7, "Sci-Fi": .3, "Comedy": -.2},
                {"detective": .8, "crime": .6, "time-manipulation": .6, "philosophy": .5, "anti-hero": .3,
                 "slapstick": -.5}, {"ANIME": .3, "MANGA": .3}),
    "cozy": ({"Slice of Life": .9, "Comedy": .5, "Music": .3, "Horror": -.9, "Thriller": -.5},
             {"iyashikei": .9, "cgdct": .7, "food": .6, "episodic": .4, "gore": -.9, "tragedy": -.4},
             {"ANIME": .5}),
    "horror": ({"Horror": .9, "Supernatural": .6, "Psychological": .5, "Comedy": -.3, "Romance": -.4},
               {"body-horror": .8, "gore": .7, "survival": .6, "urban-fantasy": .3, "demons": .4,
                "iyashikei": -.8}, {"MANGA": .5}),
    "sports": ({"Sports": 1.0, "Comedy": .3, "Drama": .3, "Horror": -.6, "Fantasy": -.3},
               {"volleyball": .6, "basketball": .6, "baseball": .5, "football": .5, "boxing": .5, "team": .4},
               {"ANIME": .5, "MANGA": .2}),
    "isekai_fan": ({"Fantasy": .8, "Adventure": .5, "Comedy": .4, "Psychological": -.2},
                   {"isekai": 1.0, "magic": .5, "reincarnation": .5, "video-games": .4, "harem": .2},
                   {"ANIME": .5, "MANGA": .2}),
    "mecha_scifi": ({"Sci-Fi": .9, "Mecha": .8, "Action": .4, "Romance": -.3, "Slice of Life": -.3},
                    {"space": .7, "robots": .6, "military": .5, "cyberpunk": .6, "post-apocalyptic": .5,
                     "aliens": .4}, {"ANIME": .7}),
    "seinen_historical": ({"Drama": .6, "Action": .4, "Psychological": .3, "Comedy": -.3},
                          {"seinen": .8, "samurai": .7, "swordplay": .5, "war": .5, "politics": .6,
                           "philosophy": .4, "cgdct": -.7}, {"MANGA": .7}),
    "villainess": ({"Romance": .8, "Fantasy": .6, "Drama": .5, "Action": -.3, "Horror": -.6},
                   {"villainess": 1.0, "nobility": .6, "female-protagonist": .6, "time-skip": .2,
                    "reincarnation": .4, "gore": -.8}, {"MANHWA": .9}),
    "music_idol": ({"Music": 1.0, "Slice of Life": .5, "Drama": .3, "Horror": -.5},
                   {"idol": .8, "band": .7, "performing-arts": .6, "school": .2}, {"ANIME": .6}),
    "mahou": ({"Mahou Shoujo": 1.0, "Fantasy": .5, "Drama": .3},
              {"magic": .5, "female-protagonist": .5, "transformation": .5, "cgdct": .3}, {"ANIME": .6}),
}
PERSONA_NAMES = list(PERSONAS)


def load_catalog() -> pd.DataFrame:
    df = pd.read_csv(CATALOG, keep_default_na=False)
    df["genres"] = df["genres"].map(lambda s: [g for g in s.split("|") if g])
    df["tags"] = df["tags"].map(lambda s: [t for t in s.split("|") if t])
    for c in ["popularity_raw", "average_score", "episodes", "chapters"]:
        df[c] = pd.to_numeric(df[c], errors="coerce")
    return df.reset_index(drop=True)


def item_features(cat: pd.DataFrame, min_tag_count: int = 8):
    genres = sorted({g for gs in cat.genres for g in gs})
    tag_counts = pd.Series([t for ts in cat.tags for t in ts]).value_counts()
    tags = sorted(tag_counts[tag_counts >= min_tag_count].index)
    gi = {g: k for k, g in enumerate(genres)}
    ti = {t: k for k, t in enumerate(tags)}
    G = np.zeros((len(cat), len(genres)), np.float32)
    T = np.zeros((len(cat), len(tags)), np.float32)
    for r, (gs, ts) in enumerate(zip(cat.genres, cat.tags)):
        for g in gs:
            G[r, gi[g]] = 1
        for t in ts:
            if t in ti:
                T[r, ti[t]] = 1
    # 1/sqrt(n) so a title with 12 tags doesn't automatically beat one with 3.
    G /= np.sqrt(np.maximum(G.sum(1, keepdims=True), 1))
    T /= np.sqrt(np.maximum(T.sum(1, keepdims=True), 1))
    return genres, tags, G, T


def persona_matrices(genres, tags):
    P_g = np.zeros((len(PERSONAS), len(genres)), np.float32)
    P_t = np.zeros((len(PERSONAS), len(tags)), np.float32)
    P_y = np.zeros((len(PERSONAS), len(TYPES)), np.float32)
    for p, (gw, tw, yw) in enumerate(PERSONAS.values()):
        for g, w in gw.items():
            if g in genres:
                P_g[p, genres.index(g)] = w
        for kw, w in tw.items():
            for k, t in enumerate(tags):
                if kw in t:
                    P_t[p, k] = w
        for y, w in yw.items():
            P_y[p, TYPES.index(y)] = w
    return P_g, P_t, P_y


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--users", type=int, default=500_000)
    ap.add_argument("--seed", type=int, default=11)
    ap.add_argument("--chunk", type=int, default=25_000)
    ap.add_argument("--days", type=int, default=365)
    args = ap.parse_args()
    rng = np.random.default_rng(args.seed)
    t0 = time.time()

    cat = load_catalog()
    n_items = len(cat)
    genres, tags, G, T = item_features(cat)
    P_g, P_t, P_y = persona_matrices(genres, tags)
    item_type = cat.media_type.map(TYPES.index).to_numpy()
    pop = np.log1p(cat.popularity_raw.fillna(cat.popularity_raw.median()).to_numpy())
    pop = ((pop - pop.mean()) / pop.std()).astype(np.float32)
    quality = ((cat.average_score.fillna(70).to_numpy() - 72) / 8).astype(np.float32)
    finished = cat.status.isin(["FINISHED", "CANCELLED"]).to_numpy()
    total = np.where(item_type == 0, cat.episodes, cat.chapters).astype(float)
    # Running series without a final count: estimate how much exists so far.
    est = np.where(item_type == 0, 24, 60 + 240 * (pop - pop.min()) / (pop.max() - pop.min()))
    total = np.where(np.isnan(total) | (total <= 0), est, total).round().clip(1, 4000).astype(np.int32)

    N = args.users
    # --- users (hidden) ---
    n_personas = np.where(rng.random(N) < 0.55, 1, 2)
    p1 = rng.integers(0, len(PERSONAS), N)
    p2 = rng.integers(0, len(PERSONAS), N)
    mix = np.where(n_personas == 1, 1.0, rng.uniform(0.5, 0.8, N)).astype(np.float32)
    segment = rng.choice(["both", "watcher", "reader"], N, p=[0.45, 0.30, 0.25])
    activity = np.clip(np.round(rng.lognormal(np.log(22), 0.85, N)), 3, 400).astype(np.int32)
    mainstream = rng.normal(0.55, 0.3, N).clip(-0.2, 1.4).astype(np.float32)
    patience = rng.beta(4, 2, N).astype(np.float32)
    rater_bias = rng.normal(7.0, 0.8, N).clip(4.5, 9.3).astype(np.float32)
    rater_spread = rng.uniform(0.7, 1.4, N).astype(np.float32)
    rate_prop = rng.beta(2.2, 1.6, N).astype(np.float32)
    start_day = rng.integers(0, args.days // 2, N)

    users = pd.DataFrame({
        "user_id": np.arange(N, dtype=np.int32), "segment": segment, "activity": activity,
    })
    hidden_users = pd.DataFrame({
        "user_id": np.arange(N, dtype=np.int32), "persona_1": np.array(PERSONA_NAMES)[p1],
        "persona_2": np.where(n_personas == 2, np.array(PERSONA_NAMES)[p2], ""), "mix": mix,
        "segment": segment, "mainstream": mainstream, "patience": patience, "rater_bias": rater_bias,
        "rater_spread": rater_spread, "rate_prop": rate_prop,
    })

    parts, truth_parts = [], []
    for lo in range(0, N, args.chunk):
        hi = min(lo + args.chunk, N)
        n = hi - lo
        a1, a2, w = p1[lo:hi], p2[lo:hi], mix[lo:hi, None]
        U_g = w * P_g[a1] + (1 - w) * P_g[a2] + rng.normal(0, 0.25, (n, len(genres))).astype(np.float32)
        U_t = w * P_t[a1] + (1 - w) * P_t[a2] + rng.normal(0, 0.12, (n, len(tags))).astype(np.float32)
        type_bias = w * P_y[a1] + (1 - w) * P_y[a2]
        seg = segment[lo:hi]
        type_bias[seg == "watcher", 1:] = -8.0
        type_bias[seg == "reader", 0] = -8.0

        affinity = (U_g @ G.T) + (U_t @ T.T) + 0.6 * type_bias[:, item_type] + 0.35 * quality[None, :]
        affinity += rng.normal(0, 0.35, affinity.shape).astype(np.float32)

        # Which titles get picked: taste + popularity pull, sampled without replacement (Gumbel top-k).
        logits = 1.3 * affinity + mainstream[lo:hi, None] * pop[None, :]
        logits += rng.gumbel(size=logits.shape).astype(np.float32)
        order = np.argsort(-logits, axis=1)
        k = np.minimum(activity[lo:hi], n_items)
        kmax = int(k.max())
        picked = order[:, :kmax]
        valid = np.arange(kmax)[None, :] < k[:, None]
        rows = np.repeat(np.arange(n), k)
        items = picked[valid]
        aff = affinity[rows, items]
        # Users only pick what looks good, so enjoyment is judged against their OWN picks: a title can be
        # chosen and still disappoint. Without this every pick is loved and the rating mean drifts to 8.5.
        sel_mean = np.bincount(rows, aff, n) / k
        sel_sd = np.sqrt(np.maximum(np.bincount(rows, aff ** 2, n) / k - sel_mean ** 2, 1e-4))
        zaff = (aff - sel_mean[rows]) / sel_sd[rows]
        enjoy = 1 / (1 + np.exp(-(1.5 * zaff + 0.35 + 0.25 * aff)))

        m = len(items)
        u_glob = rows + lo
        tot = total[items]
        status = np.full(m, 2, np.int8)  # COMPLETED by default
        frac = np.ones(m, np.float32)

        planning = rng.random(m) < 0.07
        p_drop = (1 / (1 + np.exp(7 * (enjoy - 0.38)))) * (1.25 - patience[u_glob])
        dropped = ~planning & (rng.random(m) < p_drop)
        # Long series are dropped later in absolute terms but earlier as a fraction.
        frac_drop = rng.beta(1.3, 3.5, m) * np.clip(0.4 + enjoy, 0, 1)
        ongoing = ~finished[items]
        current = ~planning & ~dropped & (ongoing | (rng.random(m) < 0.18))
        frac_current = np.where(ongoing, rng.beta(6, 1.4, m), rng.beta(1.6, 1.6, m))
        paused = current & (rng.random(m) < 0.12)

        status[planning] = 0
        frac[planning] = 0
        status[current] = 1
        frac[current] = frac_current[current]
        status[paused] = 4
        status[dropped] = 3
        frac[dropped] = frac_drop[dropped]
        progress = np.round(frac * tot).astype(np.int32)
        progress = np.where((status == 3) | (status == 1) | (status == 4), np.maximum(progress, 1), progress)
        progress = np.where(status == 2, tot, np.minimum(progress, tot))
        frac = (progress / np.maximum(tot, 1)).astype(np.float32)

        p_rate = rate_prop[u_glob] * np.select([status == 2, status == 1, status == 4, status == 3],
                                               [1.0, 0.55, 0.45, 0.35], 0.0)
        rated = rng.random(m) < p_rate
        raw = rater_bias[u_glob] + rater_spread[u_glob] * 2.6 * (2 * enjoy - 1) - 0.5 + rng.normal(0, 0.8, m)
        raw -= np.where(status == 3, 1.0, 0.0)
        score = np.where(rated, np.clip(np.round(raw), 1, 10), 0).astype(np.int8)

        # Each user consumes in order over their active window; preference order is not time order.
        span = args.days - start_day[u_glob]
        day = (start_day[u_glob] + rng.random(m) * span).astype(np.int16)

        parts.append(pd.DataFrame({
            "user_id": u_glob.astype(np.int32), "media_id": cat.id.to_numpy()[items].astype(np.int32),
            "status": status, "progress": progress.astype(np.int16), "total": tot.astype(np.int16),
            "completion": frac.astype(np.float16), "score": score, "day": day,
        }))
        truth_parts.append(pd.DataFrame({"user_id": u_glob.astype(np.int32),
                                         "media_id": cat.id.to_numpy()[items].astype(np.int32),
                                         "enjoyment": enjoy.astype(np.float16)}))
        print(f"users {hi:>7}/{N}  rows so far {sum(len(p) for p in parts):>10,}  {time.time() - t0:5.1f}s", flush=True)

    inter = pd.concat(parts, ignore_index=True).sort_values(["user_id", "day"], kind="stable")
    truth = pd.concat(truth_parts, ignore_index=True)
    inter["status"] = pd.Categorical.from_codes(inter.status, STATUS)

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "ground_truth").mkdir(exist_ok=True)
    users.to_parquet(OUT / "users.parquet", index=False)
    inter.to_parquet(OUT / "interactions.parquet", index=False)
    hidden_users.to_parquet(OUT / "ground_truth" / "users_hidden.parquet", index=False)
    truth.to_parquet(OUT / "ground_truth" / "enjoyment.parquet", index=False)
    (OUT / "ground_truth" / "personas.json").write_text(json.dumps(
        {k: {"genres": v[0], "tags": v[1], "types": v[2]} for k, v in PERSONAS.items()}, indent=1))

    rated = inter[inter.score > 0]
    summary = {
        "seed": args.seed, "users": N, "items": int(n_items), "interactions": int(len(inter)),
        "interactions_per_user": {"mean": round(float(inter.groupby("user_id").size().mean()), 1),
                                  "median": float(np.median(activity))},
        "status_share": {k: round(float(v), 3) for k, v in inter.status.value_counts(normalize=True).items()},
        "ratings": int(len(rated)), "rating_mean": round(float(rated.score.mean()), 2),
        "rating_hist": {int(k): int(v) for k, v in rated.score.value_counts().sort_index().items()},
        "by_type": {t: int(v) for t, v in cat.set_index("id").media_type.reindex(inter.media_id).value_counts().items()},
        "segments": {k: int(v) for k, v in pd.Series(segment).value_counts().items()},
        "feature_dims": {"genres": len(genres), "tags": len(tags)},
        "seconds": round(time.time() - t0, 1),
    }
    (OUT / "summary.json").write_text(json.dumps(summary, indent=1))
    print(json.dumps(summary, indent=1))


if __name__ == "__main__":
    main()
