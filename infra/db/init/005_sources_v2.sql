-- Link catalog extensions to the adapters that can read them in-app.
-- MangaDex: one Keiyoushi extension per language, all served by the same API with a language filter.
UPDATE source_catalog SET adapter_code = 'mangadex', adapter_lang = lang
WHERE extension_name = 'MangaDex' AND home_url = 'https://mangadex.org';

UPDATE source_catalog SET adapter_code = 'peppercarrot', adapter_lang = 'en'
WHERE extension_name = 'Pepper&Carrot';

-- Only the original English xkcd has the JSON API; es/fr/ru/zh are independent fan-translation sites.
UPDATE source_catalog SET adapter_code = 'xkcd', adapter_lang = 'en'
WHERE extension_name = 'xkcd' AND home_url = 'https://xkcd.com';

INSERT INTO source_adapter (code, display_name, kind, base_url, enabled, notes) VALUES
('peppercarrot', 'Pepper&Carrot', 'OFFICIAL_API', 'https://www.peppercarrot.com/0_sources/', TRUE,
 'Open webcomic, CC BY 4.0. Episode index episodes-v1.json; pages per language. Credit David Revoy.'),
('xkcd', 'xkcd', 'OFFICIAL_API', 'https://xkcd.com', TRUE,
 'CC BY-NC 2.5. Archive page scraped with Jsoup for the comic list; /N/info.0.json for each strip.')
ON CONFLICT (code) DO NOTHING;
