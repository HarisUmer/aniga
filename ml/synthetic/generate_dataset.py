"""Synthetic Aniga world: items, users with hidden tastes, trends, and logged interaction sequences.

Why synthetic: we have no real users yet, and a simulator with KNOWN ground truth lets us check
whether a model recovered the true taste (e.g. "this user loves dark+revenge but dislikes school
tournaments"), which real data can never tell us.

What is hidden in the world (and written to ground_truth/ only):
  - per-user genre/tag affinities, rater bias, trend susceptibility, slow taste drift
  - per-item latent quality
  - pairwise tag interactions (non-linear effects a linear model cannot fit)
  - tag trend waves over time (what the RL re-ranker should learn to exploit)

Logged like a real app: slates shown by a popularity-biased logging policy with recorded
propensities, so off-policy (IPS) evaluation of an RL policy is possible.

Usage: python generate_dataset.py --users 1000 --items 3000 --days 180 --seed 7
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

import numpy as np

GENRES = ["Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mystery", "Romance", "Sci-Fi",
          "Slice of Life", "Sports", "Supernatural", "Thriller", "Psychological", "Historical", "Music"]

TAG_WORDS = {
    "shonen": ["determined", "hero", "power"], "seinen": ["mature", "gritty", "adult"],
    "school": ["classroom", "student", "club"], "tournament": ["arena", "bracket", "finals"],
    "friendship": ["bond", "comrades", "together"], "rivalry": ["rival", "challenge", "surpass"],
    "revenge": ["vengeance", "betrayal", "avenge"], "dark": ["despair", "bleak", "cruel"],
    "tragedy": ["loss", "grief", "sacrifice"], "survival": ["survive", "hunger", "escape"],
    "military": ["soldier", "army", "regiment"], "political": ["throne", "conspiracy", "faction"],
    "isekai": ["transported", "another-world", "summoned"], "reincarnation": ["reborn", "past-life", "memories"],
    "cultivation": ["qi", "meridians", "breakthrough"], "system": ["status-window", "quest", "level"],
    "dungeons": ["dungeon", "gate", "raid"], "overpowered": ["unstoppable", "strongest", "effortless"],
    "slow-burn": ["gradual", "longing", "glances"], "love-triangle": ["jealousy", "confession", "torn"],
    "found-family": ["household", "adopted", "belonging"], "wholesome": ["gentle", "warm", "cozy"],
    "time-travel": ["timeline", "paradox", "loop"], "detective": ["case", "clue", "deduction"],
    "mind-games": ["scheme", "bluff", "gambit"], "demons": ["demon", "hellfire", "pact"],
    "ghosts": ["spirit", "haunting", "exorcist"], "swordsman": ["blade", "katana", "duel"],
    "martial-arts": ["kung-fu", "dojo", "technique"], "sect": ["elder", "disciple", "clan"],
    "esports": ["ranked", "guild", "match"], "idol": ["stage", "concert", "fans"],
    "cooking": ["recipe", "kitchen", "flavor"], "office": ["coworker", "deadline", "salary"],
    "apocalypse": ["ruins", "collapse", "wasteland"], "monsters": ["beast", "monster", "horde"],
    "magic-academy": ["spellbook", "academy", "mana"], "villainess": ["noble", "duke", "ballroom"],
    "regression": ["return", "second-chance", "redo"], "mecha": ["pilot", "robot", "cockpit"],
    "space": ["galaxy", "starship", "orbit"], "gore": ["blood", "carnage", "brutal"],
    "gag-comedy": ["absurd", "slapstick", "chaos"], "coming-of-age": ["growing", "youth", "identity"],
}
TAGS = list(TAG_WORDS)
FILLER = ["story", "world", "journey", "life", "secret", "fate", "city", "night", "promise", "dream",
          "past", "truth", "battle", "heart", "power", "family", "mystery", "village", "kingdom", "storm"]

TYPES = ["ANIME", "MANGA", "MANHWA", "MANHUA"]

# Archetype -> (genre prefs, tag prefs, type prefs). Values are affinities in roughly [-1, 1].
ARCHETYPES = {
    "dark_shonen": ({"Action": .6, "Drama": .4, "Horror": .4, "Comedy": -.4, "Sports": -.5},
                    {"dark": .9, "revenge": .8, "tragedy": .5, "survival": .6, "school": -.5, "tournament": -.6,
                     "friendship": -.2, "wholesome": -.6}, {"ANIME": .3, "MANGA": .3}),
    "romance_drama": ({"Romance": .8, "Drama": .6, "Slice of Life": .4, "Horror": -.7, "Action": -.2},
                      {"slow-burn": .8, "love-triangle": .4, "school": .3, "wholesome": .5, "gore": -.9,
                       "dark": -.4}, {"ANIME": .2, "MANHWA": .3}),
    "power_fantasy": ({"Action": .6, "Fantasy": .7, "Adventure": .4, "Romance": -.3},
                      {"system": .8, "dungeons": .7, "overpowered": .6, "regression": .6, "reincarnation": .5,
                       "slow-burn": -.4}, {"MANHWA": .6, "MANHUA": .2}),
    "cultivation": ({"Fantasy": .7, "Action": .5, "Historical": .3, "Sci-Fi": -.3},
                    {"cultivation": .9, "sect": .7, "martial-arts": .7, "revenge": .3, "office": -.6},
                    {"MANHUA": .7}),
    "thinker": ({"Mystery": .8, "Thriller": .7, "Psychological": .8, "Comedy": -.2},
                {"mind-games": .9, "detective": .8, "time-travel": .6, "political": .4, "overpowered": -.5},
                {"ANIME": .2, "MANGA": .3}),
    "cozy": ({"Slice of Life": .8, "Comedy": .6, "Music": .4, "Horror": -.8, "Thriller": -.4},
             {"wholesome": .9, "found-family": .8, "cooking": .6, "idol": .4, "gore": -.9, "dark": -.6},
             {"ANIME": .4}),
}
ARCH_NAMES = list(ARCHETYPES)

# Non-linear pairwise effects: (tag_a, tag_b, archetype, delta). An MLP can learn these; a linear scorer cannot.
INTERACTIONS = [
    ("dark", "school", "dark_shonen", -0.8),
    ("slow-burn", "gore", "romance_drama", -1.0),
    ("overpowered", "regression", "power_fantasy", 0.7),
    ("mind-games", "school", "thinker", 0.6),
    ("revenge", "cultivation", "cultivation", 0.6),
]


def vec(prefs: dict, keys: list[str]) -> np.ndarray:
    return np.array([prefs.get(k, 0.0) for k in keys])


def build_items(rng, n_items, days):
    type_p = np.array([.35, .25, .25, .15])
    items = []
    for i in range(n_items):
        t = rng.choice(TYPES, p=type_p)
        genres = list(rng.choice(GENRES, size=rng.integers(1, 4), replace=False))
        tags = list(rng.choice(TAGS, size=rng.integers(3, 7), replace=False))
        words = [w for tag in tags for w in rng.choice(TAG_WORDS[tag], size=2, replace=False)]
        words += list(rng.choice(FILLER, size=6))
        rng.shuffle(words)
        items.append(dict(
            item_id=i, media_type=t, title=f"{t.title()} Title {i:04d}", genres=genres, tags=tags,
            synopsis=" ".join(words), quality=float(rng.normal(0, 1)),
            release_day=int(rng.integers(-365, days)), hype_width=float(rng.uniform(10, 40)),
            hype_peak=float(rng.gamma(1.5, 0.4))))
    return items


def build_trends(rng, days):
    """A few tags get a wave of hype (like a hit adaptation making 'regression' trend for two months)."""
    trend_tags = rng.choice(TAGS, size=6, replace=False)
    waves = []
    for tag in trend_tags:
        waves.append(dict(tag=str(tag), center=int(rng.integers(20, days - 20)), width=float(rng.uniform(10, 30)),
                          strength=float(rng.uniform(0.6, 1.4))))
    return waves


def tag_trend(waves, tag, day):
    return sum(w["strength"] * np.exp(-((day - w["center"]) ** 2) / (2 * w["width"] ** 2))
               for w in waves if w["tag"] == tag)


def build_users(rng, n_users):
    users = []
    for u in range(n_users):
        mix = rng.dirichlet(np.full(len(ARCH_NAMES), 0.3))
        main = ARCH_NAMES[int(np.argmax(mix))]
        g = sum(m * vec(ARCHETYPES[a][0], GENRES) for m, a in zip(mix, ARCH_NAMES))
        t = sum(m * vec(ARCHETYPES[a][1], TAGS) for m, a in zip(mix, ARCH_NAMES))
        ty = sum(m * vec(ARCHETYPES[a][2], TYPES) for m, a in zip(mix, ARCH_NAMES))
        users.append(dict(
            user_id=u, archetype=main, mix=mix,
            genre_aff=g + rng.normal(0, .15, len(GENRES)), tag_aff=t + rng.normal(0, .15, len(TAGS)),
            type_aff=ty, bias=float(rng.normal(0, .8)), trend_sus=float(rng.beta(2, 5)),
            drift=rng.normal(0, .25, len(TAGS)), activity=float(rng.lognormal(0, .5))))
    return users


def utility(user, item, day, days, waves, g_idx, t_idx, ty_idx):
    gi = [g_idx[g] for g in item["genres"]]
    ti = [t_idx[t] for t in item["tags"]]
    tag_aff = user["tag_aff"] + user["drift"] * (day / days)  # slow taste drift: what the LSTM should track
    u = user["genre_aff"][gi].mean() + tag_aff[ti].mean() + user["type_aff"][ty_idx[item["media_type"]]]
    u += 0.35 * item["quality"]
    for a, b, arch, delta in INTERACTIONS:
        if a in item["tags"] and b in item["tags"]:
            u += delta * user["mix"][ARCH_NAMES.index(arch)]
    trend = max(tag_trend(waves, t, day) for t in item["tags"])
    hype = item["hype_peak"] * np.exp(-((day - item["release_day"] - 7) ** 2) / (2 * item["hype_width"] ** 2))
    return u + user["trend_sus"] * (trend + hype), trend + hype


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--users", type=int, default=1000)
    ap.add_argument("--items", type=int, default=3000)
    ap.add_argument("--days", type=int, default=180)
    ap.add_argument("--slate", type=int, default=10)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--out", default=str(Path(__file__).resolve().parent.parent / "data"))
    args = ap.parse_args()

    rng = np.random.default_rng(args.seed)
    out = Path(args.out)
    (out / "ground_truth").mkdir(parents=True, exist_ok=True)

    items = build_items(rng, args.items, args.days)
    waves = build_trends(rng, args.days)
    users = build_users(rng, args.users)
    g_idx = {g: i for i, g in enumerate(GENRES)}
    t_idx = {t: i for i, t in enumerate(TAGS)}
    ty_idx = {t: i for i, t in enumerate(TYPES)}
    base_pop = np.array([np.exp(0.8 * it["quality"]) for it in items])
    release = np.array([it["release_day"] for it in items])
    peak = np.array([it["hype_peak"] for it in items])
    width = np.array([it["hype_width"] for it in items])

    events, ratings = [], []
    for user in users:
        n_sessions = rng.poisson(user["activity"] * args.days / 7)
        consumed: set[int] = set()
        for day in np.sort(rng.integers(0, args.days, n_sessions)):
            released = release <= day
            hype = peak * np.exp(-((day - release - 7) ** 2) / (2 * width ** 2))
            # Logging policy: popularity^0.7 with 20% uniform exploration. Its probabilities are saved for IPS.
            score = (base_pop * (1 + hype)) ** 0.7 * released
            p = 0.8 * score / score.sum() + 0.2 * released / released.sum()
            slate = rng.choice(len(items), size=args.slate, replace=False, p=p)
            for pos, i in enumerate(slate):
                it = items[i]
                util, trend = utility(user, it, day, args.days, waves, g_idx, t_idx, ty_idx)
                events.append((user["user_id"], i, int(day), "IMPRESSION", 0.0, pos, float(p[i])))
                click_p = 1 / (1 + np.exp(-(1.6 * util - 1.2 - 0.15 * pos)))
                if i in consumed or rng.random() > click_p:
                    continue
                events.append((user["user_id"], i, int(day), "CLICK", 0.0, pos, float(p[i])))
                consumed.add(i)
                units = int(rng.geometric(1 / (1 + np.exp(1.2 * util)) * 0.5 + 0.05))
                kind = "WATCH_EPISODE" if it["media_type"] == "ANIME" else "READ_CHAPTER"
                events.append((user["user_id"], i, int(day), kind, float(units), pos, float(p[i])))
                if units <= 2:
                    events.append((user["user_id"], i, int(day), "DROP", float(units), pos, float(p[i])))
                if units >= 3 and rng.random() < 0.6:
                    s = int(np.clip(round(5.5 + user["bias"] + 2.2 * util + rng.normal(0, .8)), 1, 10))
                    ratings.append((user["user_id"], i, s, int(day)))
                    events.append((user["user_id"], i, int(day), "RATE", float(s), pos, float(p[i])))

    with open(out / "items.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["item_id", "media_type", "title", "genres", "tags", "synopsis", "release_day"])
        for it in items:
            w.writerow([it["item_id"], it["media_type"], it["title"], "|".join(it["genres"]), "|".join(it["tags"]),
                        it["synopsis"], it["release_day"]])
    with open(out / "users.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["user_id"])
        w.writerows([[u["user_id"]] for u in users])
    with open(out / "events.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["user_id", "item_id", "day", "event_type", "value", "slate_position", "logging_propensity"])
        w.writerows(events)
    with open(out / "ratings.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["user_id", "item_id", "score", "day"])
        w.writerows(ratings)

    truth_users = [dict(user_id=u["user_id"], archetype=u["archetype"], mix=u["mix"].round(3).tolist(),
                        bias=round(u["bias"], 3), trend_susceptibility=round(u["trend_sus"], 3),
                        top_tags=[TAGS[k] for k in np.argsort(-u["tag_aff"])[:5]],
                        bottom_tags=[TAGS[k] for k in np.argsort(u["tag_aff"])[:5]]) for u in users]
    (out / "ground_truth" / "users.json").write_text(json.dumps(truth_users, indent=1))
    (out / "ground_truth" / "items_quality.json").write_text(
        json.dumps({it["item_id"]: round(it["quality"], 3) for it in items}))
    (out / "ground_truth" / "trends.json").write_text(json.dumps(waves, indent=1))
    (out / "ground_truth" / "interactions.json").write_text(json.dumps(INTERACTIONS, indent=1))

    scores = np.array([r[2] for r in ratings]) if ratings else np.array([0])
    summary = dict(users=len(users), items=len(items), days=args.days, events=len(events), ratings=len(ratings),
                   rating_mean=round(float(scores.mean()), 2), rating_std=round(float(scores.std()), 2),
                   rating_hist={int(k): int(v) for k, v in zip(*np.unique(scores, return_counts=True))},
                   trend_tags=[w["tag"] for w in waves], seed=args.seed)
    (out / "summary.json").write_text(json.dumps(summary, indent=1))
    print(json.dumps(summary, indent=1))


if __name__ == "__main__":
    main()
