# Research proposal: trend-aware hybrid recommender for anime, manga, manhwa and manhua

**Working title:** *STAR — Sequence + Taste-feature scoring with Adaptive (RL) Re-ranking*
**Status:** proposal + data simulator implemented (`ml/synthetic/generate_dataset.py`). Models not trained yet.
**Date:** 2026-10-07

---

## 1. Problem

A user rates two shonen titles very differently: *Naruto* 5/10, *Attack on Titan* 10/10.
A genre-only recommender learns "likes shonen: average" and is useless. The real reasons live in:

1. **Content beyond genre:** themes in the synopsis and tags (dark, revenge, survival vs. friendship, tournament, school).
2. **Non-linear combinations:** "dark" is good, "school" is neutral, but "dark + school" may be disliked by this user.
3. **Order and drift:** taste changes over time (a reader moving from shonen to seinen), and the last few titles matter more than one from two years ago.
4. **Trends:** a new adaptation makes "regression" manhwa explode for two months; what is good to show changes week to week, independent of personal taste.

No single model handles all four well. The proposal is a three-stage system where each stage owns one problem, and each can be evaluated alone.

## 2. Research questions and hypotheses

| # | Question | Hypothesis |
|---|----------|------------|
| RQ1 | Does an LSTM over the user's ordered interaction history improve ranking when taste drifts? | H1: LSTM+MLP beats MLP-only on NDCG@10 in the last 30 days of the timeline; the gap grows with drift magnitude. |
| RQ2 | Does a feature MLP recover non-linear tag interactions that the V1 linear scorer cannot? | H2: On items that contain a planted interaction pair, MLP error is lower than V1 by a significant margin; on other items they are close. |
| RQ3 | Does a trend-aware bandit re-ranker improve cumulative reward under non-stationary trends, and what does it cost in diversity/exposure fairness? | H3: Discounted LinUCB beats static ranking on cumulative reward during trend windows, with a measurable exposure-Gini increase that an exposure-aware reward reduces. |
| RQ4 | Can attributions explain *why* a user scored two same-genre titles differently? | H4: Integrated-gradient attributions on the feature tower rank the planted top/bottom tags of a user in their top-5 more often than chance. |

RQ4 is the one that answers the original "5 vs 10" question directly, and the simulator makes it testable because we know the true reasons.

## 3. Why these model families (and what we rejected)

| Need | Choice | Why | Rejected alternative |
|------|--------|-----|----------------------|
| Content signal, cold start, explainability | Genre scoring + TF-IDF (V1, already shipped) | Works with 1 rating, no training, every number is explainable | Pre-trained text embeddings first: heavier, less explainable, unnecessary at our catalog size |
| Ordered history + drift | **LSTM** (Hochreiter & Schmidhuber 1997) | Gated memory handles short sequences and slow drift cheaply; small and fast on CPU/mobile; easy to explain | Transformer (SASRec, BERT4Rec) as *primary*: stronger with lots of data, but a reproducibility study found GRU-based models competitive at small embedding sizes (Betello et al. 2024). We keep SASRec as a **baseline** so the LSTM choice is tested, not assumed. |
| Non-linear feature interactions | **Feature MLP** (Wide & Deep style, Cheng et al. 2016; NCF, He et al. 2017) | Learns "dark × school" style crosses from multi-hot tags + TF-IDF; attributions are straightforward | Gradient boosting: great on tabular data, but harder to fuse end-to-end with the LSTM state |
| Trends + exploration | **Contextual bandit re-ranker** (LinUCB, Li et al. 2010; discounted D-LinUCB, Russac et al. 2019) | Learns online from clicks with no simulator required in production; discounting forgets stale trends; tiny state, can run in the Java backend | Full deep RL (DQN/actor-critic) in production: data hungry, unstable, hard to evaluate offline. Kept as a *simulator-only* extension (SlateQ, Ie et al. 2019). |

**Honest framing for interviews:** neural recommenders do not automatically beat tuned simple baselines (Dacrema et al. 2019; Ludewig et al. 2019). This plan therefore always reports V1, popularity, ItemKNN and matrix factorization next to the neural models.

