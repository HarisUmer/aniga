# Recommender V1: genre scoring + TF-IDF

**Code:** `backend/src/main/java/com/aniga/backend/recommend/` (`HybridRecommender`, `TfIdfIndex`)
**Tests:** `backend/src/test/java/com/aniga/backend/recommend/HybridRecommenderTest.java`

## The requirement

> A user rates one shonen anime 5 and another shonen anime 10. Find out *what about the description* made the difference.

Genre alone can't: both are "Action, shonen". The difference must come from synopsis and tags.

## Algorithm

### Step 1: centre ratings (who is this rater?)

```
pivot = user's mean score       (if ≥ 3 ratings, otherwise 5.5)
s_i   = (score_i − pivot) / 4.5   → roughly in [−1, 1]
```

A 7 from someone who averages 9 is a *negative* signal. This is the single most important line: without it, every rated title counts as "liked".

### Step 2: genre weights with shrinkage

```
w_g = Σ s_i over rated titles with genre g  /  (count_g + 1)
```

The `+1` pseudo-count shrinks genres seen once toward 0, so one lucky rating doesn't dominate. `genreScore(item) = mean of w_g over the item's genres`.

### Step 3: TF-IDF over synopsis + tags

```
tf(t, d)  = count(t in d) / |d|
idf(t)    = ln((1 + N) / (1 + df(t))) + 1          (smoothed, same as scikit-learn)
v_d       = L2-normalise(tf · idf)
```

Tokenizer: lowercase, split on non-alphanumerics (hyphens kept so `found-family` stays one token), drop tokens shorter than 3 and stopwords.

### Step 4: a signed text profile

```
profile = L2-normalise( Σ s_i · v_i )
textScore(item) = profile · v_item        (cosine, in [−1, 1])
```

Because `s_i` is signed, words from low-rated titles get **negative** weight. That is what turns "5 vs 10" into "dislikes tournament/friendship, likes dark/revenge".

### Step 5: blend

```
score = 0.55 · genreScore + 0.45 · textScore + 0.02 · popularity/100
```

Popularity is only a tie-breaker. Rated titles are excluded. With zero ratings the list falls back to popularity (cold start) and says so in the reasons.

### Step 6: reasons

Each recommendation lists: up to 2 genres the user rates above their average, up to 3 shared terms with the biggest positive `profile[t] · v_item[t]` contribution, and any genre the user rates below average ("penalised").

## Result on the demo data

User **kenji** (7 ratings, mean 7.43): Naruto 5, Attack on Titan 10, Demon Slayer 9, My Hero Academia 4, Jujutsu Kaisen 9, Haikyu!! 5, Berserk 10.

| Liked terms | Disliked terms |
|-------------|----------------|
| dark 0.32, revenge 0.21, swordsman 0.21, mercenary 0.16, tragedy 0.15, political 0.13, survival 0.13 | tournament −0.23, rivalry −0.21, school −0.18, training −0.17, friendship −0.16, ninja −0.16 |

Top 5 recommendations: Grandmaster of Demonic Cultivation, Vagabond, Monster, Tales of Demons and Gods, Violet Evergarden. They carry drama, swordsman, revenge and war themes, across manga, manhua and anime. The first one ranks on genre alone (its text score is 0.00), which is one of the weaknesses listed below.

## Unit tests (run on every Docker build)

1. Two same-genre items rated 5 and 10 → a "dark revenge" candidate outranks a "friendly tournament" candidate, while their genre scores are exactly equal (proves the text signal did the work).
2. Rated items are excluded; no ratings → cold-start popularity list.
3. The taste profile lists `revenge` as liked and `tournament` as disliked.

## Complexity

N titles, V vocabulary, R ratings. Building the index is O(total tokens). Scoring is O(N · nnz(v)) with sparse dot products — milliseconds for thousands of titles. At 100k+ titles, precompute item vectors and use an approximate nearest-neighbour index.

## Known weaknesses (and the fix for each)

| Weakness | Seen in testing | Fix |
|----------|-----------------|-----|
| No stemming | `tournament` and `tournaments` are separate terms | Porter/Snowball stemmer or lemmatizer |
| Weak tokens | `against`, `hunts`, `student` | Larger stopword list, minimum df, max-df cut |
| Genre dominates when text overlap is 0 | a title with `text = 0.00` ranked 2nd | Learn the 0.55/0.45 weights on validation data |
| Linear | cannot learn "dark is good, school is fine, dark+school is bad" | Feature MLP (research RQ2) |
| No order/time | a rating from years ago counts the same as yesterday | Time decay now; LSTM later (research RQ1) |
| No trends | ignores what's hot this week | Bandit re-ranker (research RQ3) |

Those last three rows are exactly why the research track exists.
