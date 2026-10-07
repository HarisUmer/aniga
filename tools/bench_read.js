// Latency of the reading + reverse-search paths: node tools/bench_read.js [baseUrl]
// Cold = first call after a backend restart (hits MangaDex); warm = served from the cache.
const fs = require('fs');
const BASE = process.argv[2] || 'http://localhost:8080';

async function timed(label, path, init) {
  const t = Date.now();
  const res = await fetch(BASE + path, init);
  const body = await res.text();
  const ms = Date.now() - t;
  console.log(`${String(ms).padStart(6)} ms  ${res.status}  ${label}  (${(body.length / 1024).toFixed(1)} KB)`);
  return { ms, json: res.ok ? JSON.parse(body) : null };
}

(async () => {
  const pop = await timed('popular en p0 (cold)', '/api/read/mangadex/popular?lang=en');
  await timed('popular en p0 (warm)', '/api/read/mangadex/popular?lang=en');
  await timed('popular en p1 (cold)', '/api/read/mangadex/popular?lang=en&page=1');
  // Concurrent identical cold requests: single-flight should make them cost one upstream call.
  const t = Date.now();
  await Promise.all([1, 2, 3, 4].map(() => fetch(BASE + '/api/read/mangadex/popular?lang=fr')));
  console.log(`${String(Date.now() - t).padStart(6)} ms       4x concurrent popular fr (single-flight)`);

  const search = await timed('search "one piece"', '/api/read/mangadex/search?q=one%20piece&lang=en');
  const op = search.json && search.json[0];
  if (op) {
    const ch = await timed(`chapters ${op.title} (cold)`, `/api/read/mangadex/series/${op.id}/chapters?lang=en`);
    await timed(`chapters ${op.title} (warm)`, `/api/read/mangadex/series/${op.id}/chapters?lang=en`);
    const hosted = (ch.json || []).filter(c => !c.externalUrl);
    if (hosted.length > 2) {
      await timed('pages ch A (cold)', `/api/read/mangadex/chapters/${hosted[0].id}/pages`);
      await fetch(BASE + `/api/read/mangadex/prefetch?chapters=${hosted[1].id}`, { method: 'POST' });
      await new Promise(r => setTimeout(r, 1500));
      await timed('pages ch B (after prefetch)', `/api/read/mangadex/chapters/${hosted[1].id}/pages`);
    }
  }
  await timed('find across sources', '/api/read/find?title=pepper');

  const img = process.argv[3];
  if (img && fs.existsSync(img)) {
    const form = () => { const f = new FormData(); f.append('file', new Blob([fs.readFileSync(img)]), 'q.jpg'); return f; };
    await timed('image search (cold)', '/api/search/image?scenes=true', { method: 'POST', body: form() });
    await timed('image search (same image)', '/api/search/image?scenes=true', { method: 'POST', body: form() });
  }
})().catch(e => { console.error(e); process.exit(1); });
