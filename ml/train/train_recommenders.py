"""Train and evaluate recommender v2 on the 500k-user synthetic dataset; export what the backend serves.

Models (same split, same metrics):
  pop      most-liked titles overall (baseline every model must beat)
  content  genre + tag profile built from the user's signals (what V1 does in Java)
  ease     EASE (Steck 2019): closed-form item-item regression, B = I - P / diag(P), P = (X'X + λI)^-1
  tower    inductive two-tower: item = id embedding + MLP(features); user = W_pos·mean(liked) + W_neg·mean(disliked)
           -> no per-user parameters, so it works for brand-new app users without retraining
  blend    z-scored mix of the above, weights tuned on a validation split

Signal per (user, title) — identical to RecommenderV2.java:
  rated:   clip((score - pivot)/4.5, -1, 1) + 0.15 if COMPLETED, - 0.15 if DROPPED     (pivot = user mean if >= 3 scores else 5.5)
  unrated: COMPLETED 0.5 | CURRENT 0.2 + 0.3·completion | PAUSED 0.1 | PLANNING 0.1 | DROPPED -0.3 - 0.4·(1 - completion)

Split: users with >= 8 titles; their last 20% by day is held out. Relevant = held-out title with signal >= 0.25.
Usage: python ml/train/train_recommenders.py [--epochs 12] [--dim 64]
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd
import scipy.sparse as sp
import torch
import torch.nn as nn
import torch.nn.functional as F

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "ml" / "data" / "v2"
CATALOG = ROOT / "ml" / "data" / "catalog.csv"
OUT = ROOT / "ml" / "models" / "v2"
SQL_OUT = ROOT / "infra" / "db" / "init" / "007_model_v2.sql"

STATUS = ["PLANNING", "CURRENT", "COMPLETED", "DROPPED", "PAUSED"]
RELEVANT = 0.25
K = (10, 20)


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


# ---------------------------------------------------------------- data
def signals(df: pd.DataFrame) -> np.ndarray:
    status = df.status.cat.codes.to_numpy() if hasattr(df.status, "cat") else df.status.map(STATUS.index).to_numpy()
    score = df.score.to_numpy().astype(np.float32)
    comp = df.completion.to_numpy().astype(np.float32)
    rated = score > 0
    g = df.assign(_s=np.where(rated, score, np.nan)).groupby("user_id")._s
    n_rated = g.transform("count").to_numpy()
    mean = g.transform("mean").to_numpy()
    pivot = np.where(n_rated >= 3, mean, 5.5)
    s_rated = np.clip((score - pivot) / 4.5, -1, 1) + np.where(status == 2, 0.15, 0) - np.where(status == 3, 0.15, 0)
    s_unrated = np.select([status == 2, status == 1, status == 4, status == 0, status == 3],
                          [0.5, 0.2 + 0.3 * comp, 0.1, 0.1, -0.3 - 0.4 * (1 - comp)], 0)
    return np.clip(np.where(rated, s_rated, s_unrated), -1, 1).astype(np.float32)


def item_features(cat: pd.DataFrame, min_tag_count: int = 8) -> tuple[np.ndarray, list[str]]:
    genres = sorted({g for gs in cat.genres for g in gs})
    counts = pd.Series([t for ts in cat.tags for t in ts]).value_counts()
    tags = sorted(counts[counts >= min_tag_count].index)
    types = ["ANIME", "MANGA", "MANHWA", "MANHUA"]
    names = [f"g:{g}" for g in genres] + [f"t:{t}" for t in tags] + [f"type:{t}" for t in types]
    idx = {n: i for i, n in enumerate(names)}
    Fm = np.zeros((len(cat), len(names)), np.float32)
    for r, row in enumerate(cat.itertuples()):
        for g in row.genres:
            Fm[r, idx[f"g:{g}"]] = 1
        for t in row.tags:
            if f"t:{t}" in idx:
                Fm[r, idx[f"t:{t}"]] = 0.7
        Fm[r, idx[f"type:{row.media_type}"]] = 1
    return Fm / np.linalg.norm(Fm, axis=1, keepdims=True).clip(1e-6), names


# ---------------------------------------------------------------- metrics
def evaluate(name: str, score_fn, users: np.ndarray, train_csr: sp.csr_matrix, test_rel: dict, test_bad: dict,
             n_items: int, batch: int = 4096) -> dict:
    hits = {k: [] for k in K}
    ndcg10, bad10, seen_items = [], [], set()
    disc = 1 / np.log2(np.arange(2, 22))
    for lo in range(0, len(users), batch):
        u = users[lo:lo + batch]
        S = score_fn(u).astype(np.float32)
        hist = train_csr[u]
        S[hist.nonzero()] = -np.inf
        top = np.argpartition(-S, 20, axis=1)[:, :20]
        top = np.take_along_axis(top, np.argsort(-np.take_along_axis(S, top, 1), axis=1), 1)
        for r, uid in enumerate(u):
            rel = test_rel.get(uid)
            if not rel:
                continue
            t = top[r]
            seen_items.update(t[:10].tolist())
            mask = np.isin(t, list(rel))
            for k in K:
                hits[k].append(mask[:k].sum() / min(len(rel), k))
            ideal = disc[:min(len(rel), 10)].sum()
            ndcg10.append((mask[:10] * disc[:10]).sum() / ideal)
            bad = test_bad.get(uid, ())
            bad10.append(np.isin(t[:10], list(bad)).sum() / 10 if bad else 0.0)
    out = {f"recall@{k}": round(float(np.mean(hits[k])), 4) for k in K}
    out.update({"ndcg@10": round(float(np.mean(ndcg10)), 4), "bad@10": round(float(np.mean(bad10)), 4),
                "coverage@10": round(len(seen_items) / n_items, 3), "users": len(ndcg10)})
    log(f"{name:10} " + "  ".join(f"{k}={v}" for k, v in out.items()))
    return out


def diagnose(name: str, score_fn, users: np.ndarray, train_csr: sp.csr_matrix, test_rel: dict, item_type: np.ndarray,
             segment: np.ndarray, batch: int = 4096) -> dict:
    """Where a model fails: nDCG@10 by history length and segment, and type leak (top-10 items of a media type
    the user never touched — e.g. manhua for an anime-only watcher)."""
    disc = 1 / np.log2(np.arange(2, 12))
    rows = []
    for lo in range(0, len(users), batch):
        u = users[lo:lo + batch]
        S = score_fn(u).astype(np.float32)
        hist = train_csr[u]
        S[hist.nonzero()] = -np.inf
        top = np.argpartition(-S, 10, axis=1)[:, :10]
        top = np.take_along_axis(top, np.argsort(-np.take_along_axis(S, top, 1), axis=1), 1)
        for r, uid in enumerate(u):
            rel = test_rel.get(uid)
            if not rel:
                continue
            h = hist[r].indices
            seen_types = set(item_type[h].tolist())
            mask = np.isin(top[r], list(rel))
            nd = (mask * disc).sum() / disc[:min(len(rel), 10)].sum()
            leak = float(np.mean([item_type[i] not in seen_types for i in top[r]]))
            rows.append((len(h), segment[uid], nd, leak))
    df = pd.DataFrame(rows, columns=["n", "segment", "ndcg", "leak"])
    df["bucket"] = pd.cut(df.n, [0, 10, 30, 10_000], labels=["<=10", "11-30", ">30"])
    out = {"type_leak@10": round(float(df.leak.mean()), 4),
           "ndcg_by_history": {str(k): round(float(v), 4) for k, v in df.groupby("bucket", observed=True).ndcg.mean().items()},
           "ndcg_by_segment": {str(k): round(float(v), 4) for k, v in df.groupby("segment").ndcg.mean().items()},
           "leak_by_segment": {str(k): round(float(v), 4) for k, v in df.groupby("segment").leak.mean().items()}}
    log(f"diag {name:8} {out}")
    return out


# ---------------------------------------------------------------- two-tower
class TwoTower(nn.Module):
    def __init__(self, n_items: int, feats: torch.Tensor, dim: int):
        super().__init__()
        self.register_buffer("feats", feats)
        self.id_emb = nn.Embedding(n_items, dim)
        nn.init.normal_(self.id_emb.weight, std=0.05)
        self.mlp = nn.Sequential(nn.Linear(feats.shape[1], 128), nn.GELU(), nn.Linear(128, dim))
        self.w_pos = nn.Linear(dim, dim, bias=False)
        self.w_neg = nn.Linear(dim, dim, bias=False)
        nn.init.eye_(self.w_pos.weight)
        nn.init.zeros_(self.w_neg.weight)
        self.log_temp = nn.Parameter(torch.tensor(np.log(1 / 0.07), dtype=torch.float32))

    def items(self) -> torch.Tensor:
        return F.normalize(self.id_emb.weight + self.mlp(self.feats), dim=-1)

    def users(self, pos_w: torch.Tensor, neg_w: torch.Tensor, V: torch.Tensor) -> torch.Tensor:
        # pos_w / neg_w: (B, n_items) row-normalised signal weights of the history.
        return F.normalize(self.w_pos(pos_w @ V) - self.w_neg(neg_w @ V), dim=-1)


def train_tower(train_csr: sp.csr_matrix, feats: np.ndarray, dim: int, epochs: int, device: str) -> TwoTower:
    n_users, n_items = train_csr.shape
    model = TwoTower(n_items, torch.tensor(feats, device=device), dim).to(device)
    opt = torch.optim.AdamW(model.parameters(), lr=3e-3, weight_decay=1e-4)
    indptr, indices, data = train_csr.indptr, train_csr.indices, train_csr.data
    rng = np.random.default_rng(0)
    eligible = np.where(np.diff(indptr) >= 3)[0]
    B = 4096
    for ep in range(epochs):
        rng.shuffle(eligible)
        total, steps = 0.0, 0
        for lo in range(0, len(eligible), B):
            u = eligible[lo:lo + B]
            rows = train_csr[u].toarray()
            # Target: one liked title per user, removed from that user's history (leave-one-out inside train).
            liked = rows >= RELEVANT
            has = liked.any(1)
            rows, liked = rows[has], liked[has]
            if len(rows) == 0:
                continue
            probs = liked / liked.sum(1, keepdims=True)
            target = (probs.cumsum(1) > rng.random((len(rows), 1))).argmax(1)
            rows[np.arange(len(rows)), target] = 0
            # Randomly drop 30% of the history so the user tower also learns from short histories.
            rows *= rng.random(rows.shape) > 0.3
            pos = np.clip(rows, 0, None)
            neg = np.clip(-rows, 0, None)
            pos /= np.maximum(pos.sum(1, keepdims=True), 1e-6)
            neg /= np.maximum(neg.sum(1, keepdims=True), 1e-6)
            pos_t = torch.tensor(pos, device=device)
            neg_t = torch.tensor(neg, device=device)
            hist_mask = torch.tensor(rows != 0, device=device)
            V = model.items()
            U = model.users(pos_t, neg_t, V)
            logits = (U @ V.T) * model.log_temp.exp().clamp(max=100)
            logits = logits.masked_fill(hist_mask, -1e4)
            loss = F.cross_entropy(logits, torch.tensor(target, device=device))
            opt.zero_grad()
            loss.backward()
            opt.step()
            total += loss.item()
            steps += 1
        log(f"tower epoch {ep + 1}/{epochs} loss {total / max(steps, 1):.4f}")
    return model


# ---------------------------------------------------------------- main
def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--dim", type=int, default=64)
    ap.add_argument("--eval-users", type=int, default=50_000)
    ap.add_argument("--val-users", type=int, default=10_000)
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    device = "cuda" if torch.cuda.is_available() else "cpu"
    t0 = time.time()

    cat = pd.read_csv(CATALOG, keep_default_na=False)
    cat["genres"] = cat.genres.map(lambda s: [g for g in s.split("|") if g])
    cat["tags"] = cat.tags.map(lambda s: [t for t in s.split("|") if t])
    item_of = {mid: i for i, mid in enumerate(cat.id)}
    n_items = len(cat)
    feats, feat_names = item_features(cat)

    df = pd.read_parquet(DATA / "interactions.parquet")
    log(f"loaded {len(df):,} interactions, {df.user_id.nunique():,} users, {n_items} items on {device}")
    df["item"] = df.media_id.map(item_of).astype(np.int32)
    df["s"] = signals(df)

    # temporal split per user
    df = df.sort_values(["user_id", "day"], kind="stable")
    df["rank"] = df.groupby("user_id").cumcount()
    df["n"] = df.groupby("user_id").user_id.transform("size")
    eligible = df.n >= 8
    df["test"] = eligible & (df["rank"] >= np.ceil(df.n * 0.8))
    n_users = int(df.user_id.max()) + 1
    tr = df[~df.test]
    te = df[df.test]
    train_csr = sp.csr_matrix((tr.s.to_numpy() + np.where(tr.s.to_numpy() == 0, 1e-3, 0), (tr.user_id, tr.item)),
                              shape=(n_users, n_items), dtype=np.float32)
    rel = te[te.s >= RELEVANT].groupby("user_id").item.apply(set).to_dict()
    bad = te[te.s <= -0.3].groupby("user_id").item.apply(set).to_dict()
    eval_pool = np.array(sorted(rel))
    rng = np.random.default_rng(1)
    rng.shuffle(eval_pool)
    val_users = eval_pool[:args.val_users]
    test_users = eval_pool[args.val_users:args.val_users + args.eval_users]
    log(f"train {len(tr):,} rows; test users with relevant held-out titles: {len(eval_pool):,}")

    # ---- popularity
    pos_count = np.asarray((train_csr >= RELEVANT).sum(0)).ravel().astype(np.float32)
    pop_score = np.log1p(pos_count)
    f_pop = lambda u: np.tile(pop_score, (len(u), 1))

    # ---- content profile (V1 analogue)
    f_content = lambda u: np.asarray(train_csr[u] @ feats) @ feats.T

    # ---- EASE (signed and binary-positive variants, λ tuned on validation)
    X_signed = train_csr
    X_pos = train_csr.copy()
    X_pos.data = np.clip(X_pos.data, 0, None)
    X_pos.eliminate_zeros()
    best_ease = None
    for variant, X in [("signed", X_signed), ("positive", X_pos)]:
        Gm = (X.T @ X).toarray().astype(np.float64)
        for lam in (50.0, 200.0, 800.0):
            P = np.linalg.inv(Gm + lam * np.eye(n_items))
            Bm = -P / np.diag(P)
            np.fill_diagonal(Bm, 0)
            Bm = Bm.astype(np.float32)
            m = evaluate(f"ease-{variant[:3]}-{int(lam)}", lambda u, Bm=Bm: np.asarray(train_csr[u] @ Bm),
                         val_users, train_csr, rel, bad, n_items)
            if best_ease is None or m["ndcg@10"] > best_ease[0]:
                best_ease = (m["ndcg@10"], variant, lam, Bm)
    _, ease_variant, ease_lambda, B_ease = best_ease
    log(f"EASE best: {ease_variant} λ={ease_lambda}")
    f_ease_full = lambda u: np.asarray(train_csr[u] @ B_ease)
    # What the backend actually serves: top-40 positive neighbours per item (item_neighbor). Evaluate and blend
    # THIS, otherwise offline numbers describe a model nobody runs.
    top_n = 40
    nbr = np.argsort(-B_ease, axis=1)[:, :top_n]
    B_served = np.zeros_like(B_ease)
    np.put_along_axis(B_served, nbr, np.take_along_axis(B_ease, nbr, 1), 1)
    B_served = np.clip(B_served, 0, None)
    B_served_sp = sp.csr_matrix(B_served)
    f_ease = lambda u: np.asarray((train_csr[u] @ B_served_sp).todense())
    evaluate("ease-full", f_ease_full, val_users[:4000], train_csr, rel, bad, n_items)
    evaluate("ease-top40", f_ease, val_users[:4000], train_csr, rel, bad, n_items)

    # ---- type prior: share of the user's history per media type (watchers never read, readers never watch)
    item_type = cat.media_type.map({t: i for i, t in enumerate(["ANIME", "MANGA", "MANHWA", "MANHUA"])}).to_numpy()
    type_onehot = sp.csr_matrix((np.ones(n_items, np.float32), (np.arange(n_items), item_type)), shape=(n_items, 4))

    def f_type(u):
        h = train_csr[u].copy()
        h.data = np.ones_like(h.data)
        counts = np.asarray((h @ type_onehot).todense())
        share = (counts + 0.5) / (counts.sum(1, keepdims=True) + 2.0)
        return np.log(share)[:, item_type]

    # ---- two-tower
    model = train_tower(train_csr, feats, args.dim, args.epochs, device)
    model.eval()
    with torch.no_grad():
        V = model.items()
        V_np = V.cpu().numpy()
        Wp = model.w_pos.weight.cpu().numpy()
        Wn = model.w_neg.weight.cpu().numpy()

    def f_tower(u):
        rows = train_csr[u].toarray()
        pos = np.clip(rows, 0, None)
        neg = np.clip(-rows, 0, None)
        pos /= np.maximum(pos.sum(1, keepdims=True), 1e-6)
        neg /= np.maximum(neg.sum(1, keepdims=True), 1e-6)
        U = (pos @ V_np) @ Wp.T - (neg @ V_np) @ Wn.T
        U /= np.linalg.norm(U, axis=1, keepdims=True).clip(1e-6)
        return U @ V_np.T

    # ---- blend weights on validation (z-score per user, then weighted sum)
    def z(S):
        return (S - S.mean(1, keepdims=True)) / S.std(1, keepdims=True).clip(1e-6)

    # ---- personalised popularity: how mainstream this user's history is × how popular the title is.
    # Global popularity helps some users and hurts others, so its global weight is ~0; the interaction is not.
    zpop_item = (pop_score - pop_score.mean()) / pop_score.std()

    def f_mpop(u):
        h = train_csr[u].copy()
        h.data = np.ones_like(h.data)
        counts = np.asarray(h.sum(1)).ravel().clip(1)
        mainstream = np.asarray(h @ zpop_item).ravel() / counts
        return mainstream[:, None] * zpop_item[None, :]

    parts = {"ease": f_ease, "tower": f_tower, "content": f_content, "pop": f_pop, "type": f_type, "mpop": f_mpop}
    hist_len = np.diff(train_csr.indptr)
    SHORT = 10

    def tune(users, start, label):
        # Part scores are cached once for the tuning users, so the weight search is just weighted sums.
        cached = {k: z(fn(users)) for k, fn in parts.items()}

        def score(w):
            return evaluate(label, lambda u: sum(w[k] * cached[k] for k in parts if w.get(k)), users, train_csr, rel,
                            bad, n_items, batch=len(users))["ndcg@10"]

        # Coordinate ascent: start from the previous best blend, move one weight at a time, two passes.
        w = {k: start.get(k, 0.0) for k in parts}
        best = score(w)
        for _ in range(2):
            for k in parts:
                for v in (0.0, 0.05, 0.1, 0.2, 0.3, 0.45, 0.6, 0.8, 1.0):
                    cand = {**w, k: v}
                    if sum(cand.values()) == 0 or cand == w:
                        continue
                    s = score(cand)
                    if s > best + 1e-4:
                        best, w = s, cand
        log(f"{label} weights: {w} (val ndcg {best:.4f})")
        return w

    old_w = {"ease": 0.0, "tower": 0.6, "content": 0.1, "pop": 0.0, "type": 0.0, "mpop": 0.0}
    blend_w = tune(val_users[:6000], old_w, "blend")
    # Short histories behave differently (the tower generalises from features, EASE rows are sparse): own weights.
    short_val = np.array([u for u in val_users if hist_len[u] <= SHORT])[:4000]
    blend_short = tune(short_val, blend_w, "blend-short")

    def f_blend(u):
        u = np.asarray(u)
        long_s = sum(blend_w[k] * z(parts[k](u)) for k in parts if blend_w[k])
        short_s = sum(blend_short[k] * z(parts[k](u)) for k in parts if blend_short[k])
        return np.where((np.diff(train_csr.indptr)[u] <= SHORT)[:, None], short_s, long_s)
    f_blend_v2 = lambda u: sum(old_w[k] * z(parts[k](u)) for k in parts if old_w[k])

    # ---- final test
    results = {}
    for name, fn in [("pop", f_pop), ("content", f_content), ("ease", f_ease), ("ease_full", f_ease_full),
                     ("tower", f_tower), ("type", f_type), ("blend_v2", f_blend_v2), ("blend", f_blend)]:
        results[name] = evaluate(name, fn, test_users, train_csr, rel, bad, n_items)
    users_df = pd.read_parquet(DATA / "users.parquet")
    segment = users_df.set_index("user_id").segment.reindex(np.arange(n_users)).fillna("both").to_numpy()
    diagnostics = {name: diagnose(name, fn, test_users[:20000], train_csr, rel, item_type, segment)
                   for name, fn in [("tower", f_tower), ("blend_v2", f_blend_v2), ("blend", f_blend)]}

    # ---- cold start: same test users but only their first 3 titles known
    first3 = tr[tr.user_id.isin(test_users) & (tr["rank"] < 3)]
    cold_csr = sp.csr_matrix((first3.s.to_numpy() + 1e-3, (first3.user_id, first3.item)), shape=(n_users, n_items),
                             dtype=np.float32)
    cold = {}
    saved = train_csr
    train_csr = cold_csr
    for name, fn in [("pop", f_pop), ("content", f_content), ("ease", f_ease), ("tower", f_tower),
                     ("blend_v2", f_blend_v2), ("blend", f_blend)]:
        cold[name] = evaluate(f"cold-{name}", fn, test_users[:20000], cold_csr, rel, bad, n_items)
    train_csr = saved

    # ---- export
    OUT.mkdir(parents=True, exist_ok=True)
    np.save(OUT / "item_vectors.npy", V_np)
    np.save(OUT / "w_pos.npy", Wp)
    np.save(OUT / "w_neg.npy", Wn)
    torch.save(model.state_dict(), OUT / "two_tower.pt")
    np.save(OUT / "item_features.npy", feats)
    (OUT / "feature_names.json").write_text(json.dumps(feat_names))
    np.save(OUT / "ease_served.npy", B_served.astype(np.float32))
    metrics = {
        "dataset": json.loads((DATA / "summary.json").read_text()),
        "split": {"rule": "last 20% by day for users with >= 8 titles", "relevant_signal": RELEVANT,
                  "val_users": int(len(val_users)), "test_users": int(len(test_users))},
        "ease": {"variant": ease_variant, "lambda": ease_lambda},
        "tower": {"dim": args.dim, "epochs": args.epochs, "features": len(feat_names)},
        "blend_weights": blend_w, "blend_short_weights": blend_short, "short_max_history": SHORT,
        "test": results, "cold_start_3_titles": cold, "diagnostics": diagnostics,
        "seconds": round(time.time() - t0, 1),
    }
    (OUT / "metrics.json").write_text(json.dumps(metrics, indent=1))

    ids = cat.id.to_numpy()
    lines = ["-- GENERATED by ml/train/train_recommenders.py. Item vectors + EASE neighbours served by RecommenderV2.",
             "CREATE TABLE IF NOT EXISTS item_vector (media_id BIGINT PRIMARY KEY REFERENCES media (id) ON DELETE CASCADE,",
             "  vec REAL[] NOT NULL);",
             "CREATE TABLE IF NOT EXISTS item_neighbor (media_id BIGINT NOT NULL REFERENCES media (id) ON DELETE CASCADE,",
             "  neighbor_id BIGINT NOT NULL REFERENCES media (id) ON DELETE CASCADE, weight REAL NOT NULL,",
             "  PRIMARY KEY (media_id, neighbor_id));",
             "CREATE TABLE IF NOT EXISTS model_meta (name TEXT PRIMARY KEY, payload JSONB NOT NULL, trained_at TIMESTAMPTZ NOT NULL DEFAULT now());",
             "TRUNCATE item_vector, item_neighbor;",
             "INSERT INTO item_vector (media_id, vec) VALUES"]
    lines.append(",\n".join(f"({ids[i]}, '{{{','.join(f'{x:.5f}' for x in V_np[i])}}}')" for i in range(n_items)) + ";")
    # B[j, i] = how much having j predicts i; the backend sums s_uj * B[j, i] over the user's history j.
    rows = []
    for j in range(n_items):
        for i in nbr[j]:
            if B_ease[j, i] > 0:
                rows.append(f"({ids[j]}, {ids[i]}, {B_ease[j, i]:.6f})")
    lines.append("INSERT INTO item_neighbor (media_id, neighbor_id, weight) VALUES")
    lines.append(",\n".join(rows) + ";")
    meta = {"blend": blend_w, "blend_short": blend_short, "short_max": SHORT,
            "pop_mean": float(pop_score.mean()), "pop_std": float(pop_score.std()), "w_pos": Wp.round(5).tolist(), "w_neg": Wn.round(5).tolist(),
            "test": results, "cold": cold, "ease": metrics["ease"], "dim": args.dim}
    lines.append("INSERT INTO model_meta (name, payload) VALUES ('recommender_v2', '"
                 + json.dumps(meta).replace("'", "''") + "'::jsonb)")
    lines.append("ON CONFLICT (name) DO UPDATE SET payload = EXCLUDED.payload, trained_at = now();")
    SQL_OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    log(f"exported {n_items} vectors, {len(rows):,} neighbours -> {SQL_OUT}  ({time.time() - t0:.0f}s total)")


if __name__ == "__main__":
    main()
