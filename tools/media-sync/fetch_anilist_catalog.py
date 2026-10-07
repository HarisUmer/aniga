"""Pull the most popular SFW anime, manga, manhwa and manhua from AniList into infra/db/init/006_catalog.sql.

Per title we keep: titles (english/romaji/native), cleaned description, genres, high-rank tags, episodes or
chapters, format, status, season, studios or authors, cover/banner/colour, and an image gallery for anime
(banner, streaming-episode stills, trailer thumbnail) plus main characters with portraits.

Rows upsert on anilist_id, so the 33 hand-seeded titles (matched by 004_media_art.sql) are enriched in place
and keep their ids, ratings and seeded users.

AniList's public API needs no key. Requests are spaced ~2.2 s apart (limit is 90/min, often degraded to 30/min).
Usage:  python tools/media-sync/fetch_anilist_catalog.py [--anime 300 --manga 250 --manhwa 200 --manhua 150]
"""

from __future__ import annotations

import argparse
import html
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "infra" / "db" / "init" / "006_catalog.sql"
RAW = ROOT / "tools" / "media-sync" / "data" / "anilist-catalog.json"

QUERY = """
query ($page: Int, $perPage: Int, $type: MediaType, $country: CountryCode, $formats: [MediaFormat]) {
  Page(page: $page, perPage: $perPage) {
    pageInfo { hasNextPage }
    media(type: $type, countryOfOrigin: $country, format_in: $formats, isAdult: false, sort: [POPULARITY_DESC]) {
      id idMal format status season seasonYear episodes chapters volumes duration popularity averageScore
      countryOfOrigin
      title { romaji english native }
      description(asHtml: false)
      genres
      tags { name rank isAdult isGeneralSpoiler isMediaSpoiler }
      startDate { year }
      coverImage { extraLarge color }
      bannerImage
      trailer { id site thumbnail }
      studios(isMain: true) { nodes { name } }
      staff(perPage: 4, sort: [RELEVANCE]) { edges { role node { name { full } } } }
      streamingEpisodes { title thumbnail }
      characters(perPage: 8, sort: [ROLE, RELEVANCE]) { edges { role node { name { full native } image { large } } } }
    }
  }
}
"""

KINDS = {
    "ANIME": dict(type="ANIME", country=None, formats=["TV", "ONA", "MOVIE"]),
    "MANGA": dict(type="MANGA", country="JP", formats=["MANGA", "ONE_SHOT"]),
    "MANHWA": dict(type="MANGA", country="KR", formats=["MANGA"]),
    "MANHUA": dict(type="MANGA", country="CN", formats=["MANGA"]),
}
PER_PAGE = 25
BAD_GENRES = {"Hentai", "Ecchi"}


def post(variables: dict) -> dict:
    body = json.dumps({"query": QUERY, "variables": variables}).encode()
    for _ in range(8):
        req = urllib.request.Request("https://graphql.anilist.co", data=body, method="POST",
                                     headers={"Content-Type": "application/json", "Accept": "application/json",
                                              "User-Agent": "Aniga/0.1 (practice project)"})
        try:
            with urllib.request.urlopen(req, timeout=40) as resp:
                return json.loads(resp.read())["data"]["Page"]
        except urllib.error.HTTPError as e:
            if e.code == 429:
                wait = int(e.headers.get("Retry-After", "30")) + 1
                print(f"  rate limited, waiting {wait}s", flush=True)
                time.sleep(wait)
                continue
            if e.code >= 500:
                time.sleep(10)
                continue
            raise RuntimeError(f"AniList {e.code}: {e.read()[:300]!r}") from e
        except (urllib.error.URLError, TimeoutError):
            time.sleep(10)
    raise RuntimeError("AniList kept failing")


def fetch(kind: str, count: int) -> list[dict]:
    cfg = KINDS[kind]
    out: list[dict] = []
    page = 1
    while len(out) < count:
        variables = {"page": page, "perPage": PER_PAGE, "type": cfg["type"], "formats": cfg["formats"]}
        if cfg["country"]:
            variables["country"] = cfg["country"]
        data = post(variables)
        for m in data["media"]:
            if set(m["genres"]) & BAD_GENRES:
                continue
            m["_kind"] = kind
            out.append(m)
        print(f"{kind:6} page {page:2}: {len(out)}/{count}", flush=True)
        if not data["pageInfo"]["hasNextPage"]:
            break
        page += 1
        time.sleep(2.2)
    return out[:count]


TAG_RE = re.compile(r"<[^>]+>")
SOURCE_RE = re.compile(r"\(?\s*Source:[^)\n]*\)?\s*$", re.I | re.M)
NOTE_RE = re.compile(r"^\s*(Notes?|Note:).*$", re.I | re.M)


def clean_description(text: str | None) -> str:
    if not text:
        return ""
    text = text.replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n")
    text = html.unescape(TAG_RE.sub("", text))
    text = SOURCE_RE.sub("", text)
    text = NOTE_RE.sub("", text)
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    return text[:1800]