## 4. System architecture

```text
                       ┌──────────────────────────────────────────────┐
 interaction_event ──▶ │ Stage 1  Candidate generation  (top ~200)    │
 rating, media         │  V1 genre+TF-IDF · popularity · trending tags │
                       └───────────────────────┬──────────────────────┘
                                               ▼
                       ┌──────────────────────────────────────────────┐
                       │ Stage 2  Supervised scorer  (PyTorch → ONNX) │
                       │                                              │
                       │  Sequence tower        Feature tower         │
                       │  LSTM over last 50     MLP over candidate:   │
                       │  events: item emb ⊕    genres, tags, TF-IDF  │
                       │  content emb ⊕ event   SVD-32, type, global  │
                       │  type ⊕ centred score  mean rating, recency  │
                       │  ⊕ log Δt   ──▶ h_u    + user stats ──▶ x_i   │
                       │                                              │
                       │  Fusion: [h_u, x_i, h_u ⊙ W·x_i] → MLP       │
                       │  Heads: rating (MSE, rated only)             │
                       │         engagement (BCE: click / continue)   │
                       └───────────────────────┬──────────────────────┘
                                               ▼
                       ┌──────────────────────────────────────────────┐
                       │ Stage 3  Trend-aware RL re-ranker            │
                       │  Contextual bandit (discounted LinUCB /      │
                       │  neural-linear Thompson sampling)            │
                       │  context: stage-2 scores, tag trend velocity,│
                       │  item hype, user trend susceptibility, slot  │
                       │  reward: click + λ·progress + μ·rating − drop│
                       └───────────────────────┬──────────────────────┘
                                               ▼
                                   slate of 10 shown in For You
                                   (impressions + propensities logged)
```

### 4.1 Stage 2 details

- **Sequence input per step:** item ID embedding (32), content embedding (genres multi-hot → 16, TF-IDF reduced by truncated SVD → 32), event-type embedding (4: click, progress, rate, drop), centred rating (score − user mean, 0 if none), log time gap since previous event.
- **LSTM:** 1 layer, hidden 64, last 50 events, dropout 0.2. Final hidden state `h_u` is the user's *current* taste.
- **Feature tower:** for the candidate item and static user stats (rating mean/std/count, type ratios). Two layers 128 → 64, ReLU.
- **Fusion:** concatenation plus a bilinear term `h_u ⊙ W x_i` (lets sequence state modulate which item features matter), then 64 → 32 → heads.
- **Multi-task loss:** `L = MSE(rating) · 1[rated] + α · BCE(engaged)`. Ratings are sparse and missing-not-at-random (people mostly rate what they finished); the engagement head uses every impression and regularises the shared layers.
- **Why centred ratings:** a 7 from a harsh rater outranks a 9 from a generous one. Same idea as V1's pivot.

### 4.2 Stage 3 details (the RL part)

Formulated first as a **contextual bandit**, because each slate's reward is observed immediately and the main non-stationarity (trends) is in the reward function, not in long-horizon state:

- **Arms:** the ~50 top items from stage 2. **Context** `x_{t,a}`: stage-2 rating and engagement scores, tag trend level and 7-day velocity, item hype since release, the user's estimated trend susceptibility (fraction of past clicks on trending items), slate position.
- **Policy:** discounted LinUCB. Estimate `θ` by ridge regression with weight `γ^(t−s)` on old observations so stale trends are forgotten; pick `argmax x^T θ + α·sqrt(x^T A^{-1} x)`, fill slots greedily.
- **Reward:** `r = click + λ·min(progress/5, 1) + μ·(rating − user_mean)/4.5 − ν·drop`.
- **Exposure-aware variant:** scale rewards by slot exposure to counter the popularity feedback loop (Mansoury et al. 2022). This is the H3 trade-off measurement.
- **Stretch, simulator-only:** SlateQ (Ie et al. 2019) with state = LSTM hidden state to optimise long-term return (sessions per week), trained inside our simulator like RecSim. Compared offline only.

