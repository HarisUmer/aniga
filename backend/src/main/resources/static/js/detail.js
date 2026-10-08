/* ---------- detail ---------- */
function tame(hex) {
  // Clamp lightness so arbitrary AniList accents stay readable on ink (mirrors Ui.tame on Android).
  let [r, g, b] = hex.match(/\w\w/g).map(x => parseInt(x, 16) / 255);
  const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2;
  const target = Math.max(.52, Math.min(.68, l));
  return l < target ? mix(hex, '#ffffff', (target - l) / (1 - l)) : l > target ? mix(hex, '#000000', (l - target) / l) : hex;
}
var readLang = () => localStorage.getItem('aniga.lang') || 'en';
var GRID = (html) => `<div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:22px 16px;margin-top:16px">${html}</div>`;

/** Opens a catalog title (number id) or a series straight from a reading source ({source,id,...}). */
async function openDetail(id, series) {
  let m = series ? null : (state.media.find(x => x.id === id) || null);
  let detail = null;
  if (!series) {
    try { detail = await api(`/api/media/${id}`); m = detail.media; } catch (e) { if (!m) return; }
  }
  const s = series;
  const t = m ? m.mediaType : s.contentType, tc = typeColor(t), anime = t === 'ANIME';
  const accent = tame(/^#[0-9a-f]{6}$/i.test((m && m.accentColor) || '') ? m.accentColor : tc);
  const stats = m ? [m.startYear, m.format, (anime ? m.episodes && m.episodes + ' EPISODES' : m.chapters && m.chapters + ' CHAPTERS'), m.averageScore && 'ANILIST ' + m.averageScore + '%'].filter(Boolean).join(' · ')
    : [s.year, (s.status || '').toUpperCase(), s.source.toUpperCase(), s.author].filter(Boolean).join(' · ');
  const facts = !detail ? [] : [m.nativeTitle, detail.romajiTitle !== m.title && detail.romajiTitle, m.season && m.startYear && m.season + ' ' + m.startYear,
    m.status && m.status.replace('_', ' '), detail.episodeMinutes && detail.episodeMinutes + ' min / ep', detail.volumes && detail.volumes + ' volumes',
    (m.makers || []).length && (anime ? 'Studio ' : 'By ') + m.makers.join(', ')].filter(Boolean);
  const gallery = (detail && detail.gallery) || [], cast = (detail && detail.characters) || [];
  const title = m ? m.title : s.title;
  if (m) remember([m]);
  const paint = () => {
  $('dChapters')?._vl?.destroy();
  $('sheet').innerHTML = `<button class="close" id="dClose">✕</button>
    <div class="dhead" style="background:linear-gradient(${mix(accent, '#15120F', .35)}, ${mix(accent, '#15120F', .82)})">
      ${m && m.bannerUrl ? `<img class="banner" src="${esc(m.bannerUrl)}" alt="">` : ''}
      ${speedLines(mix(accent, '#ffffff', .55) + '4D')}<div class="seigaiha" style="opacity:.22"></div><div class="fade"></div>
      <div class="big">${coverArt({ title, coverUrl: m ? m.coverUrl : s.coverUrl, mediaType: t })}</div></div>
    <div class="dbody">
      <div class="kicker" style="color:${tc}">${esc(t)}</div><h2 class="display">${esc(title)}</h2>
      <div class="mono muted" style="font-size:12px">${esc(stats)}</div>
      <div style="margin-top:10px">${((m ? m.genres : s.tags) || []).map(g => `<span class="chip" style="cursor:default;color:${mix(accent, '#ffffff', .45)};border-color:${accent}8C;background:${accent}1A">${esc(g)}</span>`).join('')}</div>
      <div class="kicker" style="margin-top:18px">Story</div><p class="muted" style="margin-top:4px">${esc((m ? m.synopsis : s.description) || 'No synopsis yet.')}</p>
      ${facts.length ? `<div class="facts">${facts.map(esc).join(' · ')}</div>` : ''}
      <div id="dShelf"></div>
      ${gallery.length ? `<div class="kicker" style="margin-top:22px">Stills · banner · trailer</div><div class="strip gallery">${gallery.map(g =>
        `<figure data-href="${esc(g.video || g.url)}"><img loading="lazy" src="${esc(g.url)}" alt=""><figcaption>${g.kind === 'trailer' ? '▶ ' : ''}${esc(g.caption || g.kind)}</figcaption></figure>`).join('')}</div>` : ''}
      ${cast.length ? `<div class="kicker" style="margin-top:18px">Cast</div><div class="strip cast" style="--c:${tc}">${cast.map(c =>
        `<div><img loading="lazy" class="${c.role === 'MAIN' ? 'main' : ''}" src="${esc(c.image)}" alt=""><div class="nm">${esc(c.name)}</div><div class="nt">${esc(c.native || '')}</div></div>`).join('')}</div>` : ''}
      <div style="display:flex;align-items:baseline;margin-top:24px"><h3 class="display" style="font-size:28px;margin:0">${anime ? 'Episodes' : 'Chapters'}</h3><span class="kicker" id="dCount" style="margin-left:auto"></span></div>
      <div id="dSrc" style="margin-top:8px"></div>
      <div class="chapters" style="--rule:${tc}" id="dChapters"></div>
      <div id="dSimilar"></div></div>`;
  $('detail').classList.add('on');
  $('sheet').scrollTop = 0;
  };
  // Shared-element transition: the cover you pressed flies into the sheet's big cover.
  const src = lastPress.el && performance.now() - lastPress.at < 1500 && lastPress.el.isConnected ? lastPress.el : null;
  lastPress.el = null;
  if (src && canVT()) {
    src.style.viewTransitionName = 'cover-morph';
    $('detail').classList.add('morph');
    const vt = document.startViewTransition(() => {
      src.style.viewTransitionName = '';
      paint();
      const big = $('sheet').querySelector('.dhead .big'); if (big) big.style.viewTransitionName = 'cover-morph';
    });
    vt.finished.finally(() => {
      const big = $('sheet').querySelector('.dhead .big'); if (big) big.style.viewTransitionName = '';
      $('detail').classList.remove('morph');
    });
    await vt.updateCallbackDone;
  } else paint();
  $('dClose').onclick = closeDetail;
  $('sheet').querySelectorAll('figure[data-href]').forEach(f => f.onclick = () => window.open(f.dataset.href, '_blank', 'noopener'));
  const ctx = { m, s, t, tc, anime, title, chapters: [], source: s && s.source, lang: s && s.language, progress: 0, romaji: detail && detail.romajiTitle };
  if (m) { renderShelf(ctx); loadSimilar(m.id); }
  if (anime) renderEpisodes(ctx, gallery);
  else if (s) loadChapters(ctx, s.source, s.id, s.language);
  else findOnSources(ctx);
}

/* shelf panel: status chips, progress stepper on a paper-plane route, hanko 1-10 */
async function renderShelf(ctx) {
  const { m, anime, tc } = ctx;
  let st = { status: 'NONE', progress: 0, score: 0 };
  try { st = await api(`/api/users/${state.user}/library/${m.id}`); } catch {}
  const total = anime ? m.episodes : m.chapters, unit = anime ? 'EP' : 'CH';
  const draw = (anim) => {
    ctx.progress = st.progress;
    const pct = total ? Math.min(1, st.progress / total) : (st.progress ? .5 : 0), x = 12 + 276 * pct;
    $('dShelf').innerHTML = `<div class="shelf"><h3 class="display" style="font-size:20px">Your shelf <span class="kicker" style="font-size:10px;margin-left:8px">${esc(state.userName)}</span></h3>
      <div style="margin-top:8px">${Object.keys(STATUS).map(k => `<span class="chip st ${st.status === k ? 'on' : ''}" style="--c:${STATUS[k][0]}" data-s="${k}">${statusLabel(k, anime)}</span>`).join('')}</div>
      <div class="stepper"><button data-d="-1">−</button><svg class="route" viewBox="0 0 300 22" preserveAspectRatio="none" height="22">
        <line x1="${x}" y1="11" x2="288" y2="11" stroke="#3B332C" stroke-width="1.5" stroke-dasharray="4 5"/><line x1="12" y1="11" x2="${x}" y2="11" stroke="${tc}" stroke-width="2.4" stroke-linecap="round"/>
        <path transform="translate(${x},11)" d="M8,0 L-8,-5.5 L-3.5,0 L-8,4.5 Z" fill="#F1E9DA" stroke="#0E0C0A" stroke-width="1"/></svg>
        <button data-d="1">+</button><span class="mono" style="min-width:96px;text-align:right;font-size:12px">${unit} ${st.progress} / ${total ?? '?'}</span></div>
      <div style="display:flex;align-items:baseline;margin-top:12px"><span class="kicker">Your score</span><span class="kicker" id="clr" style="margin-left:auto;cursor:pointer;color:${st.score ? 'var(--hanko)' : ''}">${st.score ? st.score + ' / 10 · clear' : 'not rated'}</span></div>
      <div class="hanko-rate">${[...Array(10)].map((_, i) => `<button data-v="${i + 1}" class="${i < st.score ? 'on' : ''} ${anim === i + 1 ? 'stamp' : ''}" style="${i < st.score ? 'background:' + sealColor(i + 1) : ''}">${i + 1}</button>`).join('')}</div></div>`;
    $('dShelf').querySelectorAll('.chip.st').forEach(c => c.onclick = () => save({ status: c.dataset.s }));
    $('dShelf').querySelectorAll('.stepper button').forEach(b => b.onclick = () => save({ progress: Math.max(0, st.progress + +b.dataset.d) }));
    $('dShelf').querySelectorAll('.hanko-rate button').forEach(b => b.onclick = () => save({ score: +b.dataset.v }, +b.dataset.v));
    $('clr').onclick = () => st.score && save({ score: 0 });
    if (ctx.vl) ctx.vl.refresh();
    else document.querySelectorAll('#dChapters .ch').forEach(el => el.classList.toggle('read', +el.dataset.n > 0 && +el.dataset.n <= st.progress));
    const jump = $('dJump'); if (jump) jump.dataset.p = st.progress;
  };
  const save = async (body, anim) => {
    const before = st.status;
    try {
      st = await api(`/api/users/${state.user}/library/${m.id}`, { method: 'PUT', body: JSON.stringify(body) });
      draw(anim); refreshRatings();
      if (st.status && st.status !== 'NONE') state.library.set(m.id, { status: st.status, progress: st.progress, score: st.score });
      else state.library.delete(m.id);
      patchTiles(m.id);
      if (anim >= 8) burstAt($('dShelf').querySelector(`.hanko-rate button[data-v="${anim}"]`), 16);
      if (body.status === 'COMPLETED' && before !== 'COMPLETED') burstAt($('dShelf').querySelector('.chip.st.on') || $('dShelf'), 22);
      // A score, a drop or a finish is a reward for the online learner: show what it changed.
      if (body.score || (body.status && body.status !== before && ['COMPLETED', 'DROPPED'].includes(body.status))) {
        const t = await api(`/api/users/${state.user}/taste/v2`); lastTaste = t;
        const c = t.changes[0];
        if (c && c.mediaId === m.id) {
          const d = fmtDelta(c.deltas);
          toast(`<span class="kicker">${esc(c.cause)}</span> ${c.expected != null ? `we expected ${pct(c.expected)}` : ''}${d ? ' · ' + d : ' · as expected'}`);
        }
      }
    } catch (e) { alert(e.message); }
  };
  ctx.setProgress = (p) => save({ progress: p });
  draw();
}

function renderEpisodes(ctx, gallery) {
  const total = ctx.m && ctx.m.episodes, out = $('dChapters');
  if (!total) { out.innerHTML = ballStatus('Anime is metadata only in Aniga — no streaming.', ctx.tc); return; }
  const stills = gallery.filter(g => g.kind === 'episode').map(g => g.caption || '');
  const name = (n) => { const s = stills.find(x => x.toLowerCase().startsWith(`episode ${n} `) || x.toLowerCase().startsWith(`episode ${n}-`)); return s ? s.split(' - ').slice(1).join(' - ') || s : ''; };
  const seen = () => ctx.progress || 0;
  $('dCount').innerHTML = `<b data-count="${total}">0</b> EP · metadata only`;
  countUp($('dCount'));
  jumpBar(ctx, total, 'EP');
  const row = (i) => `<div class="ch ${i + 1 <= seen() ? 'read' : ''} ${i === seen() ? 'next' : ''}" data-i="${i}" data-n="${i + 1}"><span class="n" style="color:${ctx.tc}"><small>EP.</small>${i + 1}</span>
    <div><div style="font-weight:500">${esc(name(i + 1) || 'Episode ' + (i + 1))}</div><div class="mono muted" style="font-size:11px">${i + 1 <= seen() ? 'watched' : i === seen() ? 'up next · click = watched up to here' : 'click = watched up to here'}</div></div><span class="muted">${i + 1 <= seen() ? '✓' : ''}</span></div>`;
  ctx.vl = virtualList(out, out, total, 64, row, (i) => ctx.setProgress && ctx.setProgress(i + 1));
}
/** "Jump to" for long lists + a shortcut to the first unread/unwatched entry. */
function jumpBar(ctx, total, unit) {
  $('dJumpBar')?.remove();
  if (total < 40) return;
  $('dChapters').insertAdjacentHTML('beforebegin', `<div class="jump" id="dJumpBar"><span class="kicker">Jump to ${unit}</span>
    <input class="field" id="dJumpN" type="number" min="1" max="${total}" placeholder="1–${total}"><button class="btn ghost" id="dJump" style="padding:6px 12px">Up next</button></div>`);
  const go = (i) => ctx.vl && ctx.vl.scrollTo(Math.max(0, Math.min(total - 1, i)));
  $('dJumpN').onkeydown = (e) => { if (e.key === 'Enter') go(ctx.indexOf ? ctx.indexOf(+e.target.value) : +e.target.value - 1); };
  $('dJump').onclick = () => go(ctx.indexOf ? ctx.indexOf((ctx.progress || 0) + 1) : ctx.progress || 0);
}

async function findOnSources(ctx) {
  const out = $('dChapters');
  out.innerHTML = planeLoader('Looking on every reading source…');
  try {
    const aliases = [ctx.m && ctx.m.nativeTitle, ctx.romaji].filter(Boolean).join('|');
    const type = (ctx.m && ctx.m.mediaType) || ctx.t || '';
    const hits = await api(`/api/read/find?title=${encodeURIComponent(ctx.title)}&lang=${readLang()}${aliases ? '&aliases=' + encodeURIComponent(aliases) : ''}${type ? '&type=' + encodeURIComponent(type) : ''}`);
    if (!hits.length) { out.innerHTML = ballStatus(`No chapters on the reading sources for “${ctx.title}”. Try another language in Discover.`, ctx.tc); return; }
    $('dSrc').innerHTML = hits.slice(0, 8).map((h, i) => {
      const n = h.chapterCount != null ? `<span class="n">${h.chapterCount} CH</span>` : '';
      const lang = h.language ? ' · ' + h.language : '';
      return `<span class="chip ${i === 0 ? 'on' : ''}" data-i="${i}">${esc(h.source)}${esc(lang)} · ${esc(h.title)}${n}</span>`;
    }).join('');
    $('dSrc').querySelectorAll('.chip').forEach(c => c.onclick = () => {
      $('dSrc').querySelectorAll('.chip').forEach(x => x.classList.toggle('on', x === c));
      const h = hits[+c.dataset.i]; loadChapters(ctx, h.source, h.id, h.language);
    });
    loadChapters(ctx, hits[0].source, hits[0].id, hits[0].language);
  } catch (e) { out.innerHTML = ballStatus(e.message, ctx.tc); }
}

async function loadChapters(ctx, source, seriesId, lang) {
  const out = $('dChapters'); ctx.source = source; ctx.lang = lang || readLang();
  out._vl?.destroy(); ctx.vl = null;
  out.innerHTML = planeLoader('Fetching chapters…');
  try {
    const chs = await api(`/api/read/${source}/series/${encodeURIComponent(seriesId)}/chapters?lang=${encodeURIComponent(ctx.lang)}`);
    ctx.chapters = chs;
    const ext = chs.filter(c => c.externalUrl && !c.pages).length;
    const asked = readLang();
    const note = ctx.lang && ctx.lang !== asked ? ` · richest list is ${ctx.lang.toUpperCase()} (${asked.toUpperCase()} had fewer)` : '';
    $('dCount').textContent = `${chs.length} on ${source} · ${ctx.lang || asked}${ext ? ` (${ext} official links)` : ''}${note}`;
    if (!chs.length) { out.innerHTML = ballStatus(`No chapters in ${(ctx.lang || asked).toUpperCase()} here.`, ctx.tc); return; }
    const read = (c) => +c.number > 0 && +c.number <= (ctx.progress || 0);
    const row = (i) => { const c = chs[i];
      return `<div class="ch ${read(c) ? 'read' : ''}" data-i="${i}" data-n="${+c.number || 0}">
      <span class="n" style="color:${ctx.tc}"><small>CH.</small>${esc(c.number || 'Oneshot')}</span>
      <div><div style="font-weight:500">${esc(c.title || 'Chapter ' + (c.number || ''))}</div><div class="mono muted" style="font-size:11px">${c.externalUrl && !c.pages ? 'official site ↗' : c.pages + ' pages'}${c.group ? ' · ' + esc(c.group) : ''}${c.publishedAt ? ' · ' + c.publishedAt.slice(0, 10) : ''}</div></div><span class="muted">${c.externalUrl && !c.pages ? '↗' : '›'}</span></div>`; };
    ctx.indexOf = (n) => { const k = chs.findIndex(c => +c.number >= n); return k < 0 ? chs.length - 1 : k; };
    jumpBar(ctx, chs.length, 'CH');
    ctx.vl = virtualList(out, out, chs.length, 64, row, (i) => {
      const c = chs[i];
      if (c.externalUrl && !c.pages) { window.open(c.externalUrl, '_blank', 'noopener'); return; }
      const readable = chs.filter(x => !(x.externalUrl && !x.pages));
      openReader(ctx.title, ctx.t, readable, readable.indexOf(c), { source, mediaId: ctx.m ? ctx.m.id : null });
    });
  } catch (e) { out.innerHTML = ballStatus(e.message, ctx.tc); }
}

async function loadSimilar(id) {
  try {
    const list = await api(`/api/media/${id}/similar?limit=12`);
    if (!list.length) return;
    $('dSimilar').innerHTML = `<h3 class="display" style="font-size:24px;margin-top:28px">More like this</h3><div class="strip similar">${list.map((m, i) => coverTile(m, i)).join('')}</div>`;
    $('dSimilar').querySelectorAll('.cover').forEach(el => el.onclick = () => openDetail(+el.dataset.id));
  } catch {}
}
function closeDetail() { $('detail').classList.remove('on'); }
$('detail').querySelector('.scrim').onclick = closeDetail;

