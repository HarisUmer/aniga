-- Second perceptual hash per cover (its centre 80%), so screenshots cropped tighter than the cover still match.
-- Filled by ImageSearchService's background indexer; see learning/14-image-search.md.
ALTER TABLE media ADD COLUMN IF NOT EXISTS cover_hash_zoom BIGINT;
