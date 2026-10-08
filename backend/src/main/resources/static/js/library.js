/* ---------- library ---------- */
var libFilter = '';
$('libFilters').innerHTML = [['', 'All'], ['CURRENT', 'In progress'], ['COMPLETED', 'Completed'], ['PLANNING', 'Planning'], ['PAUSED', 'Paused'], ['DROPPED', 'Dropped']]
  .map(([k, l]) => `<span class="chip ${k === '' ? 'on' : ''}" data-k="${k}">${l}</span>`).join('');
$('libFilters').querySelectorAll('.chip').forEach(c => c.onclick = () => {
  libFilter = c.dataset.k; $('libFilters').querySelectorAll('.chip').forEach(x => x.classList.toggle('on', x === c)); loadLibrary();
});
async function loadLibrary() {
  $('libOut').innerHTML = planeLoader('Folding the shelf…');
  try {
    const all = await loadLib();
    remember(all.map(e => e.media));
    const anime = all.filter(e => e.media.mediaType === 'ANIME');
    $('libStats').innerHTML = `<b data-count="${all.length}">0</b> titles · <b data-count="${anime.length}">0</b> anime · <b data-count="${anime.reduce((a, e) => a + e.progress, 0)}">0</b> episodes watched · <b data-count="${all.filter(e => e.media.mediaType !== 'ANIME').reduce((a, e) => a + e.progress, 0)}">0</b> chapters read`;
    countUp($('libStats'));
    const list = all.filter(e => !libFilter || e.status === libFilter);
    if (!list.length) { $('libOut').innerHTML = ballStatus(all.length ? 'Nothing here with this status.' : 'Nothing on your shelf yet — open any title and pick a status.'); return; }
    $('libOut').innerHTML = list.map((e, i) => {
      const m = e.media, total = m.mediaType === 'ANIME' ? m.episodes : m.chapters, c = STATUS[e.status][0];
      return `<div class="lib rise" style="animation-delay:${Math.min(i, 14) * 30}ms" data-id="${m.id}"><span class="spine" style="background:${c}"></span><div class="img">${coverArt(m)}</div>
        <div><div class="kicker" style="color:${c}">${statusLabel(e.status, m.mediaType === 'ANIME')} · ${m.mediaType}</div><div style="font-weight:600">${esc(m.title)}</div>
          <div class="meter" style="margin-top:8px"><i style="width:${total ? Math.min(100, e.progress / total * 100) : (e.progress ? 50 : 0)}%;background:${typeColor(m.mediaType)}"></i></div>
          <div class="mono muted" style="font-size:11px;margin-top:4px">${m.mediaType === 'ANIME' ? 'EP' : 'CH'} ${e.progress}${total ? ' / ' + total : ''} · updated ${String(e.updatedAt || '').slice(0, 10)}</div></div>
        ${e.score ? `<span class="seal2" style="background:${sealColor(e.score)}">${e.score}</span>` : '<span></span>'}<span class="kicker">open ›</span></div>`;
    }).join('');
    $('libOut').querySelectorAll('.lib').forEach(el => el.onclick = () => openDetail(+el.dataset.id));
  } catch (e) { $('libOut').innerHTML = ballStatus(e.message); }
}

