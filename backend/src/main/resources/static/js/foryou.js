/* ---------- for you ---------- */
var recType = '';
async function loadUsers() {
  const users = await api('/api/users');
  $('users').innerHTML = users.map(u => `<span class="chip ${u.id === state.user ? 'on' : ''}" data-id="${u.id}" data-name="${esc(u.displayName || u.username)}">${esc(u.displayName || u.username)}</span>`).join('');
  $('users').querySelectorAll('.chip').forEach(c => c.onclick = () => {
    state.user = +c.dataset.id; state.userName = c.dataset.name.split(' (')[0];
    localStorage.setItem('aniga.user', state.user); localStorage.setItem('aniga.userName', state.userName);
    $('users').querySelectorAll('.chip').forEach(x => x.classList.toggle('on', x === c));
    refreshRatings(); $('recs').innerHTML = ''; recommend(); loadShelf();
  });
  const me = users.find(u => u.id === state.user);
  if (me) state.userName = (me.displayName || me.username).split(' (')[0];
}
$('typeFilter').innerHTML = ['', 'ANIME', 'MANGA', 'MANHWA', 'MANHUA'].map(t => `<span class="chip ${t === '' ? 'on' : ''}" data-t="${t}">${t || 'All types'}</span>`).join('');
$('typeFilter').querySelectorAll('.chip').forEach(c => c.onclick = () => {
  recType = c.dataset.t; $('typeFilter').querySelectorAll('.chip').forEach(x => x.classList.toggle('on', x === c)); recommend();
});
$('newUserBtn').onclick = async () => {
  try { const u = await api('/api/users', { method: 'POST', body: JSON.stringify({ username: $('newUser').value }) });
    state.user = u.id; state.userName = u.displayName || u.username; await loadUsers(); recommend(); }
  catch (e) { alert(e.message); }
};
async function refreshRatings() {
  const r = await api(`/api/users/${state.user}/ratings`);
  state.ratings = new Map(r.map(x => [x.mediaId, x.score]));
}
/* ---------- for you: the list + feedback (♥ / ✕ / clicks) + the editable taste the model learned ---------- */
var tasteKind = 'genre', lastTaste = null, recsTimer = 0;
var pct = (x) => Math.round(x * 100) + '%';
var featLabel = (f) => ((lastTaste && lastTaste.features.find(x => x.feature === f)) || {}).label || f.replace(/^(g|t|type):/, '');
var fmtDelta = (deltas, n = 3) => Object.entries(deltas || {}).sort((a, b) => Math.abs(b[1]) - Math.abs(a[1])).slice(0, n)
  .map(([f, d]) => `<span class="${d > 0 ? 'up' : 'dn'}">${esc(featLabel(f))} ${d > 0 ? '+' : '−'}${Math.abs(d).toFixed(2)}</span>`).join(' · ');
var ago = (s) => {
  const t = Date.parse(String(s).replace(' ', 'T').replace(/([+-]\d\d)$/, '$1:00'));
  if (!t) return '';
  const m = (Date.now() - t) / 6e4;
  return m < 1 ? 'now' : m < 60 ? Math.round(m) + 'm' : m < 1440 ? Math.round(m / 60) + 'h' : Math.round(m / 1440) + 'd';
};
async function recommend() { loadTaste(); await loadRecs(); }
var refreshRecsSoon = () => { clearTimeout(recsTimer); recsTimer = setTimeout(loadRecs, 500); };

/** POST a reaction; with explain=true returns the learned change ("Horror −0.25 · gore −0.12") for a toast. */
async function feedback(id, kind, explain = false) {
  try {
    const r = await api(`/api/users/${state.user}/feedback`, { method: 'POST', body: JSON.stringify({ mediaId: id, kind }) });
    if (explain && r.learned) {
      await loadTaste();
      const c = lastTaste && lastTaste.changes[0];
      return c && c.mediaId === id ? fmtDelta(c.deltas) : '';
    }
  } catch (e) { if (explain) toast(esc(e.message)); }
  return '';
}

