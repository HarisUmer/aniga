-- Online learning from feedback (see learning/15-online-learning.md).
-- Applied after 007 on a fresh volume; safe to re-run on a live DB.

ALTER TABLE interaction_event DROP CONSTRAINT IF EXISTS interaction_event_event_type_check;
ALTER TABLE interaction_event ADD CONSTRAINT interaction_event_event_type_check CHECK (event_type IN
    ('IMPRESSION', 'CLICK', 'READ_CHAPTER', 'WATCH_EPISODE', 'RATE', 'SKIP', 'DROP', 'LIKE', 'NOT_INTERESTED'));

-- Explicit reactions to a recommendation. NOT_INTERESTED hides the title and counts as a dislike.
CREATE TABLE IF NOT EXISTS rec_feedback (
    user_id    BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    media_id   BIGINT NOT NULL REFERENCES media (id) ON DELETE CASCADE,
    kind       TEXT NOT NULL CHECK (kind IN ('LIKE', 'NOT_INTERESTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, media_id)
);

-- How often a title was recommended and whether it was opened; shown >= 3 with no click = fatigue.
CREATE TABLE IF NOT EXISTS rec_impression (
    user_id       BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    media_id      BIGINT NOT NULL REFERENCES media (id) ON DELETE CASCADE,
    shown         INT NOT NULL DEFAULT 0,
    clicked       INT NOT NULL DEFAULT 0,
    last_slot     TEXT,
    last_shown_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, media_id)
);

-- What the user told us directly: +1 "more of this", -1 "never show me this". feature = g:Horror, t:gore, type:MANHUA
CREATE TABLE IF NOT EXISTS user_pref (
    user_id    BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    feature    TEXT NOT NULL,
    weight     SMALLINT NOT NULL CHECK (weight IN (-1, 1)),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, feature)
);

-- Per-user weights learned online from rewards (logistic SGD); n = how much evidence each feature has seen.
CREATE TABLE IF NOT EXISTS user_online_weight (
    user_id    BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    feature    TEXT NOT NULL,
    weight     REAL NOT NULL,
    n          REAL NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, feature)
);

-- Human-readable audit of why the taste moved ("Horror -0.21 after you rated Another 3/10").
CREATE TABLE IF NOT EXISTS taste_change (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    media_id   BIGINT REFERENCES media (id) ON DELETE CASCADE,
    cause      TEXT NOT NULL,
    reward     REAL NOT NULL,
    expected   REAL,                       -- model's p(like) just before the update: reward − expected = surprise
    deltas     JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS taste_change_user_idx ON taste_change (user_id, created_at DESC);
