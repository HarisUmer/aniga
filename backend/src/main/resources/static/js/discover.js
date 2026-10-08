/* ---------- discover: title (per source + language) · story (BM25) · image (trace.moe + dHash) ---------- */
var disc = { mode: 'title', source: 'mangadex', sources: [] };
async function initDiscover() {
  try {
    disc.sources = await api('/api/read/sources');
    const chips = [{ code: 'catalog', name: 'Catalog' }, ...disc.sources.map(s => ({ code: s.info.code, name: s.info.displayName + (s.catalogExtensions > 1 ? ' ' + s.catalogExtensions : '') }))];
    $('dSources').innerHTML = chips.map(c => `<span class="chip ${c.code === disc.source ? 'on' : ''}" data-c="${c.code}">${esc(c.name)}</span>`).join('');
    $('dSources').querySelectorAll('.chip').forEach(c => c.onclick = () => {
      disc.source = c.dataset.c; $('dSources').querySelectorAll('.chip').forEach(x => x.classList.toggle('on', x === c)); fillLangs(); titleSearch();
    });
    fillLangs(); titleSearch();
  } catch (e) { $('dOut').innerHTML = ballStatus(e.message); }
}
function fillLangs() {
  const src = disc.sources.find(s => s.info.code === disc.source);
  const langs = src ? src.info.languages : ['en'];
  $('dLang').innerHTML = langs.map(l => `<option ${l === readLang() ? 'selected' : ''}>${l}</option>`).join('');
  $('dLang').disabled = disc.source === 'catalog';
}
$('dLang').onchange = () => { localStorage.setItem('aniga.lang', $('dLang').value); titleSearch(); };
$('dModes').querySelectorAll('button').forEach(b => b.onclick = () => {
  disc.mode = b.dataset.m; $('dModes').querySelectorAll('button').forEach(x => x.classList.toggle('on', x === b));
  $('dTitleBar').style.display = disc.mode === 'title' ? 'flex' : 'none';
  $('dStoryBar').style.display = disc.mode === 'story' ? 'flex' : 'none';
  $('dImageBar').style.display = disc.mode === 'image' ? 'block' : 'none';
  $('kanjiBg').textContent = { title: '探', story: '語', image: '絵' }[disc.mode];
  $('dNote').textContent = ''; $('dOut').innerHTML = disc.mode === 'title' ? '' : ballStatus(disc.mode === 'story' ? 'Describe a plot, a character or a vibe.' : 'Drop an anime screenshot to find the episode, or a cover to find the title.');
  if (disc.mode === 'title') titleSearch();
});
function showTiles(items, note) {
  $('dNote').textContent = note || '';
  if (!items.length) { $('dOut').innerHTML = ballStatus('Nothing found. The ball rolled past.'); return; }
  $('dOut').innerHTML = GRID(items.map((x, i) => coverTile(x.m, i, x.meta, x.badge)).join(''));
  $('dOut').querySelectorAll('.cover').forEach((el, i) => el.onclick = () => items[i].series ? openDetail(null, items[i].series) : openDetail(items[i].m.id));
}
async function titleSearch() {
  const q = $('dQ').value.trim(), lang = readLang();
  $('dOut').innerHTML = planeLoader(q ? 'Searching…' : 'Opening the shelf of this source…');
  try {
    if (disc.source === 'catalog') {
      const list = await api(q ? `/api/media?q=${encodeURIComponent(q)}` : '/api/media?sort=popularity&limit=60');
      showTiles(list.map(m => ({ m })), q ? '' : 'Aniga catalog · 900 AniList titles · most popular first');
      return;
    }
    const list = await api(q ? `/api/read/${disc.source}/search?q=${encodeURIComponent(q)}&lang=${lang}` : `/api/read/${disc.source}/popular?lang=${lang}`);
    showTiles(list.map(s => ({ m: { ...s, mediaType: s.contentType }, series: s, meta: [s.year, s.status].filter(Boolean).join(' · ').toUpperCase() })),
      q ? '' : `${disc.source} · ${lang} · series with chapters in this language`);
  } catch (e) { $('dOut').innerHTML = ballStatus(e.message); }
}
$('dBtn').onclick = titleSearch;
$('dQ').onkeydown = (e) => { if (e.key === 'Enter') titleSearch(); };
async function storySearch() {
  const q = $('sQ').value.trim(); if (!q) return;
  $('dOut').innerHTML = planeLoader('Reading every synopsis…');
  try {
    const hits = await api(`/api/search?q=${encodeURIComponent(q)}&limit=30`);
    showTiles(hits.map(h => ({ m: h.media, badge: (h.matched || []).slice(0, 2).join(' ') })), `${hits.length} matches · BM25 over title, synopsis, tags, genres and character names · badge = matched words`);
  } catch (e) { $('dOut').innerHTML = ballStatus(e.message); }
}
$('sBtn').onclick = storySearch;
$('sQ').onkeydown = (e) => { if (e.key === 'Enter') storySearch(); };
async function imageSearch(file) {
  if (!file) return;
  $('dOut').innerHTML = planeLoader('Looking closely…');
  const fd = new FormData(); fd.append('file', file);
  try {
    const res = await fetch('/api/search/image?scenes=true', { method: 'POST', body: fd });
    if (!res.ok) throw new Error((await res.json().catch(() => ({}))).detail || res.statusText);
    const r = await res.json(), seen = new Set(), items = [];
    const clock = (s) => `${Math.floor(s / 60)}:${String(Math.round(s % 60)).padStart(2, '0')}`;
    let note = '';
    for (const sc of r.scenes || []) {
      if (!sc.media || seen.has(sc.media.id)) continue; seen.add(sc.media.id);
      const ep = sc.episode ? `EP ${Math.round(sc.episode)} · ` : '';
      items.push({ m: sc.media, badge: ep + clock(sc.from) });
      if (!note) note = `Scene: ${sc.title || sc.media.title} · ${ep}${clock(sc.from)} · ${Math.round(sc.similarity * 100)}% similar (trace.moe)`;
    }
    for (const c of r.covers || []) { if (!c.media || seen.has(c.media.id)) continue; seen.add(c.media.id); items.push({ m: c.media, badge: Math.round(c.similarity * 100) + '% cover' }); }
    showTiles(items, note || (r.sceneError ? 'Scene search unavailable · ' : '') + `cover matches by perceptual hash · ${r.coversIndexed} covers indexed`);
  } catch (e) { $('dOut').innerHTML = ballStatus(e.message); }
}
$('imgFile').onchange = (e) => imageSearch(e.target.files[0]);
['dragover', 'dragenter'].forEach(k => $('dz').addEventListener(k, e => { e.preventDefault(); $('dz').classList.add('over'); }));
['dragleave', 'drop'].forEach(k => $('dz').addEventListener(k, e => { e.preventDefault(); $('dz').classList.remove('over'); }));
$('dz').addEventListener('drop', e => imageSearch(e.dataTransfer.files[0]));
document.addEventListener('paste', e => { if (disc.mode === 'image' && e.clipboardData.files[0]) imageSearch(e.clipboardData.files[0]); });

