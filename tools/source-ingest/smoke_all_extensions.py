"""Walk every catalog extension that maps to an adapter: popular -> chapters -> pages, through the backend.

Writes tools/source-ingest/data/extension-smoke.json and prints one line per extension.
A language passes when some popular series has a chapter whose pages resolve (or, failing that,
an official external link-out chapter). Languages where MangaDex simply has no hosted chapters are
reported as EMPTY, not as failures of the adapter.
"""
import json
import pathlib
import sys
import time
import urllib.parse
import urllib.request

API = "http://localhost:8080/api"
OUT = pathlib.Path(__file__).parent / "data" / "extension-smoke.json"


def get(path):
    with urllib.request.urlopen(API + path, timeout=60) as r:
        return json.loads(r.read())


def check(adapter, lang):
    q = urllib.parse.quote
    started = time.time()
    series = get(f"/read/{adapter}/popular?lang={q(lang)}")
    if not series:
        return {"status": "EMPTY", "series": 0}
    for s in series[:3]:
        chapters = get(f"/read/{adapter}/series/{q(s['id'])}/chapters?lang={q(lang)}")
        hosted = [c for c in chapters if not c.get("externalUrl")]
        if hosted:
            pages = get(f"/read/{adapter}/chapters/{q(hosted[0]['id'])}/pages?lang={q(lang)}")
            return {"status": "OK", "series": len(series), "title": s["title"], "chapters": len(chapters),
                    "pages": len(pages["urls"]), "seconds": round(time.time() - started, 1)}
        if chapters:
            return {"status": "LINK_OUT", "series": len(series), "title": s["title"], "chapters": len(chapters)}
    return {"status": "EMPTY", "series": len(series)}


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    catalog = get("/sources?readable=true&size=500")
    items = catalog["items"] if isinstance(catalog, dict) else catalog
    results = []
    for src in items:
        adapter, lang = src.get("adapterCode"), src.get("adapterLang") or "en"
        try:
            r = check(adapter, lang)
        except Exception as e:  # noqa: BLE001 - report and keep going
            r = {"status": "FAIL", "error": str(e)[:160]}
        r.update({"name": src["name"], "adapter": adapter, "lang": lang})
        results.append(r)
        print(json.dumps(r, ensure_ascii=False), flush=True)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(results, indent=1, ensure_ascii=False), encoding="utf-8")
    counts = {}
    for r in results:
        counts[r["status"]] = counts.get(r["status"], 0) + 1
    print("SUMMARY", counts)


if __name__ == "__main__":
    main()
