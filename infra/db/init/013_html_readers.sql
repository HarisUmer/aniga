-- AsuraComic, MangaTown, and MangaGeko. English catalog rows only.
-- A fresh database runs this from docker-entrypoint-initdb.d. An existing database needs the same file applied by hand.

INSERT INTO source_adapter (code, display_name, kind, base_url, enabled, notes) VALUES
('asura', 'AsuraComic', 'CMS_FAMILY', 'https://asurascans.com', TRUE,
 'English HTML reader. Series id is the site slug. Chapter id is slug:chapterSlug.'),
('mangatown', 'MangaTown', 'CMS_FAMILY', 'https://www.mangatown.com', TRUE,
 'English HTML reader. Series id is the site slug. Chapter id is slug:chapterSlug.'),
('mangageko', 'MangaGeko', 'CMS_FAMILY', 'https://www.mgeko.cc', TRUE,
 'English HTML reader. Series id is the site slug. Chapter id is slug:chapterSlug.')
ON CONFLICT (code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    kind = EXCLUDED.kind,
    base_url = EXCLUDED.base_url,
    enabled = EXCLUDED.enabled,
    notes = EXCLUDED.notes;

-- asuracomic.net redirects to asurascans.com. The adapter reads the new host.
UPDATE source_catalog
SET adapter_code = 'asura', adapter_lang = 'en', home_url = 'https://asurascans.com'
WHERE extension_name = 'Asura Scans' AND lang = 'en';

UPDATE source_catalog
SET adapter_code = 'mangatown', adapter_lang = 'en'
WHERE home_url = 'https://www.mangatown.com' AND lang = 'en';

UPDATE source_catalog
SET adapter_code = 'mangageko', adapter_lang = 'en'
WHERE home_url = 'https://www.mgeko.cc' AND lang = 'en';
