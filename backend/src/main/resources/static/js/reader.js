/* ---------- reader ---------- */
var TONES = [['night', 'Night ink', '#0E0C0A', '#E9E1D2'], ['sepia', 'Sepia', '#EDE0C4', '#4A3F33'], ['paper', 'Paper', '#F6F1E7', '#221C17'], ['oled', 'OLED', '#000000', '#D8D2C8']];
var rd = { title: '', type: '', chs: [], i: 0, lastY: 0 };
function setTone(tone) {
  $('reader').dataset.tone = tone; localStorage.setItem('aniga.tone', tone);
  $('tones').querySelectorAll('button').forEach(b => b.classList.toggle('on', b.dataset.tone === tone));
}
$('tones').innerHTML = TONES.map(([k, label, bg, fg]) => `<button data-tone="${k}" title="${label}" style="background:${bg};color:${fg}">Aa</button>`).join('');
$('tones').querySelectorAll('button').forEach(b => b.onclick = () => setTone(b.dataset.tone));
setTone(localStorage.getItem('aniga.tone') || 'night');

function drawFlight(p) {
  const x = 12 + (900 - 24) * p;
  $('flight').innerHTML = `<line x1="${x}" y1="11" x2="888" y2="11" stroke="var(--r-text)" stroke-opacity=".35" stroke-width="1.5" stroke-dasharray="4 5"/>
    <line x1="12" y1="11" x2="${x}" y2="11" stroke="var(--r-accent)" stroke-width="2.4" stroke-linecap="round"/>
    <path transform="translate(${x},11)" d="M8,0 L-8,-5.5 L-3.5,0 L-8,4.5 Z" fill="var(--r-text)" stroke="#0E0C0A" stroke-width="1"/>`;
}
function openReader(title, type, chs, i, opts = {}) {
  Object.assign(rd, { title, type, chs, i, source: opts.source || 'mangadex', mediaId: opts.mediaId || null, saved: 0, pre: null });
  $('reader').classList.add('on'); document.body.style.overflow = 'hidden';
  Sakura.pause(true);
  loadChapter();
}
/* Next chapter is fetched (page list + first images) while you are 60% through this one, and the one after is
 * warmed on the server via /prefetch, so "Next chapter" opens without a spinner. */
