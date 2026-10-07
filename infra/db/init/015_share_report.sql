-- One public taste-report link per account. The token is the only way to open it;
-- knowing a user id is not enough. Replaced only when the owner asks for a new link.
CREATE TABLE IF NOT EXISTS share_report (
    user_id    BIGINT PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    token      TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