function recRow(r, i, lo, hi) {
  const m = r.media, c = typeColor(m.mediaType), explore = r.slot === 'EXPLORE';
  const liked = lastTaste && lastTaste.feedback.some(f => f.media.id === m.id && f.kind === 'LIKE');
  const parts = Object.entries(r.parts || {}).filter(([, v]) => Math.abs(v) >= .05).sort((a, b) => Math.abs(b[1]) - Math.abs(a[1])).slice(0, 3)
    .map(([k, v]) => `${k} ${v >= 0 ? '+' : ''}${v.toFixed(2)}`).join(' · ');
  return `<div class="rec rise ${explore ? 'explore' : ''}" style="animation-delay:${i * 40}ms" data-id="${m.id}">
    <span class="rank" style="${i < 3 ? `color:${c}` : ''}">${String(i + 1).padStart(2, '0')}</span>
    <div class="img">${coverArt(m)}</div>
    <div><div class="kicker" style="color:${c}">${m.mediaType}${m.startYear ? ' · ' + m.startYear : ''}<span class="prog2">${metaOf(m)}</span>${explore ? '<span class="xp" title="A wildcard slot: we are testing a guess about your taste">exploring</span>' : ''}</div>
      <div class="t">${esc(m.title)}</div>
      <div class="why">${(r.reasons || []).map(x => `<span class="${x.startsWith('penalised') ? 'err' : ''}">${esc(x)}</span>`).join(' · ')}</div></div>
    <div class="score"><div class="meter"><i style="width:${hi > lo ? (r.score - lo) / (hi - lo) * 100 : 100}%;background:${c}"></i></div>
      <div class="mono muted" style="font-size:11px;margin-top:6px">score ${r.score.toFixed(2)}${parts ? ' · ' + parts : ''}</div></div>
    <div class="fb"><button class="fbb like ${liked ? 'on' : ''}" data-k="LIKE" title="More like this">♥</button><button class="fbb ni" data-k="NOT_INTERESTED" title="Not interested">✕</button></div></div>`;
}
async function loadRecs() {
  if (!$('recs').children.length) $('recs').innerHTML = planeLoader('Ranking your list…');
  try {
    const recs = await api(`/api/users/${state.user}/recommendations?limit=15&type=${recType}&track=true`);
    remember(recs.map(r => r.media));
    const lo = Math.min(...recs.map(r => r.score)) - .1, hi = Math.max(...recs.map(r => r.score));
    $('recs').innerHTML = recs.length ? recs.map((r, i) => recRow(r, i, lo, hi)).join('') : ballStatus('Nothing left to recommend with these settings.');
  } catch (e) { $('recs').innerHTML = ballStatus(e.message); }
}
$('recs').onclick = async (e) => {
  const row = e.target.closest('.rec'); if (!row) return;
  const id = +row.dataset.id, btn = e.target.closest('.fbb'), m = state.seen.get(id) || { title: 'this' };
  if (!btn) { feedback(id, 'CLICK'); openDetail(id); return; }
  if (btn.dataset.k === 'LIKE') {
    const on = btn.classList.toggle('on');
    if (on) burstAt(btn, 18);
    const d = await feedback(id, on ? 'LIKE' : 'CLEAR', on);
    if (on) toast(`<span class="kicker">Learned</span> ♥ <b>${esc(m.title)}</b>${d ? ' · ' + d : ''}`);
    else loadTaste();
    return;
  }
  row.classList.add('gone');
  const d = await feedback(id, 'NOT_INTERESTED', true);
  toast(`<span class="kicker">Won't show again</span> <b>${esc(m.title)}</b>${d ? ' · ' + d : ''}`,
    { label: 'Undo', run: async () => { await feedback(id, 'CLEAR'); loadTaste(); loadRecs(); } });
  setTimeout(() => {
    row.remove();
    $('recs').querySelectorAll('.rank').forEach((el, i) => el.textContent = String(i + 1).padStart(2, '0'));
    topUp();
  }, 460);
};
/** Refill the list after a ✕ without re-ranking what the user is looking at. */
async function topUp() {
  try {
    const shown = new Set([...$('recs').querySelectorAll('.rec')].map(el => +el.dataset.id));
    const recs = await api(`/api/users/${state.user}/recommendations?limit=${shown.size + 3}&type=${recType}`);
    const extra = recs.find(r => !shown.has(r.media.id));
    if (!extra) return;
    remember([extra.media]);
    const scores = recs.map(r => r.score);
    $('recs').insertAdjacentHTML('beforeend', recRow(extra, shown.size, Math.min(...scores) - .1, Math.max(...scores)));
  } catch {}
}

