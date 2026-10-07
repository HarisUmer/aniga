-- Remember which reading source a shelved comic is being read on, so Home can jump straight back in
-- ("continue") and the Library updates feed can diff the source's chapter list against what was last seen.
ALTER TABLE library_entry
    ADD COLUMN IF NOT EXISTS source_code   TEXT,
    ADD COLUMN IF NOT EXISTS series_id     TEXT,
    ADD COLUMN IF NOT EXISTS series_title  TEXT,
    ADD COLUMN IF NOT EXISTS source_lang   TEXT,
    -- Highest chapter number the user has seen listed; a larger live count means "new chapters".
    ADD COLUMN IF NOT EXISTS chapters_known DOUBLE PRECISION;

-- Lets a chat message carry a shared title ("I'm reading this") or a rating, rendered as a card.
ALTER TABLE chat_message
    ADD COLUMN IF NOT EXISTS share JSONB;
