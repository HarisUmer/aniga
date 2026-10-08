/* ---------- rate ---------- */
var catType = '';
$('catalogTypes').innerHTML = ['', 'ANIME', 'MANGA', 'MANHWA', 'MANHUA'].map(t => `<span class="chip ${t === '' ? 'on' : ''}" data-t="${t}">${t || 'All types'}</span>`).join('');
$('catalogTypes').querySelectorAll('.chip').forEach(c => c.onclick = () => {
  catType = c.dataset.t; $('catalogTypes').querySelectorAll('.chip').forEach(x => x.classList.toggle('on', x === c)); searchMedia();
});
function rateRow(mid) {
  const cur = state.ratings.get(mid);
  return `<div class="rate" data-mid="${mid}">${[...Array(10)].map((_, i) => `<button class="${cur && i < cur ? 'on' : ''}" data-s="${i + 1}" title="${i + 1}">${i + 1}</button>`).join('')}</div>`;
}
function paintRate(row, score) {
  row.querySelectorAll('button').forEach((b, i) => b.classList.toggle('on', !!score && i < score));
}
function wireRating(root) {
  root.querySelectorAll('.rate').forEach(row => {
    if (row.dataset.wired) return;
    row.dataset.wired = '1';
    row.querySelectorAll('button').forEach(b => b.onclick = async (ev) => {
      ev.stopPropagation();
      if (!row.isConnected) return;
      const mid = +row.dataset.mid, s = +b.dataset.s, cur = state.ratings.get(mid);
      try {
        if (cur === s) await api(`/api/users/${state.user}/ratings/${mid}`, { method: 'DELETE' });
        else await api(`/api/users/${state.user}/ratings/${mid}`, { method: 'PUT', body: JSON.stringify({ score: s }) });
        await refreshRatings();
        if (!row.isConnected) return;
        if (cur !== s && s >= 8) burstAt(b, 12);
        paintRate(row, state.ratings.get(mid) || 0);
      } catch (e) { alert(e.message); }
    });
  });
}
/* Catalog grid: pages of 48 appended by an IntersectionObserver sentinel (infinite scroll over all 900 titles).
 * Cells off-screen are skipped by content-visibility, so a long scroll stays cheap. */
var CAT_PAGE = 48;
var cat = { offset: 0, done: false, busy: false, gen: 0 };
var catObserver = new IntersectionObserver(es => { if (es.some(e => e.isIntersecting)) moreMedia(); }, { rootMargin: '900px 0px' });
async function searchMedia() {
  const grid = $('catalogGrid');
  Object.assign(cat, { offset: 0, done: false, busy: false, gen: cat.gen + 1 });
  grid.innerHTML = '';
  try { await refreshRatings(); } catch {}
  await moreMedia();
}
async function moreMedia() {
  const grid = $('catalogGrid');
  if (cat.done || cat.busy) return;
  cat.busy = true;
  const gen = cat.gen;
  $('catMore')?.remove();
  grid.insertAdjacentHTML('afterend', `<div id="catMore" class="more">${planeLoader('More titles…')}</div>`);
  try {
    const list = await api(`/api/media?q=${encodeURIComponent($('mediaQ').value)}&type=${catType}&limit=${CAT_PAGE}&offset=${cat.offset}`);
    if (gen !== cat.gen) return;
    remember(list);
    grid.insertAdjacentHTML('beforeend', list.map((m, i) => `<div>${coverTile(m, i)}${rateRow(m.id)}</div>`).join(''));
    cat.offset += list.length; cat.done = list.length < CAT_PAGE;
    wireRating(grid);
    if (!cat.offset) grid.innerHTML = ballStatus('Nothing matches.');
  } catch (e) { if (!cat.offset) grid.innerHTML = ballStatus(e.message); cat.done = true; }
  finally {
    if (gen === cat.gen) {
      cat.busy = false;
      const more = $('catMore');
      if (!more) return;
      more.innerHTML = cat.done ? `<span class="kicker">${cat.offset} titles</span>` : '<div class="sentinel"></div>';
      const s = more.querySelector('.sentinel');
      catObserver.disconnect(); if (s) catObserver.observe(s);
    }
  }
}
$('catalogGrid').onclick = (e) => { const el = e.target.closest('.cover'); if (el) openDetail(+el.dataset.id); };
$('mediaBtn').onclick = searchMedia;
$('mediaQ').onkeydown = (e) => { if (e.key === 'Enter') searchMedia(); };