function prefetchNext() {
  const next = rd.chs[rd.i + 1];
  if (!next || (rd.pre && rd.pre.id === next.id)) return;
  const pre = { id: next.id, p: null };
  rd.pre = pre;
  api(`/api/read/${rd.source}/chapters/${encodeURIComponent(next.id)}/pages?lang=${next.language || readLang()}`)
    .then(p => { pre.p = p; p.urls.slice(0, 3).forEach(u => { const im = new Image(); im.referrerPolicy = 'no-referrer'; im.src = u; }); })
    .catch(() => { if (rd.pre === pre) rd.pre = null; });
  const after = rd.chs[rd.i + 2];
  if (after) fetch(`/api/read/${rd.source}/prefetch?chapters=${encodeURIComponent(after.id)}`, { method: 'POST' }).catch(() => {});
}
/* reaching the end of a chapter moves the shelf forward, never backwards */
async function chapterFinished(ch) {
  const n = Math.floor(+ch.number || 0);
  if (!rd.mediaId || n <= rd.saved) return;
  try {
    const cur = await api(`/api/users/${state.user}/library/${rd.mediaId}`);
    if (n <= cur.progress) { rd.saved = cur.progress; return; }
    const saved = await api(`/api/users/${state.user}/library/${rd.mediaId}`, { method: 'PUT', body: JSON.stringify({ progress: n }) });
    rd.saved = n;
    state.library.set(rd.mediaId, { status: saved.status, progress: saved.progress, score: saved.score });
    patchTiles(rd.mediaId);
    if (rd.i >= rd.chs.length - 1) burstAt($('rEnd'), 24);
    const tag = document.createElement('div'); tag.className = 'kicker'; tag.style.marginTop = '8px'; tag.textContent = `Shelf updated · CH ${n}`;
    $('rEnd').querySelector('.rend')?.appendChild(tag);
  } catch {}
}
async function loadChapter() {
  const ch = rd.chs[rd.i];
  $('rTitle').textContent = rd.title;
  $('rSub').textContent = `Chapter ${ch.number || '—'} · ${rd.i + 1} / ${rd.chs.length} · ${ch.group || rd.source}`;
  $('rPrev').disabled = rd.i === 0; $('rNext').disabled = rd.i >= rd.chs.length - 1;
  $('rPages').className = 'pages' + (rd.type === 'MANGA' || rd.type === 'WEBCOMIC' ? ' gap' : '');
  $('rPages').innerHTML = planeLoader('Loading pages…'); $('rEnd').innerHTML = ''; $('reader').scrollTop = 0; drawFlight(0);
  try {
    const p = rd.pre && rd.pre.id === ch.id && rd.pre.p ? rd.pre.p
      : await api(`/api/read/${rd.source}/chapters/${encodeURIComponent(ch.id)}/pages?lang=${ch.language || readLang()}`);
    rd.pre = null;
    if (rd.chs[rd.i] !== ch) return;
    const fb = p.fallbackUrls || [], caps = p.captions || [];
    $('rPages').innerHTML = p.urls.map((u, i) => `<figure style="margin:0"><img loading="lazy" src="${esc(u)}" data-fb="${esc(fb[i] || '')}" alt="Page ${i + 1}">${caps[i] ? `<figcaption>${esc(caps[i])}</figcaption>` : ''}</figure>`).join('');
    $('rPages').querySelectorAll('img').forEach(img => {
      img.addEventListener('load', () => img.classList.add('ok'));
      img.addEventListener('error', () => { if (img.dataset.fb && img.src !== img.dataset.fb) img.src = img.dataset.fb; });
    });
    const last = rd.i >= rd.chs.length - 1;
    $('rEnd').innerHTML = `<div class="rend">${ballStatus('', getComputedStyle($('reader')).getPropertyValue('--r-accent').trim() || '#E5532D')}
      <h3 class="display">${last ? "You're caught up." : 'End of chapter ' + esc(ch.number || '')}</h3>${p.credit ? `<div class="mono muted" style="font-size:11px;margin:6px 0 10px">${esc(p.credit)}</div>` : ''}${last ? '' : '<button class="btn" id="rEndNext">Next chapter</button>'}</div>`;
    if (!last) $('rEndNext').onclick = () => { rd.i++; loadChapter(); };
    $('rPage').textContent = `1 / ${p.urls.length}`;
    const seen = new IntersectionObserver((es) => { if (es.some(e => e.isIntersecting)) { seen.disconnect(); if (rd.chs[rd.i] === ch) chapterFinished(ch); } }, { root: $('reader') });
    seen.observe($('rEnd'));
  } catch (e) { $('rPages').innerHTML = ballStatus(e.message); }
}
$('reader').addEventListener('scroll', () => {
  const r = $('reader'), y = r.scrollTop, max = r.scrollHeight - r.clientHeight;
  drawFlight(max > 0 ? y / max : 0);
  if (max > 0 && y / max > .6) prefetchNext();
  if (y > rd.lastY + 12) $('rchrome').classList.add('hide'); else if (y < rd.lastY - 12) $('rchrome').classList.remove('hide');
  rd.lastY = y;
  const imgs = [...$('rPages').querySelectorAll('img')];
  const cur = imgs.findIndex(img => img.getBoundingClientRect().bottom > 120);
  if (imgs.length) $('rPage').textContent = `${Math.max(1, cur + 1)} / ${imgs.length}`;
});
$('rPages').onclick = () => $('rchrome').classList.toggle('hide');
$('rClose').onclick = () => { $('reader').classList.remove('on'); document.body.style.overflow = ''; Sakura.pause(false); };
$('rPrev').onclick = () => { if (rd.i > 0) { rd.i--; loadChapter(); } };
$('rNext').onclick = () => { if (rd.i < rd.chs.length - 1) { rd.i++; loadChapter(); } };
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') { if ($('reader').classList.contains('on')) $('rClose').click(); else closeDetail(); }
});

