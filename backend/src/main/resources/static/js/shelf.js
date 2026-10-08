/* ---------- shelf ---------- */
function heroPage(top, first) {
  // Page coordinates in % — one tall panel with a slanted gutter, two stacked panels on the right.
  const p = 3.5, g = 2.8, gx = (y) => 60 - 8 * (y - p) / (100 - 2 * p);
  const polys = [
    [[p, p], [60, p], [52, 100 - p], [p, 100 - p]],
    [[gx(p) + g, p], [100 - p, p], [100 - p, 40 - g / 2], [gx(47 - g / 2) + g, 47 - g / 2]],
    [[gx(47 + g / 2) + g, 47 + g / 2], [100 - p, 40 + g / 2], [100 - p, 100 - p], [gx(100 - p) + g, 100 - p]],
  ];
  const html = polys.map((poly, i) => {
    const xs = poly.map(q => q[0]), ys = poly.map(q => q[1]);
    const [x0, x1, y0, y1] = [Math.min(...xs), Math.max(...xs), Math.min(...ys), Math.max(...ys)];
    const clip = poly.map(q => `${q[0]}% ${q[1]}%`).join(',');
    const m = top[i];
    return `<div class="panel" data-id="${m.id}" style="clip-path:polygon(${clip});animation-delay:${250 + i * 120}ms">
      <img src="${esc(m.coverUrl)}" alt="" style="left:${x0}%;top:${y0}%;width:${x1 - x0}%;height:${y1 - y0}%">
      <svg viewBox="0 0 100 100" preserveAspectRatio="none"><polygon points="${poly.map(q => q.join(',')).join(' ')}" fill="none" stroke="#0E0C0A" stroke-width="3" vector-effect="non-scaling-stroke"/></svg></div>`;
  }).join('');
  $('heroPage').innerHTML = html + `<div class="caption"><div class="kicker">Picked for ${esc(state.userName)}</div>
    <div class="display">${esc(top[0].title)}</div><p>${esc((first.reasons || [])[0] || '')}</p></div>` + burstSvg('No.1');
  $('heroPage').querySelectorAll('.panel').forEach(el => el.onclick = () => { feedback(+el.dataset.id, 'CLICK'); openDetail(+el.dataset.id); });
}

function rail(idx, title, sub, items, color, metaFn) {
  return `<div class="rail"><div class="rh"><span class="bar" style="background:${color}"></span><span class="kicker">${idx}</span>
    <h3 class="display">${esc(title)}</h3><span class="kicker right">${items.length} titles</span></div>
    <div class="sub">${esc(sub)}</div><div class="row">${items.map((m, i) => coverTile(m, i, metaFn && metaFn(m))).join('')}</div></div>`;
}

async function loadShelf() {
  $('rails').innerHTML = planeLoader('Folding the shelf…');
  try {
    const [recs, lib, ...byType] = await Promise.all([api(`/api/users/${state.user}/recommendations?limit=18&track=true`), loadLib(),
      ...['MANHWA', 'MANGA', 'MANHUA', 'ANIME'].map(t => api(`/api/media?type=${t}&sort=popularity&limit=40`))]);
    const cont = lib.filter(e => e.status === 'CURRENT');
    const all = remember(byType.flat());
    remember(recs.map(r => r.media)); remember(lib.map(e => e.media));
    state.media = all;
    const withArt = recs.filter(r => r.media.coverUrl);
    if (withArt.length >= 3) heroPage(withArt.slice(0, 3).map(r => r.media), recs[0]);
    $('heroKicker').textContent = `Tonight's pull list — ${state.userName}`;
    const types = [['MANHWA', 'Manhwa', 'Korean webtoons — read top to bottom'], ['MANGA', 'Manga', 'Japanese, right to left in print'],
      ['MANHUA', 'Manhua', 'Chinese cultivation, wuxia & more'], ['ANIME', 'Anime', 'Metadata only — episodes, studios, synopses']];
    let html = '';
    if (cont.length) html += rail('続', 'Continue', 'Pick up where you left off', cont.map(e => e.media), '#4DB38A');
    const because = (recs.slice(3).find(r => r.becauseTitle) || {}).becauseTitle;
    html += rail('推', 'For you', because ? `Because you loved ${because}` : `Ranked from ${state.userName}'s shelf and ratings`, recs.slice(3).map(r => r.media), '#E5532D');
    types.forEach(([t, name, sub], i) => {
      const items = all.filter(m => m.mediaType === t).sort((a, b) => b.popularity - a.popularity);
      if (items.length) html += rail('0' + (i + 1), name, sub, items, typeColor(t));
    });
    $('rails').innerHTML = html;
    reveal($('rails'));
    const recIds = new Set(recs.map(r => r.media.id));
    $('rails').onclick = (e) => {
      const el = e.target.closest('.cover'); if (!el) return;
      if (recIds.has(+el.dataset.id)) feedback(+el.dataset.id, 'CLICK');
      openDetail(+el.dataset.id);
    };
  } catch (e) { $('rails').innerHTML = ballStatus("Can't reach the backend — is Docker running?"); }
}