### 4.3 Explainability (RQ4)

- **Integrated gradients** (Sundararajan et al. 2017) on the feature-tower inputs for a (user, item) pair → per-tag and per-term attributions.
- For the "5 vs 10" question: take two rated items of the same genre and show the attribution difference. Expected output for the kenji demo user: `+dark, +revenge, +survival` vs `−tournament, −friendship, −school`.
- V1 already produces this kind of reason list from linear weights (`/api/users/{id}/taste`). The research question is whether the neural model's explanations stay faithful once it becomes non-linear.

## 5. Data

### 5.1 Synthetic world (implemented)

`ml/synthetic/generate_dataset.py`. Default run: 1,000 users, 3,000 items, 180 days, ~494k logged events, ~28k ratings (mean 7.1, std 1.48 — skewed high like real MAL data because people mostly rate what they finished).

What is planted (and saved in `ml/data/ground_truth/` for evaluation only):

| Planted property | Which model it tests |
|------------------|----------------------|
| 6 taste archetypes mixed per user (Dirichlet) | all |
| Per-user slow tag drift over the 180 days | LSTM (RQ1) |
| 5 pairwise tag interactions, e.g. dark×school negative for dark-shonen users | MLP (RQ2) |
| 6 tag trend waves plus per-item release hype | bandit (RQ3) |
| User trend susceptibility (Beta(2,5)) | bandit context |
| Rater bias per user | centring |
| Logging policy = popularity^0.7 + 20% uniform, propensity stored per impression | off-policy evaluation |

Ratings are only produced after a user clicked and read/watched ≥ 3 units, so the data is **missing-not-at-random** on purpose (Marlin & Zemel 2009) — a model that ignores this will look better offline than it is.

### 5.2 Real data for external validity

- Public MyAnimeList-derived datasets on Kaggle (e.g. *Anime Recommendations Database*: ~7.8M ratings, ~73k users). Check each dataset's license before use; use only for offline research, not shipped in the app.
- Aniga's own `interaction_event` table once real users exist (same schema as the simulator's `events.csv`, on purpose).
- Manga/manhwa ratings are much scarcer publicly; this is a stated limitation.

## 6. Evaluation protocol

- **Split by time, not randomly:** train days 0–120, validate 120–150, test 150–180. A random split leaks the future (trends) into training.
- **Rating:** RMSE, MAE on centred and raw scores.
- **Ranking:** NDCG@10, Recall@10, HR@10 against a sampled + full catalog; report both because sampled metrics can mislead.
- **Beyond accuracy:** catalog coverage, intra-list diversity (genre Jaccard), novelty, exposure Gini.
- **Bandit / RL:**
  - in the simulator: cumulative reward and **regret against the oracle** (the simulator knows true utility),
  - on logged data: IPS, SNIPS and doubly-robust estimates using the stored propensities (Dudík et al. 2011),
  - trend responsiveness: reward lift inside vs. outside trend windows.
- **Explainability (RQ4):** precision@5 of attributed tags against the planted top/bottom tags.
- **Statistics:** 5 seeds per configuration, mean ± std, paired bootstrap or Wilcoxon test across users.

### Baselines

Popularity · V1 genre+TF-IDF · ItemKNN · MF/BPR · NCF · GRU4Rec · SASRec · MLP-only · LSTM-only · LSTM+MLP · LSTM+MLP+bandit.

### Ablations

No sequence tower · no TF-IDF features · no bilinear fusion · LSTM vs GRU vs Transformer · no trend features in bandit · undiscounted vs discounted LinUCB · exposure-aware reward on/off · engagement head on/off.

## 7. Threats to validity

1. **Circularity:** a model can look good because it matches the simulator's assumptions. Mitigation: report real-data results separately and never tune on ground-truth files.
2. **MNAR ratings and popularity bias** in logs. Mitigation: propensity-weighted evaluation, exposure metrics.
3. **Baseline weakness:** tune baselines with the same budget as our model (the main critique in Dacrema et al. 2019).
4. **Small real dataset** for manga/manhwa/manhua.
5. **LSTM vs. Transformer:** if SASRec wins clearly, report it; the proposal's contribution is the staged design and trend-aware re-ranking, not "LSTM is best".

