"""Offline test bench for the feedback loop (the "reinforcement" part of recommender v2).

Fresh simulated users get a full hidden taste over all 902 titles (same personas/maths as the 500k generator), so
we know the TRUE enjoyment of anything we recommend, not only of what they happened to pick.

Each round (≈ one week) the policy shows 10 titles. The user examines them with position bias, opens what looks
good, rates what they opened, sometimes presses "Not interested" on bad picks or "Like" on great ones. Policies:

  inductive    trained blend only; the history grows with rated titles (the model is inductive, so it adapts a bit)
  +buttons     LIKE / NOT_INTERESTED become history signals and hide the title
  +online      + per-user online weights θ (logistic SGD on every reward) + impression fatigue  [OnlineLearner.java]
  +explore     + 1–2 Thompson exploration slots                                                 [RecommenderV2.explore]
  +recency     + signals lose half their pull every HALF_LIFE days                              [UserSignals.recency]

Experiments: steady taste, and taste DRIFT (persona replaced halfway; old history now misleads).
Metrics per round: good@10 = share of the 10 shown titles the user truly enjoys (enjoyment >= 0.6),
enjoy@10 = mean true enjoyment of the shown list, clicks, cumulative reward = Σ enjoyment of opened titles.

Usage: python ml/eval/simulate_feedback.py [--users 1500 --rounds 30]
Writes ml/models/v2/feedback_sim.json and learning/assets/feedback-sim.png
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
MODEL = ROOT / "ml" / "models" / "v2"
spec = importlib.util.spec_from_file_location("gen", ROOT / "ml" / "synthetic" / "generate_users_500k.py")
gen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gen)

TYPES = gen.TYPES
DAYS_PER_ROUND = 7
K = 10


def z(S):
    return (S - S.mean(1, keepdims=True)) / S.std(1, keepdims=True).clip(1e-6)


class World:
    """Catalog, trained artifacts and the hidden-taste machinery shared by all policies."""

    def __init__(self, rng):
        cat = gen.load_catalog()
        self.cat = cat
        self.n = len(cat)
        genres, tags, G, T = gen.item_features(cat)
        self.G, self.T = G, T
        self.P_g, self.P_t, self.P_y = gen.persona_matrices(genres, tags)
        self.item_type = cat.media_type.map(TYPES.index).to_numpy()
        pop = np.log1p(cat.popularity_raw.fillna(cat.popularity_raw.median()).to_numpy())
        self.pop = ((pop - pop.mean()) / pop.std()).astype(np.float32)
        self.quality = ((cat.average_score.fillna(70).to_numpy() - 72) / 8).astype(np.float32)
        # trained model, exactly what the backend serves
        self.V = np.load(MODEL / "item_vectors.npy")
        self.Wp = np.load(MODEL / "w_pos.npy")
        self.Wn = np.load(MODEL / "w_neg.npy")
        self.B = np.load(MODEL / "ease_served.npy")
        self.F = np.load(MODEL / "item_features.npy")
        m = json.loads((MODEL / "metrics.json").read_text())
        self.blend = m["blend_weights"]
        self.blend_short = m.get("blend_short_weights", self.blend)
        self.short_max = m.get("short_max_history", 0)
        # online-learner features, same rule as OnlineLearner.features(): genres 1, type 1, first 8 tags 0.5
        names = {}
        rows, cols, vals = [], [], []
        for i, r in enumerate(cat.itertuples()):
            feats = {f"g:{g}": 1.0 for g in r.genres}
            feats[f"type:{r.media_type}"] = 1.0
            for t in r.tags[:8]:
                feats.setdefault(f"t:{t}", 0.5)
            norm = np.sqrt(sum(v * v for v in feats.values()))
            for f, v in feats.items():
                rows.append(i)
                cols.append(names.setdefault(f, len(names)))
                vals.append(v / norm)
        self.X = np.zeros((self.n, len(names)), np.float32)
        self.X[rows, cols] = vals
        self.rng = rng

    def users(self, n):
        rng = self.rng
        p1 = rng.integers(0, len(gen.PERSONAS), n)
        p2 = rng.integers(0, len(gen.PERSONAS), n)
        mix = np.where(rng.random(n) < 0.55, 1.0, rng.uniform(0.5, 0.8, n)).astype(np.float32)
        segment = rng.choice(3, n, p=[0.45, 0.30, 0.25])  # both, watcher, reader
        return {"p1": p1, "p2": p2, "mix": mix, "segment": segment,
                "mainstream": rng.normal(0.55, 0.3, n).clip(-0.2, 1.4).astype(np.float32),
                "bias": rng.normal(7.0, 0.8, n).clip(4.5, 9.3).astype(np.float32),
                "spread": rng.uniform(0.7, 1.4, n).astype(np.float32),
                "noise_g": rng.normal(0, 0.25, (n, self.P_g.shape[1])).astype(np.float32),
                "noise_t": rng.normal(0, 0.12, (n, self.P_t.shape[1])).astype(np.float32),
                "noise_i": rng.normal(0, 0.35, (n, self.n)).astype(np.float32)}

    def enjoyment(self, u):
        w = u["mix"][:, None]
        U_g = w * self.P_g[u["p1"]] + (1 - w) * self.P_g[u["p2"]] + u["noise_g"]
        U_t = w * self.P_t[u["p1"]] + (1 - w) * self.P_t[u["p2"]] + u["noise_t"]
        tb = w * self.P_y[u["p1"]] + (1 - w) * self.P_y[u["p2"]]
        tb[u["segment"] == 1, 1:] = -8.0
        tb[u["segment"] == 2, 0] = -8.0
        aff = U_g @ self.G.T + U_t @ self.T.T + 0.6 * tb[:, self.item_type] + 0.35 * self.quality[None] + u["noise_i"]
        # Same calibration as the generator: judged against what this user would typically pick.
        logits = 1.3 * aff + u["mainstream"][:, None] * self.pop[None] + self.rng.gumbel(size=aff.shape)
        picks = np.argsort(-logits, 1)[:, :25]
        pa = np.take_along_axis(aff, picks, 1)
        zaff = (aff - pa.mean(1, keepdims=True)) / pa.std(1, keepdims=True).clip(1e-3)
        return (1 / (1 + np.exp(-(1.5 * zaff + 0.35 + 0.25 * aff)))).astype(np.float32), logits

    def score_of(self, u, e):
        raw = u["bias"][:, None] + u["spread"][:, None] * 2.6 * (2 * e - 1) - 0.5 + self.rng.normal(0, 0.8, e.shape)
        return np.clip(np.round(raw), 1, 10)


def run(world: World, users: dict, policy: set, rounds: int, drift_at: int | None, seed: int,
        eta=1.2, online_w=0.9, half_life=120.0, beta=1.6, init=10) -> dict:
    rng = np.random.default_rng(seed)
    world.rng = np.random.default_rng(seed + 1)  # identical users/enjoyment across policies
    n_users, n = len(users["p1"]), world.n
    e, logits = world.enjoyment(users)
    H = np.zeros((n_users, n), np.float32)          # signal per (user, title), 0 = unseen
    age = np.zeros((n_users, n), np.float32)        # days since the signal was set
    hidden = np.zeros((n_users, n), bool)           # NOT_INTERESTED / LIKE / consumed
    shown = np.zeros((n_users, n), np.int16)
    clicked = np.zeros((n_users, n), bool)
    theta = np.zeros((n_users, world.X.shape[1]), np.float32)
    evid = np.zeros_like(theta)
    rewards = np.zeros(n_users, np.int32)

    # initial history: the user's own top picks, all rated
    first = np.argsort(-logits, 1)[:, :init]
    rows = np.repeat(np.arange(n_users), init)
    sc = world.score_of(users, e[rows, first.ravel()].reshape(n_users, init))
    score_sum = sc.sum(1)
    score_n = np.full(n_users, init, np.float32)
    for r in range(n_users):
        piv = sc[r].mean() if init >= 3 else 5.5
        H[r, first[r]] = np.clip((sc[r] - piv) / 4.5, -1, 1) + 1e-3
        hidden[r, first[r]] = True
        if "+online" in policy:
            for j, s in zip(first[r], sc[r]):
                theta[r], evid[r] = step(theta[r], evid[r], world.X[j], (s - 1) / 9, eta)
                rewards[r] += 1

    hist = {"good@10": [], "enjoy@10": [], "clicks": [], "reward": [], "not_interested": []}
    cum = np.zeros(n_users)
    for t in range(rounds):
        if drift_at is not None and t == drift_at:
            users = {**users, "p1": world.rng.integers(0, len(gen.PERSONAS), n_users),
                     "noise_g": world.rng.normal(0, 0.25, users["noise_g"].shape).astype(np.float32),
                     "noise_t": world.rng.normal(0, 0.12, users["noise_t"].shape).astype(np.float32)}
            users["p2"] = users["p1"]
            users["mix"] = np.ones(n_users, np.float32)
            e, _ = world.enjoyment(users)

        S = base_scores(world, H, age if "+recency" in policy else None, half_life)
        if "+online" in policy:
            S = S + online_w * theta @ world.X.T
            fat = (shown >= 3) & ~clicked
            S = S - np.where(fat, np.minimum(0.9, 0.15 * (shown - 2)), 0)
        S[hidden] = -np.inf
        if "+buttons" not in policy:
            S[(H != 0)] = -np.inf
        order = np.argsort(-S, 1)
        slots = np.where(rewards < 30, 2, 1) if "+explore" in policy else np.zeros(n_users, int)
        recs = np.zeros((n_users, K), np.int64)
        for r in range(n_users):
            ex = order[r, :K - slots[r]]
            if slots[r]:
                pool = order[r, K - slots[r]:max(40, n // 4)]
                unc = (world.X[pool] / np.sqrt(1 + evid[r])).sum(1)
                samp = S[r, pool] + beta * unc * rng.standard_normal(len(pool))
                pick = pool[np.argsort(-samp)[:slots[r]]]
                ex = np.insert(ex, [min(3, len(ex)), min(7, len(ex))][:slots[r]], pick)
            recs[r] = ex[:K]

        er = np.take_along_axis(e, recs, 1)
        hist["good@10"].append(float((er >= 0.6).mean()))
        hist["enjoy@10"].append(float(er.mean()))
        exam = rng.random((n_users, K)) < 1 / np.log2(np.arange(K) + 2)
        click = exam & (rng.random((n_users, K)) < np.clip(0.05 + 0.8 * er ** 2, 0, 1))
        ni = exam & ~click & (er < 0.35) & (rng.random((n_users, K)) < 0.35)
        like = exam & ~click & (er > 0.7) & (rng.random((n_users, K)) < 0.15)
        scores = world.score_of(users, er)
        hist["clicks"].append(float(click.sum(1).mean()))
        hist["not_interested"].append(float(ni.sum(1).mean()))
        cum += (er * click).sum(1)
        hist["reward"].append(float(cum.mean()))

        age[H != 0] += DAYS_PER_ROUND
        for r in range(n_users):
            for p in range(K):
                j = recs[r, p]
                shown[r, j] += 1
                if click[r, p]:
                    clicked[r, j] = True
                    score_sum[r] += scores[r, p]
                    score_n[r] += 1
                    piv = score_sum[r] / score_n[r] if score_n[r] >= 3 else 5.5
                    H[r, j] = np.clip((scores[r, p] - piv) / 4.5, -1, 1) + 1e-3
                    age[r, j] = 0
                    hidden[r, j] = True
                    if "+online" in policy:
                        theta[r], evid[r] = step(theta[r], evid[r], world.X[j], 0.7, eta)
                        theta[r], evid[r] = step(theta[r], evid[r], world.X[j], (scores[r, p] - 1) / 9, eta)
                        rewards[r] += 2
                elif "+buttons" in policy and (ni[r, p] or like[r, p]):
                    H[r, j] = 0.6 if like[r, p] else -0.6
                    age[r, j] = 0
                    hidden[r, j] = True
                    if "+online" in policy:
                        theta[r], evid[r] = step(theta[r], evid[r], world.X[j], 1.0 if like[r, p] else 0.0, eta)
                        rewards[r] += 1
    return hist


def step(theta, evid, x, r, eta):
    p = 1 / (1 + np.exp(-2 * float(theta @ x)))
    return np.clip(theta + eta * (r - p) * x, -3, 3), evid + x


def base_scores(world: World, H: np.ndarray, age, half_life):
    w = np.abs(H)
    if age is not None:
        w = w * np.maximum(0.15, 0.5 ** (age / half_life))
    pos = np.where(H > 0, w, 0)
    neg = np.where(H < 0, w, 0)
    pos /= pos.sum(1, keepdims=True).clip(1e-6)
    neg /= neg.sum(1, keepdims=True).clip(1e-6)
    U = (pos @ world.V) @ world.Wp.T - (neg @ world.V) @ world.Wn.T
    U /= np.linalg.norm(U, axis=1, keepdims=True).clip(1e-6)
    parts = {"tower": U @ world.V.T, "ease": H @ world.B, "content": (H @ world.F) @ world.F.T,
             "pop": np.tile(world.pop, (len(H), 1))}
    onehot = np.eye(4, dtype=np.float32)[world.item_type]
    seen = (H != 0).astype(np.float32)
    counts = seen @ onehot
    parts["type"] = np.log((counts + 0.5) / (counts.sum(1, keepdims=True) + 2.0))[:, world.item_type]
    mainstream = (seen @ world.pop) / seen.sum(1).clip(1)
    parts["mpop"] = mainstream[:, None] * world.pop[None]
    n_hist = seen.sum(1)
    long_s = sum(world.blend.get(k, 0) * z(v) for k, v in parts.items() if world.blend.get(k, 0))
    short_s = sum(world.blend_short.get(k, 0) * z(v) for k, v in parts.items() if world.blend_short.get(k, 0))
    return np.where((n_hist <= world.short_max)[:, None], short_s, long_s).astype(np.float32)


def summarize(h, rounds, drift_at):
    g = np.array(h["good@10"])
    out = {"good@10_first5": round(float(g[:5].mean()), 4), "good@10_last5": round(float(g[-5:].mean()), 4),
           "good@10_mean": round(float(g.mean()), 4), "enjoy@10_mean": round(float(np.mean(h["enjoy@10"])), 4),
           "clicks_per_round": round(float(np.mean(h["clicks"])), 3),
           "not_interested_per_round": round(float(np.mean(h["not_interested"])), 3),
           "cumulative_reward": round(h["reward"][-1], 3)}
    if drift_at is not None:
        before = g[drift_at - 5:drift_at].mean()
        after = g[drift_at:]
        rec = next((i for i, v in enumerate(after) if v >= 0.9 * before), None)
        out.update({"good@10_before_drift": round(float(before), 4), "good@10_right_after": round(float(after[0]), 4),
                    "rounds_to_recover_90pct": rec})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--users", type=int, default=1500)
    ap.add_argument("--rounds", type=int, default=30)
    ap.add_argument("--seed", type=int, default=5)
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    t0 = time.time()
    world = World(np.random.default_rng(args.seed))
    world.rng = np.random.default_rng(args.seed + 1)
    users = world.users(args.users)

    policies = {
        "inductive": set(),
        "+buttons": {"+buttons"},
        "+online": {"+buttons", "+online"},
        "+explore": {"+buttons", "+online", "+explore"},
        "+recency": {"+buttons", "+online", "+explore", "+recency"},
    }
    results = {"steady": {}, "drift": {}, "sweeps": {}}
    curves = {}
    for exp, drift in [("steady", None), ("drift", args.rounds // 2)]:
        for name, pol in policies.items():
            h = run(world, users, pol, args.rounds, drift, args.seed)
            results[exp][name] = summarize(h, args.rounds, drift)
            curves[(exp, name)] = h["good@10"]
            print(f"{exp:6} {name:10} {results[exp][name]}  ({time.time() - t0:.0f}s)", flush=True)

    # Parameter sweeps on the full policy under drift (smaller population, same seed).
    small = {k: v[:600] for k, v in users.items()}
    full = policies["+recency"]
    for label, kw in [("eta", [{"eta": v} for v in (0.4, 1.2, 2.5)]),
                      ("online_w", [{"online_w": v} for v in (0.4, 0.9, 1.6)]),
                      ("half_life", [{"half_life": v} for v in (30.0, 60.0, 120.0, 365.0)]),
                      ("beta", [{"beta": v} for v in (0.0, 0.8, 1.6, 3.0)])]:
        results["sweeps"][label] = {}
        for p in kw:
            h = run(world, small, full, args.rounds, args.rounds // 2, args.seed, **p)
            s = summarize(h, args.rounds, args.rounds // 2)
            results["sweeps"][label][str(list(p.values())[0])] = s
            print(f"sweep {label}={list(p.values())[0]:<6} {s}", flush=True)

    results["config"] = {"users": args.users, "rounds": args.rounds, "days_per_round": DAYS_PER_ROUND,
                         "seconds": round(time.time() - t0, 1)}
    (MODEL / "feedback_sim.json").write_text(json.dumps(results, indent=1))

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(1, 2, figsize=(12, 4.2), sharey=True)
    colors = {"inductive": "#85796A", "+buttons": "#7C9CE0", "+online": "#4DB38A", "+explore": "#EFB23B",
              "+recency": "#E5532D"}
    for ax, exp in zip(axes, ["steady", "drift"]):
        for name in policies:
            ax.plot(curves[(exp, name)], label=name, color=colors[name], lw=2)
        if exp == "drift":
            ax.axvline(args.rounds // 2, color="#999", ls="--", lw=1)
            ax.text(args.rounds // 2 + 0.3, 0.05, "taste changes", color="#666", fontsize=9)
        ax.set_title(f"{exp} taste — share of shown titles the user truly enjoys")
        ax.set_xlabel("round (≈ week)")
        ax.grid(alpha=.25)
    axes[0].set_ylabel("good@10")
    axes[0].legend()
    fig.tight_layout()
    out = ROOT / "learning" / "assets" / "feedback-sim.png"
    fig.savefig(out, dpi=130)
    print(f"wrote {out} and {MODEL / 'feedback_sim.json'} in {time.time() - t0:.0f}s")


if __name__ == "__main__":
    main()