def slug(tag: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", tag.lower()).strip("-")


def lit(v) -> str:
    if v is None:
        return "NULL"
    return "'" + str(v).replace("'", "''") + "'"


def arr(values: list[str]) -> str:
    inner = ",".join('"' + v.replace("\\", "\\\\").replace('"', '\\"') + '"' for v in values)
    return lit("{" + inner + "}")


def jsonb(value) -> str:
    return lit(json.dumps(value, ensure_ascii=False)) + "::jsonb"


def row(m: dict) -> str:
    kind = m["_kind"]
    title = m["title"]["english"] or m["title"]["romaji"]
    tags = [slug(t["name"]) for t in m["tags"]
            if not t["isAdult"] and not t["isGeneralSpoiler"] and not t["isMediaSpoiler"] and t["rank"] >= 60][:12]
    gallery = []
    if m["bannerImage"]:
        gallery.append({"url": m["bannerImage"], "kind": "banner", "caption": "Key visual"})
    if m["trailer"] and m["trailer"]["site"] == "youtube":
        gallery.append({"url": f"https://i.ytimg.com/vi/{m['trailer']['id']}/hqdefault.jpg", "kind": "trailer",
                        "caption": "Trailer", "video": f"https://www.youtube.com/watch?v={m['trailer']['id']}"})
    for ep in (m.get("streamingEpisodes") or [])[:10]:
        if ep.get("thumbnail"):
            gallery.append({"url": ep["thumbnail"], "kind": "episode", "caption": ep.get("title") or "Episode"})
    characters = [{"name": e["node"]["name"]["full"], "native": e["node"]["name"]["native"], "role": e["role"],
                   "image": e["node"]["image"]["large"]}
                  for e in m["characters"]["edges"] if e["node"]["image"]["large"]]
    if kind == "ANIME":
        makers = [n["name"] for n in m["studios"]["nodes"]][:3]
    else:
        makers = list(dict.fromkeys(e["node"]["name"]["full"] for e in m["staff"]["edges"]))[:3]
    year = m["seasonYear"] or (m["startDate"] or {}).get("year")
    popularity = m["popularity"] or 0
    return ("(" + ", ".join([
        lit(kind), lit(title), lit(m["title"]["romaji"]), lit(m["title"]["native"]),
        lit(clean_description(m["description"])), arr(m["genres"]), arr(tags),
        lit(m["episodes"]), lit(m["chapters"]), lit(m["volumes"]), lit(m["duration"]), lit(year),
        lit(m["season"]), lit(m["format"]), lit(m["status"]),
        str(popularity), lit(m["averageScore"]),
        lit(m["coverImage"]["extraLarge"]), lit(m["bannerImage"]), lit(m["coverImage"]["color"]),
        str(m["id"]), lit(m["idMal"]), arr(makers), jsonb(gallery), jsonb(characters),
        "'anilist'", lit(str(m["id"])),
    ]) + ")")


COLUMNS = ("media_type, title, romaji_title, native_title, synopsis, genres, tags, episodes, chapters, volumes, "
           "episode_minutes, start_year, season, format, status, popularity_raw, average_score, cover_url, banner_url, "
           "accent_color, anilist_id, mal_id, makers, gallery, characters, external_source, external_id")


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--anime", type=int, default=300)
    ap.add_argument("--manga", type=int, default=250)
    ap.add_argument("--manhwa", type=int, default=200)
    ap.add_argument("--manhua", type=int, default=150)
    ap.add_argument("--from-cache", action="store_true", help="rebuild SQL from the saved raw JSON")
    args = ap.parse_args()

    if args.from_cache:
        items = json.loads(RAW.read_text(encoding="utf-8"))
    else:
        items = []
        for kind, n in [("ANIME", args.anime), ("MANGA", args.manga), ("MANHWA", args.manhwa), ("MANHUA", args.manhua)]:
            items += fetch(kind, n)
            time.sleep(2.2)
        RAW.parent.mkdir(parents=True, exist_ok=True)
        RAW.write_text(json.dumps(items, ensure_ascii=False), encoding="utf-8")

    seen: set[int] = set()
    rows = []
    for m in items:
        if m["id"] in seen:
            continue
        seen.add(m["id"])
        rows.append(row(m))

    lines = [
        "-- GENERATED by tools/media-sync/fetch_anilist_catalog.py from AniList (https://anilist.co). Do not edit by hand.",
        "-- Descriptions and images belong to their owners via AniList; shown with attribution for a non-commercial project.",
        f"INSERT INTO media ({COLUMNS}) VALUES",
        ",\n".join(rows),
        "ON CONFLICT (anilist_id) DO UPDATE SET",
        "  romaji_title = EXCLUDED.romaji_title, native_title = EXCLUDED.native_title,",
        "  synopsis = EXCLUDED.synopsis, genres = EXCLUDED.genres,",
        "  tags = ARRAY(SELECT DISTINCT unnest(media.tags || EXCLUDED.tags)),",
        "  episodes = coalesce(EXCLUDED.episodes, media.episodes), chapters = coalesce(EXCLUDED.chapters, media.chapters),",
        "  volumes = EXCLUDED.volumes, episode_minutes = EXCLUDED.episode_minutes, season = EXCLUDED.season,",
        "  format = EXCLUDED.format, status = EXCLUDED.status, popularity_raw = EXCLUDED.popularity_raw,",
        "  average_score = EXCLUDED.average_score, cover_url = EXCLUDED.cover_url, banner_url = EXCLUDED.banner_url,",
        "  accent_color = EXCLUDED.accent_color, mal_id = EXCLUDED.mal_id, makers = EXCLUDED.makers,",
        "  gallery = EXCLUDED.gallery, characters = EXCLUDED.characters;",
        "",
        "-- popularity (0-100) = percentile of AniList member count within the catalog, used by the recommenders.",
        "UPDATE media m SET popularity = greatest(1, round(100 * p.pct))::int FROM (",
        "  SELECT id, percent_rank() OVER (ORDER BY popularity_raw) AS pct FROM media WHERE popularity_raw IS NOT NULL",
        ") p WHERE p.id = m.id;",
    ]
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {len(rows)} titles -> {OUT}")


if __name__ == "__main__":
    main()
