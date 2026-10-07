"""End-to-end check of every reading adapter through the backend: popular -> series -> chapters -> pages -> image.

Run with the stack up:  python tools/source-ingest/smoke_adapters.py [--base http://localhost:8080] [--langs en,es,fr]
Exits non-zero if any adapter/language fails, so it can gate CI later.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.parse
import urllib.request

UA = {"User-Agent": "Aniga/0.1 (practice project)"}


def get(url: str, raw: bool = False):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        body = r.read()
        return (r.status, r.headers.get("Content-Type"), len(body)) if raw else json.loads(body)


def check(base: str, source: str, lang: str) -> dict:
    t0 = time.time()
    q = urllib.parse.urlencode({"lang": lang})
    series = get(f"{base}/api/read/{source}/popular?{q}")
    if not series:
        raise RuntimeError("popular returned nothing")
    link_only = None
    for s in series[:6]:
        chapters = get(f"{base}/api/read/{source}/series/{urllib.parse.quote(s['id'])}/chapters?{q}")
        hosted = [c for c in chapters if c["pages"] > 0]
        if not hosted:
            if chapters and not link_only:
                link_only = {"source": source, "lang": lang, "series": s["title"], "chapters": len(chapters),
                             "linkOut": chapters[0]["externalUrl"], "note": "official external chapters only"}
            continue
        ch = hosted[0]
        pages = get(f"{base}/api/read/{source}/chapters/{urllib.parse.quote(ch['id'])}/pages?{q}")
        if not pages["urls"]:
            continue
        status, ctype, size = get(pages["urls"][0], raw=True)
        return {"source": source, "lang": lang, "series": s["title"], "seriesCount": len(series),
                "chapters": len(chapters), "firstChapter": ch.get("number"), "pages": len(pages["urls"]),
                "image": f"{status} {ctype} {size // 1024} KB", "seconds": round(time.time() - t0, 1)}
    if link_only:
        return link_only
    raise RuntimeError("no chapters in the first 6 popular series")


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--langs", default="en,es,fr,pt-BR,id,ja")
    args = ap.parse_args()

    sources = get(f"{args.base}/api/read/sources")
    failures = 0
    for s in sources:
        info = s["info"]
        langs = [info["defaultLanguage"]]
        if info["code"] == "mangadex":
            langs = args.langs.split(",")
        elif info["code"] == "peppercarrot":
            langs = ["en", "fr", "ja"]
        for lang in langs:
            try:
                print(json.dumps(check(args.base, info["code"], lang), ensure_ascii=False), flush=True)
            except Exception as e:  # noqa: BLE001 — report and continue
                failures += 1
                print(json.dumps({"source": info["code"], "lang": lang, "error": str(e)}), flush=True)
    print(f"{failures} failures")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