## 8. Deployment path

- Train in Python (PyTorch), export stage 2 to **ONNX**; serve with ONNX Runtime from the Spring backend first, on-device (ONNX Runtime Mobile) later.
- Stage 3 bandit state is a `d×d` matrix and a vector per policy: update it in Java inside the backend, persist in Postgres.
- Every slate shown is logged to `interaction_event` with position and propensity, so each release generates its own evaluation data.

## 9. Milestones

| Milestone | Deliverable | Done when |
|-----------|-------------|-----------|
| M0 | Simulator + V1 in production | **done** |
| M1 | Data loaders, time split, baseline suite (Pop, V1, ItemKNN, MF) | metrics table on synthetic data |
| M2 | Feature MLP + attributions | RQ2, RQ4 results |
| M3 | LSTM + fusion, multi-task | RQ1 results, SASRec comparison |
| M4 | Discounted LinUCB in simulator + IPS/DR on logs | RQ3 results |
| M5 | ONNX export, backend integration behind a feature flag | A/B between V1 and STAR in the web console |
| M6 | Write-up (paper-style report in `learning/research/`) | reproducible from one command |

## 10. References

- Hochreiter, S., Schmidhuber, J. (1997). Long Short-Term Memory. *Neural Computation*.
- Hidasi, B. et al. (2016). Session-based Recommendations with Recurrent Neural Networks (GRU4Rec). *ICLR*.
- Kang, W.-C., McAuley, J. (2018). Self-Attentive Sequential Recommendation (SASRec). *ICDM*.
- Sun, F. et al. (2019). BERT4Rec. *CIKM*.
- Betello, F. et al. (2024). A Reproducible Analysis of Sequential Recommender Systems. arXiv:2408.03873.
- Cheng, H.-T. et al. (2016). Wide & Deep Learning for Recommender Systems. *DLRS*.
- He, X. et al. (2017). Neural Collaborative Filtering. *WWW*.
- Covington, P. et al. (2016). Deep Neural Networks for YouTube Recommendations. *RecSys* (candidate generation + ranking split).
- Li, L. et al. (2010). A Contextual-Bandit Approach to Personalized News Article Recommendation (LinUCB). *WWW*.
- Russac, Y., Vernade, C., Cappé, O. (2019). Weighted Linear Bandits for Non-Stationary Environments (D-LinUCB). *NeurIPS*.
- Zheng, G. et al. (2018). DRN: A Deep Reinforcement Learning Framework for News Recommendation. *WWW*.
- Ie, E. et al. (2019). SlateQ: A Tractable Decomposition for Reinforcement Learning with Recommendation Sets. *IJCAI*.
- Ie, E. et al. (2019). RecSim: A Configurable Simulation Platform for Recommender Systems. arXiv:1909.04847.
- Chen, M. et al. (2019). Top-K Off-Policy Correction for a REINFORCE Recommender System. *WSDM*.
- Chen, X. et al. (2021). A Survey of Deep Reinforcement Learning in Recommender Systems. arXiv:2109.03540.
- Mansoury, M. et al. (2022). Exposure-Aware Recommendation using Contextual Bandits. arXiv:2209.01665.
- Dudík, M., Langford, J., Li, L. (2011). Doubly Robust Policy Evaluation and Learning. *ICML*.
- Marlin, B., Zemel, R. (2009). Collaborative Prediction and Ranking with Non-Random Missing Data. *RecSys*.
- Dacrema, M. F., Cremonesi, P., Jannach, D. (2019). Are We Really Making Much Progress? *RecSys*.
- Ludewig, M. et al. (2019). Performance Comparison of Neural and Non-Neural Approaches to Session-based Recommendation. *RecSys*.
- Sundararajan, M., Taly, A., Yan, Q. (2017). Axiomatic Attribution for Deep Networks (Integrated Gradients). *ICML*.
