-- Firebase Authentication owns identity. Postgres keeps the relational data.
-- firebase_uid ties app_user to a Firebase user. Passwords are not stored here.
-- Chat is pushed with NOTIFY on the subject chat_message, not the Firebase Realtime Database.
-- @mentions land in chat_mention. Page-link results and user reports are counted below.

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS firebase_uid TEXT;
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS email TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS app_user_firebase_uid_idx ON app_user (firebase_uid);

CREATE TABLE IF NOT EXISTS chat_mention (
    message_id BIGINT NOT NULL REFERENCES chat_message (id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    PRIMARY KEY (message_id, user_id)
);

CREATE TABLE IF NOT EXISTS source_parse_event (
    id          BIGSERIAL PRIMARY KEY,
    source_code TEXT NOT NULL,
    target      TEXT,
    outcome     TEXT NOT NULL CHECK (outcome IN ('OK', 'FAIL')),
    error       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS source_parse_event_time_idx ON source_parse_event (created_at);

CREATE TABLE IF NOT EXISTS source_report (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    source_code TEXT,
    target      TEXT,
    reason      TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS source_report_time_idx ON source_report (created_at);

CREATE OR REPLACE FUNCTION notify_chat_message() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_notify('chat_message', NEW.id::text);
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS chat_message_notify ON chat_message;
CREATE TRIGGER chat_message_notify
    AFTER INSERT ON chat_message
    FOR EACH ROW EXECUTE FUNCTION notify_chat_message();
