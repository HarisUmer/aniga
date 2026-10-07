-- People: friends, a shared global room, and group rooms.
-- Idempotent so it can run on every boot alongside the backend's own bootstrap.

-- Allow a single public GLOBAL room in addition to DIRECT and GROUP.
ALTER TABLE chat_room DROP CONSTRAINT IF EXISTS chat_room_room_type_check;
ALTER TABLE chat_room ADD CONSTRAINT chat_room_room_type_check
    CHECK (room_type IN ('DIRECT', 'GROUP', 'GLOBAL'));

CREATE TABLE IF NOT EXISTS friend_request (
    id           BIGSERIAL PRIMARY KEY,
    requester_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    addressee_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    status       TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at TIMESTAMPTZ,
    CHECK (requester_id <> addressee_id),
    UNIQUE (requester_id, addressee_id)
);
CREATE INDEX IF NOT EXISTS friend_request_addressee_idx ON friend_request (addressee_id, status);
CREATE INDEX IF NOT EXISTS friend_request_requester_idx ON friend_request (requester_id, status);

-- Exactly one global room.
INSERT INTO chat_room (room_type, title)
SELECT 'GLOBAL', 'Global'
WHERE NOT EXISTS (SELECT 1 FROM chat_room WHERE room_type = 'GLOBAL');
