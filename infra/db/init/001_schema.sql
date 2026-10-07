-- Aniga core schema (local Postgres). Mirrors what will later move to Supabase,
-- so table names avoid Supabase-reserved ones (auth.users lives in its own schema there).

CREATE TABLE app_user (
    id           BIGSERIAL PRIMARY KEY,
    username     TEXT NOT NULL UNIQUE,
    display_name TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE media (
    id              BIGSERIAL PRIMARY KEY,
    media_type      TEXT NOT NULL CHECK (media_type IN ('ANIME', 'MANGA', 'MANHWA', 'MANHUA')),
    title           TEXT NOT NULL,
    synopsis        TEXT NOT NULL DEFAULT '',
    genres          TEXT[] NOT NULL DEFAULT '{}',
    tags            TEXT[] NOT NULL DEFAULT '{}',
    episodes        INT,
    chapters        INT,
    start_year      INT,
    popularity      INT NOT NULL DEFAULT 0,
    cover_url       TEXT,
    banner_url      TEXT,
    -- Dominant cover colour from AniList (#rrggbb); the UI tints detail screens with it.
    accent_color    TEXT,
    anilist_id      INT UNIQUE,
    average_score   INT,
    romaji_title    TEXT,
    native_title    TEXT,
    volumes         INT,
    episode_minutes INT,
    season          TEXT,
    format          TEXT,
    status          TEXT,
    popularity_raw  INT,
    mal_id          INT,
    -- Studios for anime, story/art credits for comics.
    makers          TEXT[] NOT NULL DEFAULT '{}',
    -- [{url, kind: banner|trailer|episode, caption, video?}] — anime have no chapters, so images carry the page.
    gallery         JSONB NOT NULL DEFAULT '[]',
    -- [{name, native, role, image}]
    characters      JSONB NOT NULL DEFAULT '[]',
    -- 64-bit dHash of the cover, filled in by the backend for reverse image search.
    cover_hash      BIGINT,
    external_source TEXT,
    external_id     TEXT,
    UNIQUE (external_source, external_id)
);
CREATE INDEX media_genres_gin ON media USING GIN (genres);
CREATE INDEX media_type_idx ON media (media_type);

CREATE TABLE rating (
    user_id    BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    media_id   BIGINT NOT NULL REFERENCES media (id) ON DELETE CASCADE,
    score      SMALLINT NOT NULL CHECK (score BETWEEN 1 AND 10),
    rated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, media_id)
);

CREATE TABLE library_entry (
    user_id    BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    media_id   BIGINT NOT NULL REFERENCES media (id) ON DELETE CASCADE,
    status     TEXT NOT NULL CHECK (status IN ('PLANNING', 'CURRENT', 'COMPLETED', 'DROPPED', 'PAUSED')),
    progress   INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, media_id)
);

-- Ordered event log: input sequence for the LSTM and reward signal for the RL re-ranker.
CREATE TABLE interaction_event (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    media_id    BIGINT NOT NULL REFERENCES media (id) ON DELETE CASCADE,
    event_type  TEXT NOT NULL CHECK (event_type IN ('IMPRESSION', 'CLICK', 'READ_CHAPTER', 'WATCH_EPISODE', 'RATE', 'SKIP', 'DROP')),
    value       REAL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX interaction_user_time_idx ON interaction_event (user_id, occurred_at);

-- Catalog of reader-app sources imported from public extension indexes (metadata only, no content).
CREATE TABLE source_catalog (
    id             TEXT PRIMARY KEY,
    name           TEXT NOT NULL,
    lang           TEXT NOT NULL,
    home_url       TEXT,
    content_hint   TEXT NOT NULL CHECK (content_hint IN ('MANGA', 'MANHWA', 'MANHUA', 'MIXED')),
    nsfw           BOOLEAN NOT NULL DEFAULT FALSE,
    extension_name TEXT NOT NULL,
    package_name   TEXT,
    version        TEXT,
    index_repo     TEXT NOT NULL,
    -- Set when one of our adapters can read this extension's content in-app (see 005_sources_v2.sql).
    adapter_code   TEXT,
    adapter_lang   TEXT
);
CREATE INDEX source_catalog_lang_idx ON source_catalog (lang);
CREATE INDEX source_catalog_hint_idx ON source_catalog (content_hint);

-- Result of the last link check of each extension's home page (UP / MOVED / BLOCKED / DOWN).
CREATE TABLE source_health (
    source_id   TEXT PRIMARY KEY REFERENCES source_catalog (id) ON DELETE CASCADE,
    status      TEXT NOT NULL CHECK (status IN ('UP', 'MOVED', 'BLOCKED', 'DOWN')),
    http_status INT,
    latency_ms  INT,
    final_url   TEXT,
    error       TEXT,
    checked_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Adapters the app can actually read from. A catalog row is just a pointer; an adapter is working code.
CREATE TABLE source_adapter (
    code         TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    kind         TEXT NOT NULL CHECK (kind IN ('OFFICIAL_API', 'CMS_FAMILY')),
    base_url     TEXT NOT NULL,
    enabled      BOOLEAN NOT NULL DEFAULT FALSE,
    notes        TEXT
);

-- Chat (phase 7). Kept in the schema now so the data model is reviewed together.
CREATE TABLE chat_room (
    id         BIGSERIAL PRIMARY KEY,
    room_type  TEXT NOT NULL CHECK (room_type IN ('DIRECT', 'GROUP')),
    title      TEXT,
    created_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chat_member (
    room_id   BIGINT NOT NULL REFERENCES chat_room (id) ON DELETE CASCADE,
    user_id   BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role      TEXT NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('OWNER', 'MEMBER')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id)
);

CREATE TABLE chat_message (
    id        BIGSERIAL PRIMARY KEY,
    room_id   BIGINT NOT NULL REFERENCES chat_room (id) ON DELETE CASCADE,
    sender_id BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    body      TEXT NOT NULL,
    sent_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX chat_message_room_time_idx ON chat_message (room_id, sent_at);
