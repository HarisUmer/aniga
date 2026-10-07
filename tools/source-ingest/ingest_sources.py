"""Turn reader-app extension indexes into a SQL seed for the source_catalog table.

Supports the formats documented in Z:/pange/animation/knowledge_base/08-reader-app-source-indexes.md:
  mihon-repo-v2     {extensionList: {extensions: [...]}}       (Keiyoushi, Mihon 0.20.1+)
  tachiyomi-legacy  [ {pkg, apk, nsfw, sources: [...]} ]
  aidoku-v1         {sources: [ {id, baseURL, contentRating} ]}

Only metadata is stored (site name, language, home URL, NSFW flag). No site is contacted.

Usage:
  python ingest_sources.py                                   # local Keiyoushi snapshot
  python ingest_sources.py --catalog <08-source-index-catalog.json>   # every verified repo
  python ingest_sources.py --include-nsfw
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_INPUT = HERE / "data" / "keiyoushi-index.json"
DEFAULT_OUTPUT = HERE.parent.parent / "infra" / "db" / "init" / "003_source_catalog.sql"

MANHWA_WORDS = ("toon", "manhwa", "webtoon", "comic-k", "naver", "kakao")
MANHUA_WORDS = ("manhua", "bilibili", "kuaikan", "dmzj")


def content_hint(lang: str, name: str, url: str) -> str:
    text = f"{name} {url}".lower()
    if any(w in text for w in MANHUA_WORDS) or lang.startswith("zh"):
        return "MANHUA"
    if any(w in text for w in MANHWA_WORDS) or lang == "ko":
        return "MANHWA"
    if lang == "ja":
        return "MANGA"
    return "MIXED"


def detect_format(data) -> str:
    if isinstance(data, dict) and "extensionList" in data:
        return "mihon-repo-v2"
    if isinstance(data, dict) and "sources" in data:
        return "aidoku-v1"
    if isinstance(data, list) and data and "apk" in data[0]:
        return "tachiyomi-legacy"
    if isinstance(data, list) and data and "file" in data[0]:
        return "aidoku-legacy"
    raise ValueError("Unknown index format")


def rows_from_index(data, repo: str) -> list[dict]:
    fmt = detect_format(data)
    rows: list[dict] = []
    if fmt == "mihon-repo-v2":
        for ext in data["extensionList"]["extensions"]:
            nsfw = ext.get("contentWarning") == "CONTENT_WARNING_NSFW"
            for src in ext.get("sources", []):
                rows.append(dict(
                    id=f"{repo}:{src['id']}", name=src["name"], lang=src.get("language", "all"),
                    home_url=src.get("homeUrl"), nsfw=nsfw, extension_name=ext["name"],
                    package_name=ext.get("packageName"), version=ext.get("versionName"), repo=repo))
    elif fmt == "tachiyomi-legacy":
        for ext in data:
            nsfw = int(ext.get("nsfw", 0)) == 1
            for src in ext.get("sources", []):
                rows.append(dict(
                    id=f"{repo}:{src['id']}", name=src["name"], lang=src.get("lang", "all"),
                    home_url=src.get("baseUrl"), nsfw=nsfw, extension_name=ext["name"],
                    package_name=ext.get("pkg"), version=ext.get("version"), repo=repo))
    elif fmt == "aidoku-v1":
        for src in data["sources"]:
            langs = src.get("languages") or ["all"]
            rows.append(dict(
                id=f"{repo}:{src['id']}", name=src["name"], lang=langs[0] if len(langs) == 1 else "multi",
                home_url=src.get("baseURL"), nsfw=int(src.get("contentRating", 0)) >= 1,
                extension_name=src["name"], package_name=src["id"], version=str(src.get("version")), repo=repo))
    else:
        print(f"  skip {repo}: aidoku-legacy has no site URL field", file=sys.stderr)
    return rows


def load_json(location: str):
    if location.startswith("http"):
        req = urllib.request.Request(location, headers={"User-Agent": "Aniga-source-ingest/0.1"})
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode("utf-8"))
    return json.loads(Path(location).read_text(encoding="utf-8"))


def sql_literal(value) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    return "'" + str(value).replace("'", "''") + "'"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default=str(DEFAULT_INPUT), help="index file path or URL")
    parser.add_argument("--repo", default="keiyoushi")
    parser.add_argument("--catalog", help="08-source-index-catalog.json: ingest every verified repo")
    parser.add_argument("--include-nsfw", action="store_true")
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT))
    args = parser.parse_args()

    targets = [(args.repo, args.input)]
    if args.catalog:
        catalog = json.loads(Path(args.catalog).read_text(encoding="utf-8"))
        targets = [(r["id"], r["indexUrl"]) for r in catalog["repositories"] if r.get("status") == "verified"]

    rows: list[dict] = []
    for repo, location in targets:
        try:
            got = rows_from_index(load_json(location), repo)
            print(f"  {repo}: {len(got)} sources")
            rows.extend(got)
        except Exception as exc:  # one broken repo should not abort the whole import
            print(f"  {repo}: FAILED ({exc})", file=sys.stderr)

    seen: set[str] = set()
    kept = []
    for r in rows:
        if r["nsfw"] and not args.include_nsfw:
            continue
        if r["id"] in seen:
            continue
        seen.add(r["id"])
        r["hint"] = content_hint(r["lang"], r["name"], r["home_url"] or "")
        kept.append(r)

    lines = [
        "-- GENERATED by tools/source-ingest/ingest_sources.py. Do not edit by hand.",
        f"-- {len(kept)} sources (nsfw {'included' if args.include_nsfw else 'excluded'}).",
    ]
    for i in range(0, len(kept), 500):
        lines.append("INSERT INTO source_catalog (id, name, lang, home_url, content_hint, nsfw, "
                     "extension_name, package_name, version, index_repo) VALUES")
        values = [
            "(" + ", ".join(sql_literal(v) for v in (
                r["id"], r["name"], r["lang"], r["home_url"], r["hint"], r["nsfw"],
                r["extension_name"], r["package_name"], r["version"], r["repo"])) + ")"
            for r in kept[i:i + 500]
        ]
        lines.append(",\n".join(values) + "\nON CONFLICT (id) DO NOTHING;")

    Path(args.output).write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"wrote {len(kept)} rows -> {args.output}")
    print("by hint:", dict(Counter(r["hint"] for r in kept)))
    print("top langs:", Counter(r["lang"] for r in kept).most_common(8))


if __name__ == "__main__":
    main()
