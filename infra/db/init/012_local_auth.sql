-- Used when Firebase Email/Password is not switched on yet.
-- The hash is PBKDF2-SHA256. The password itself is never stored.
-- Keep this file identical to backend/src/main/resources/db/012_local_auth.sql.

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS password_hash TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS app_user_email_lower_idx
    ON app_user (lower(email))
    WHERE email IS NOT NULL AND email <> '';