var KINDS = [['genre', 'Genres'], ['tag', 'Tags'], ['type', 'Types']];
async function loadTaste(flash) {
  try {
    const t = await api(`/api/users/${state.user}/taste/v2`);
    lastTaste = t;
    $('tasteMeta').innerHTML = `<b data-count="${t.signals}">0</b> titles · <b data-count="${t.rewards}">0</b> feedback events<br>${t.exploreSlots} exploration slot${t.exploreSlots > 1 ? 's' : ''} per list`;
    countUp($('tasteMeta'));
    renderTasteBars(flash);
    renderChanges(t.changes);
    $('fbList').innerHTML = t.feedback.length ? t.feedback.map(f => `<span class="chip fbchip ${f.kind === 'LIKE' ? 'like' : 'dislike'}" data-id="${f.media.id}" title="click to undo">${f.kind === 'LIKE' ? '♥' : '✕'} ${esc(f.media.title)}</span>`).join('')
      : '<span class="muted" style="font-size:13px">No reactions yet — use ♥ and ✕ on the list below.</span>';
  } catch (e) { $('bars').innerHTML = ballStatus(e.message); }
}
function renderTasteBars(flash) {
  const t = lastTaste; if (!t) return;
  $('kindTabs').innerHTML = KINDS.map(([k, l]) => `<span class="chip ${k === tasteKind ? 'on' : ''}" data-k="${k}">${l}<span class="n">${t.features.filter(f => f.kind === k).length}</span></span>`).join('');
  const rows = t.features.filter(f => f.kind === tasteKind)
    .sort((a, b) => (b.pref !== 0) - (a.pref !== 0) || Math.abs(b.effective) - Math.abs(a.effective)).slice(0, 12);
  if (!rows.length) { $('bars').innerHTML = ballStatus(t.signals ? 'Nothing learned here yet.' : 'Rate or shelve a few titles — until then you get popular picks.'); return; }
  const max = Math.max(.3, ...rows.map(f => Math.max(Math.abs(f.history), Math.abs(f.online))));
  const seg = (v, on) => { const len = Math.min(50, Math.abs(v) / max * 50), pos = v >= 0;
    return `<div class="ttrack ${on ? 'on' : ''}"><i style="left:${pos ? 50 : 50 - len}%;width:${len}%;background-color:${pos ? 'var(--like)' : 'var(--dislike)'}"></i></div>`; };
  $('bars').innerHTML = rows.map(f => `<div class="trow ${f.pref < 0 ? 'muted' : ''} ${f.feature === flash ? 'flash' : ''}" data-f="${esc(f.feature)}">
    <span class="lab" title="${esc(f.feature)}">${esc(f.label)}<small>${f.evidence.toFixed(1)} evidence${f.pref ? (f.pref > 0 ? ' · boosted' : ' · muted') : ''}</small></span>
    <div title="ratings ${f.history.toFixed(2)} · learned ${f.online.toFixed(2)}">${seg(f.history, false)}${seg(f.online, true)}</div>
    <span class="mono" style="text-align:right;font-size:12px;color:${f.effective >= 0 ? 'var(--like)' : 'var(--dislike)'}">${f.effective >= 0 ? '+' : ''}${f.effective.toFixed(2)}</span>
    <span class="tbtns"><button class="tbtn up ${f.pref > 0 ? 'on' : ''}" data-w="1" title="Show me more">▲</button><button class="tbtn down ${f.pref < 0 ? 'on' : ''}" data-w="-1" title="Never show">▼</button></span></div>`).join('');
}
function renderChanges(ch) {
  const prevTop = $('changes').dataset.top;
  $('changes').innerHTML = ch.length ? ch.slice(0, 7).map((c, i) => {
    const surprise = c.expected == null ? '' : `expected ${pct(c.expected)} → got ${pct(c.reward)}`;
    const d = fmtDelta(c.deltas);
    return `<div class="chg ${i === 0 && prevTop && prevTop !== c.at ? 'new' : ''}"><span class="dot" style="background:${mix('#E5532D', '#4DB38A', Math.max(0, Math.min(1, c.reward)))}"></span>
      <div><b>${esc(c.title || 'a title')}</b> <span class="kicker">${esc(c.cause)}</span>
        <div class="d">${surprise}${surprise ? ' · ' : ''}${d || 'matched what we expected — nothing to learn'}</div></div>
      <span class="kicker">${ago(c.at)}</span></div>`;
  }).join('') : '<p class="muted" style="font-size:13px">Every rating, drop, ♥ and ✕ shows up here with what it changed.</p>';
  $('changes').dataset.top = (ch[0] && ch[0].at) || '';
}
$('kindTabs').onclick = (e) => { const c = e.target.closest('.chip'); if (!c) return; tasteKind = c.dataset.k; renderTasteBars(); };
$('bars').onclick = async (e) => {
  const b = e.target.closest('.tbtn'); if (!b) return;
  const f = b.closest('.trow').dataset.f, w = b.classList.contains('on') ? 0 : +b.dataset.w, name = esc(featLabel(f));
  try {
    await api(`/api/users/${state.user}/prefs`, { method: 'PUT', body: JSON.stringify({ feature: f, weight: w }) });
    if (w > 0) burstAt(b, 10);
    toast(w === 0 ? `Cleared your setting for <b>${name}</b>` : w > 0 ? `More <b>${name}</b> from now on` : `<b>${name}</b> muted — titles with it drop out of your list`);
    await loadTaste(f); refreshRecsSoon();
  } catch (err) { toast(esc(err.message)); }
};
$('fbList').onclick = async (e) => {
  const c = e.target.closest('.fbchip'); if (!c) return;
  await feedback(+c.dataset.id, 'CLEAR'); toast('Reaction removed'); loadTaste(); refreshRecsSoon();
};
$('resetOnline').onclick = async () => {
  if (!confirm('Forget everything learned from feedback? Ratings, shelf and your ▲/▼ settings stay.')) return;
  await api(`/api/users/${state.user}/taste/online`, { method: 'DELETE' });
  toast('Learned weights reset'); loadTaste(); refreshRecsSoon();
};

