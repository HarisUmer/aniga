# 12 — Training report: recommender v2

Script: `ml/train/train_recommenders.py` (PyTorch on GPU for the two-tower, SciPy for EASE). Full run: 276 s. Raw numbers: `ml/models/v2/metrics.json`.

## Protocol

- **Split:** users with ≥ 8 titles; each user's **last 20% by day** is held out (time-based, so there is no peeking at the future).
- **Users:** 10,000 for validation (blend weights, EASE λ) and 50,000 for test.
- **Relevant** = a held-out title with signal ≥ 0.25 (liked or completed).
- **Metrics @10:**
  - recall: share of liked held-out titles that appear in the top 10;
  - **nDCG:** the same, rewarding higher positions;
  - bad@10: share of the top 10 the user actually disliked;
  - coverage: share of the catalog that appears in anyone's top 10.
- **Cold start:** 20,000 test users reduced to only 3 known titles.

## Models

| Model | Idea | Why it is here |
|---|---|---|
| pop | Most-liked titles overall | The baseline every model must beat |
| content | Genre + tag profile from the user's signals (V1, in Java) | Explainable; works for titles nobody has rated |
| EASE | Closed-form item–item regression: `P = (XᵀX + λI)⁻¹`, `B = I − P / diag(P)`, λ = 800 | Strong, simple, no training loop; a 902×902 matrix |
| two-tower | Item = id embedding + MLP(191 genre/tag/type features), dim 64, 12 epochs, in-batch softmax. User = `W_pos·mean(liked) + W_neg·mean(disliked)` | **Inductive**: no per-user parameters, so a user who rated three titles a minute ago gets a vector immediately |
| blend | Weighted sum of z-scored parts; weights grid-searched on validation | Combines strengths |

## Results — test, 50,000 users

| Model | recall@10 | recall@20 | **nDCG@10** | bad@10 | coverage@10 |
|---|---|---|---|---|---|
| pop | 0.0570 | 0.0958 | 0.0406 | 0.0025 | 5.5% |
| content | 0.0630 | 0.1092 | 0.0445 | 0.0020 | 100% |
| EASE | 0.1093 | 0.1760 | 0.0815 | 0.0019 | 72.1% |
| two-tower | 0.1113 | 0.1795 | 0.0821 | 0.0023 | 42.6% |
| type (share of each media type in the user's history) | 0.0354 | 0.0587 | 0.0253 | 0.0037 | 69.0% |
| blend, run 1 (tower 0.6, content 0.1) | 0.1140 | 0.1820 | 0.0841 | 0.0020 | 55.7% |
| **blend, run 2** (+ type 0.05, EASE 0.05, short-history weights) | **0.1147** | **0.1843** | **0.0850** | **0.0019** | 53.5% |

Blend vs popularity: **+109% nDCG@10**. Blend vs V1 content: **+91%**.

## Cold start — only 3 known titles, 20,000 users

| Model | recall@10 | nDCG@10 |
|---|---|---|
| pop | 0.0497 | 0.0340 |
| content | 0.0308 | 0.0205 |
| EASE | 0.0599 | 0.0412 |
| two-tower | 0.0670 | 0.0454 |
| blend, run 1 | 0.0642 | 0.0430 |
| **blend, run 2** (short-history weights: tower 0.45, pop 0.2) | **0.0722** | **0.0489** |

## Run 2: what was tested and changed

Run 1 used one set of blend weights for everyone, and the blend lost to the plain two-tower on cold start (0.0430 vs 0.0454). Diagnostics added in run 2 (`metrics.json → diagnostics`) showed why:

| Slice (blend) | nDCG@10 |
|---|---|
| ≤ 10 titles of history | 0.0447 |
| 11–30 titles | 0.0681 |
| > 30 titles | 0.1259 |
| readers only / watchers only / both | 0.0888 / 0.1099 / 0.0643 |

Changes:

1. **Separate weights for short histories (≤ 10 titles).** These were grid-searched on validation users with short histories. They lean on popularity (0.2), because content and EASE rows are noise with three titles. Result: cold start +14% over run 1, and now above the tower alone.
2. **A "type" part:** the user's share of anime / manga / manhwa / manhua. "Both" users are the hardest segment, and the type part stops a manhwa reader being shown anime they never watch. `type_leak@10` (recommending a type the user never touched) is 0.03%.
3. **EASE back in at 0.05:** a small weight helps once the type part removes the overlap.

Online learning on top of the trained blend is in [15-feedback-learning.md](15-feedback-learning.md).

## Reading the results (what to say in an interview)

1. **Collaborative signal dominates.** EASE and the two-tower double popularity. Content alone barely beats popularity because personas mix genres in ways a flat profile can't capture.
2. **EASE gets weight 0 in the blend** even though it is strong alone. It is highly correlated with the two-tower, so the grid prefers tower + a little content (diversity) over tower + EASE. EASE is still used for "More like this", where an item-item row is exactly the right tool.
3. **The two-tower wins cold start** because its item side uses features. With 3 titles, the feature MLP generalises where the EASE rows are sparse.
4. **bad@10 stays at ~0.2%** for every model: negative signals (drops, low scores) are respected.
5. **Coverage trade-off:** popularity recommends 5.5% of the catalog to everyone; the blend reaches 55% without losing accuracy.

## Serving (`RecommenderV2.java`)

- The training script exports `infra/db/init/007_model_v2.sql`: 902 `item_vector` rows (64-d tower embeddings plus popularity) and 36,080 `item_neighbor` rows (top-40 EASE neighbours per item).
- At request time the backend:
  1. builds the user's signals from `rating` + `library_entry`;
  2. scores candidates with tower dot-products and neighbour sums;
  3. z-scores and blends with the validation weights;
  4. excludes titles already seen;
  5. attaches a **because** anchor (the liked title that contributed most);
  6. applies the **franchise diversification** rerank.
- **Franchise rerank:** sequels share neighbours, so a raw top 10 was "AoT S2, S3, Final Season…". A greedy pass keeps one title per franchise key (title before ":", "season", "part", digits, roman numerals), then fills from the skipped ones. User 1's list went from four Attack on Titan entries to: AoT, Tokyo Ghoul, Chainsaw Man, FMA: Brotherhood, Parasyte, Monster, Vinland Saga.
- Users with ≤ 10 signals use the short-history weights.
- Every serve then adds the per-user online weights, the hand-set boosts and mutes, and two exploration slots ([15](15-feedback-learning.md)).

## Next

- Retrain on real events once there are users; keep the synthetic set as a regression test.
- Add the sequence model from the research proposal (LSTM over the last N events) as another blend part.
- Online A/B: impressions and clicks are now logged (`rec_impression`), so CTR and completion lift per blend weight can be measured.
