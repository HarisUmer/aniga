var $ = (id) => document.getElementById(id);
var esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
async function api(path, opts = {}) {
  const res = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...opts });
  if (!res.ok) {
    let detail = res.statusText;
    try { detail = (await res.json()).detail || detail; } catch {}
    throw new Error(`${res.status}: ${detail}`);
  }
  return res.status === 204 ? null : res.json();
}
var TYPE = { ANIME:'#7C9CE0', MANGA:'#E5532D', MANHWA:'#4DB38A', MANHUA:'#EFB23B', WEBCOMIC:'#E88FA6' };
var STATUS = { CURRENT:['#4DB38A','Watching','Reading'], COMPLETED:['#7C9CE0','Completed'], PLANNING:['#B9AD9A','Planning'], PAUSED:['#EFB23B','Paused'], DROPPED:['#E5532D','Dropped'] };
var statusLabel = (s, anime) => !STATUS[s] ? 'Add' : s === 'CURRENT' ? (anime ? 'Watching' : 'Reading') : STATUS[s][1];
var sealColor = (score) => mix('#EFB23B', '#E5532D', (score - 1) / 9);
var typeColor = (t) => TYPE[t] || TYPE.MANGA;
var mix = (hex, other, p) => {
  const a = hex.match(/\w\w/g).map(x => parseInt(x, 16)), b = other.match(/\w\w/g).map(x => parseInt(x, 16));
  return '#' + a.map((v, i) => Math.round(v + (b[i] - v) * p).toString(16).padStart(2, '0')).join('');
};
var state = { user: +(localStorage.getItem('aniga.user') || 1), userName: localStorage.getItem('aniga.userName') || 'Kenji', ratings: new Map(), media: [], library: new Map(), seen: new Map() };
var REDUCE = matchMedia('(prefers-reduced-motion: reduce)');
/** Remember every catalog title we render so detail/patching never needs a refetch. */
var remember = (list) => { for (const m of list) if (m && typeof m.id === 'number') state.seen.set(m.id, m); return list; };

/* ---------- illustrations ---------- */
var svgSeq = 0;
/** Paper plane on a lemniscate (same curve as PaperPlaneLoaderView) with an ink trail and banking. */
function planeLoader(text) {
  const id = 'route' + (++svgSeq), dur = 2.8, pts = [];
  for (let i = 0; i <= 160; i++) {
    const t = 2 * Math.PI * i / 160;
    pts.push(`${(130 + 88 * Math.sin(t)).toFixed(1)},${(60 + 38 * Math.sin(t) * Math.cos(t) * 1.6).toFixed(1)}`);
  }
  const d = 'M' + pts.join(' L');
  const puffs = [...Array(16)].map((_, k) => {
    const life = 1 - (k + 1) / 17;
    return `<circle r="${(1.1 + 3.4 * life).toFixed(1)}" fill="#B9AD9A" opacity="${(0.55 * life * life).toFixed(2)}">
      <animateMotion dur="${dur}s" repeatCount="indefinite" begin="-${(dur - (k + 1) * 0.055).toFixed(2)}s"><mpath href="#${id}"/></animateMotion></circle>`;
  }).join('');
  return `<div class="status"><svg width="260" height="120" viewBox="0 0 260 120">
    <path id="${id}" d="${d}" fill="none" stroke="#E5532D" stroke-opacity=".35" stroke-width="1.6" stroke-linecap="round" stroke-dasharray="420" stroke-dashoffset="420">
      <animate attributeName="stroke-dashoffset" from="420" to="0" dur="1.1s" fill="freeze"/></path>
    <path d="${d}" fill="none" stroke="#85796A" stroke-opacity=".35" stroke-dasharray="3 6"/>
    ${puffs}
    <g><animateMotion dur="${dur}s" repeatCount="indefinite" rotate="auto"><mpath href="#${id}"/></animateMotion>
      <g>
        <animateTransform attributeName="transform" type="scale" values="1,1;1,0.78;1,1;1,0.82;1,1" keyTimes="0;0.25;0.5;0.75;1" dur="${dur}s" repeatCount="indefinite"/>
        <path d="M14,0 L-12,7 L-5,0 Z" fill="#E4D9C5" stroke="#0E0C0A" stroke-width="1.3" stroke-linejoin="round"/>
        <path d="M14,0 L-5,0 L-9,3 Z" fill="#E5532D"/>
        <path d="M14,0 L-12,-9 L-5,0 Z" fill="#F1E9DA" stroke="#0E0C0A" stroke-width="1.3" stroke-linejoin="round"/>
      </g>
    </g></svg><span class="kicker">${esc(text)}</span></div>`;
}

/** Rolling ball over two panel floors with a gutter hop, squash on landing and dust — same maths as RollingBallView.java. */
function ballStatus(text, color = '#E5532D') {
  return `<div class="status"><canvas class="ball" width="640" height="220" style="width:320px;height:110px" data-color="${color}"></canvas><span class="kicker">${esc(text)}</span></div>`;
}
function drawBalls() {
  const t = (performance.now() % 3200) / 3200;
  document.querySelectorAll('canvas.ball').forEach(cv => {
    const g = cv.getContext('2d'), w = cv.width, h = cv.height, r = Math.min(h * .16, w * .07), ground = h * .8;
    const gapA = w * .44, gapB = w * .56, jump = w * .36, landA = w * .64, landB = w * .76, hop = h * .42;
    g.clearRect(0, 0, w, h);
    g.strokeStyle = '#85796A'; g.lineWidth = 4; g.lineCap = 'round';
    g.beginPath(); g.moveTo(w * .04, ground); g.lineTo(gapA, ground); g.lineTo(gapA, ground + 14);
    g.moveTo(gapB, ground + 14); g.lineTo(gapB, ground); g.lineTo(w * .96, ground); g.stroke();
    const x = -r + (w + 2 * r) * t;
    let lift = 0;
    const crouch = x > jump - w * .08 && x <= jump ? (1 - (jump - x) / (w * .08)) : 0;
    if (x > jump && x < landA) lift = hop * Math.sin(Math.PI * (x - jump) / (landA - jump));
    else if (x >= landA && x < landB) lift = hop * .22 * Math.sin(Math.PI * (x - landA) / (landB - landA));
    const imp = (L) => { const d = x - L, win = w * .03; return d < -win * .4 || d > win ? 0 : 1 - Math.abs(d) / win; };
    const sq = Math.max(imp(landA), imp(landB) * .6, crouch * .55);
    const since = (x - landA) / (w * .14);
    if (since >= 0 && since <= 1) {
      g.fillStyle = `rgba(185,173,154,${.65 * (1 - since)})`;
      for (let i = 0; i < 3; i++) {
        const s = r * (.6 + 1.4 * since) * (1 + i * .45), up = r * .5 * since * (1 + i * .3), sz = 4 * (1 - since * .5);
        g.beginPath(); g.arc(landA - s, ground - up, sz, 0, 7); g.arc(landA + s * .7, ground - up * .8, sz, 0, 7); g.fill();
      }
    }
    const hr = lift / hop;
    if (x < gapA - r * .3 || x > gapB + r * .3 || lift > 0) {
      g.fillStyle = `rgba(14,12,10,${.45 * (1 - .7 * hr)})`;
      g.beginPath(); g.ellipse(x, ground, r * (1.1 - .55 * hr), 5, 0, 0, 7); g.fill();
    }
    g.save(); g.translate(x, ground - lift); g.scale(1 + .2 * sq, 1 - .24 * sq); g.translate(0, -r);
    g.beginPath(); g.arc(0, 0, r, 0, 7); g.save(); g.clip();
    g.fillStyle = cv.dataset.color; g.fillRect(-r, -r, 2 * r, 2 * r);
    g.rotate(x / r); g.fillStyle = '#F1E9DA'; g.fillRect(-r * 1.2, -r * .22, r * 2.4, r * .44);
    g.strokeStyle = '#0E0C0A'; g.lineWidth = 3; g.strokeRect(-r * 1.3, -r * .22, r * 2.6, r * .44);
    g.restore(); g.lineWidth = 4; g.strokeStyle = '#0E0C0A'; g.beginPath(); g.arc(0, 0, r, 0, 7); g.stroke();
    g.strokeStyle = 'rgba(241,233,218,.7)'; g.lineWidth = 4; g.beginPath(); g.arc(0, 0, r * .62, 3.5, 4.35); g.stroke();
    g.restore();
  });
  requestAnimationFrame(drawBalls);
}
requestAnimationFrame(drawBalls);

/** 集中線 focus lines, deterministic so the header doesn't reshuffle on every open. */
function speedLines(color) {
  let seed = 42; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  const cx = 300, cy = 220, outer = 520, out = [];
  for (let i = 0; i < 96; i++) {
    const a = 2 * Math.PI * (i + rnd() * .8) / 96, inner = 120 + rnd() * 80, half = .8 + rnd() * rnd() * 5.5;
    const c = Math.cos(a), s = Math.sin(a), px = -s * half, py = c * half;
    out.push(`<polygon points="${cx + c * outer + px},${cy + s * outer + py} ${cx + c * outer - px},${cy + s * outer - py} ${cx + c * inner},${cy + s * inner}" style="animation-delay:${(rnd() * 120) | 0}ms"/>`);
  }
  return `<svg class="lines" viewBox="0 0 600 440" preserveAspectRatio="xMidYMid slice" fill="${color}">${out.join('')}</svg>`;
}

function burstSvg(label) {
  const pts = [];
  for (let i = 0; i < 28; i++) {
    const a = Math.PI * i / 14 - Math.PI / 2, r = i % 2 === 0 ? (i % 4 === 0 ? 46 : 41) : 34;
    pts.push(`${50 + Math.cos(a) * r},${50 + Math.sin(a) * r}`);
  }
  return `<svg class="burst" viewBox="0 0 100 100"><polygon points="${pts.join(' ')}" fill="#E5532D" stroke="#0E0C0A" stroke-width="2.5"/>
    <text x="50" y="58" text-anchor="middle" font-family="Anton" font-size="24" fill="#F1E9DA">${esc(label)}</text></svg>`;
}

function coverArt(m, cls = '') {
  const t = m.mediaType || m.type;
  if (m.coverUrl) return `<img loading="lazy" decoding="async" src="${esc(m.coverUrl)}" alt="" class="${cls}" onload="this.classList.add('ok')" onerror="this.classList.add('ok')">`;
  const c = typeColor(t);
  return `<div class="typo" style="background:${mix(c, '#0E0C0A', .55)};--dot:${c}8C"><div class="bar" style="color:${c}">${esc(t)}</div><div class="dots"></div><div class="display" style="position:relative">${esc(m.title)}</div></div>`;
}
/* the reader's shelf entry for a catalog title (status + progress), loaded once and kept in sync */
var shelfOf = (m) => typeof m.id === 'number' ? state.library.get(m.id) : null;
var totalOf = (m) => (m.mediaType || m.type) === 'ANIME' ? m.episodes : m.chapters;
function progressBar(m) {
  const e = shelfOf(m);
  if (!e || !e.progress) return '';
  const total = totalOf(m), pct = total ? Math.min(100, e.progress / total * 100) : 50;
  return `<div class="prog"><i style="width:${pct}%;background:${(STATUS[e.status] || STATUS.CURRENT)[0]}"></i></div>`;
}
function coverTile(m, i, meta, badge) {
  const t = m.mediaType || m.type || m.contentType, c = typeColor(t);
  return `<div class="cover rise" style="animation-delay:${Math.min(i, 10) * 45}ms" data-id="${m.id}">
    <div class="img">${coverArt({ ...m, mediaType: t })}${progressBar({ ...m, mediaType: t })}<span class="tag" style="background:${c}">${esc(t)}</span>${badge ? `<span class="badge">${esc(badge)}</span>` : ''}</div>
    <div class="t">${esc(m.title)}</div><div class="m">${meta != null ? esc(meta) : metaOf({ ...m, mediaType: t })}</div></div>`;
}
/** "EP 5 / 24 · Watching" when it's on the shelf, otherwise "24 EP · 87%" (or "ongoing" while a count is unknown). */
function metaOf(m) {
  const anime = m.mediaType === 'ANIME', unit = anime ? 'EP' : 'CH', total = totalOf(m), e = shelfOf(m);
  if (e && (e.progress || e.status)) {
    const left = total && e.progress < total ? ` · ${total - e.progress} left` : '';
    return `<b>${unit} ${e.progress}${total ? ' / ' + total : ''}</b> · ${esc(statusLabel(e.status, anime))}${left}`;
  }
  return esc([total ? total + ' ' + unit : (m.status === 'RELEASING' ? 'ongoing' : ''), m.averageScore ? m.averageScore + '%' : ''].filter(Boolean).join(' · '));
}
async function loadLib() {
  try {
    const all = await api(`/api/users/${state.user}/library`);
    state.library = new Map(all.map(e => [e.media.id, { status: e.status, progress: e.progress, score: e.score }]));
    return all;
  } catch { state.library = new Map(); return []; }
}
/** After a shelf change, patch every visible tile of that title instead of re-rendering whole rails. */
function patchTiles(id) {
  document.querySelectorAll(`.cover[data-id="${id}"]`).forEach(el => {
    const m = state.seen.get(id);
    if (!m) return;
    el.querySelector('.m').innerHTML = metaOf(m);
    el.querySelector('.prog')?.remove();
    const bar = progressBar(m);
    if (bar) el.querySelector('.tag').insertAdjacentHTML('beforebegin', bar);
  });
}

/* ---------- nav: brush indicator + kanji ---------- */
function moveInk(btn, animate = true) {
  const ink = $('navInk'), wrap = btn.parentElement.getBoundingClientRect(), r = btn.getBoundingClientRect();
  const left = r.left - wrap.left + 12, right = wrap.right - r.right + 12;
  const oldLeft = parseFloat(ink.style.left || left);
  ink.className = !animate ? '' : left > oldLeft ? 'fwd' : 'back';
  if (!animate) ink.style.transition = 'none';
  ink.style.left = left + 'px'; ink.style.right = right + 'px';
  if (!animate) requestAnimationFrame(() => ink.style.transition = '');
}
/* View Transitions where supported: the outgoing tab slides out the way you came, the new one slides in. */
var canVT = () => !!document.startViewTransition && !REDUCE.matches;
if (document.startViewTransition) document.documentElement.classList.add('vt');
function switchTab(b) {
  document.querySelectorAll('nav button, main section').forEach(x => x.classList.remove('on'));
  b.classList.add('on'); $(b.dataset.tab).classList.add('on');
}
var tabIndex = (b) => [...document.querySelectorAll('nav button')].indexOf(b);
document.querySelectorAll('nav button').forEach(b => b.onclick = () => {
  const cur = document.querySelector('nav button.on');
  if (cur === b) return;
  document.documentElement.style.setProperty('--vt-dir', tabIndex(b) > tabIndex(cur) ? 1 : -1);
  if (canVT()) document.startViewTransition(() => switchTab(b)); else switchTab(b);
  moveInk(b);
  const k = $('kanjiBg'); k.style.opacity = 0; k.style.transform = 'scale(.6) rotate(-14deg)';
  setTimeout(() => { k.textContent = b.dataset.kj; k.style.opacity = ''; k.style.transform = ''; }, 160);
  if (b.dataset.tab === 'catalog' && !$('catalogGrid').children.length) searchMedia();
  if (b.dataset.tab === 'sources' && !$('srcBody').children.length) loadSources();
  if (b.dataset.tab === 'read' && !$('dOut').children.length) initDiscover();
  if (b.dataset.tab === 'library') loadLibrary();
  if (b.dataset.tab === 'foryou' && !$('recs').children.length) recommend();
});
window.addEventListener('load', () => moveInk(document.querySelector('nav button.on'), false));
window.addEventListener('resize', () => moveInk(document.querySelector('nav button.on'), false));

/* ---------- sakura: full-page, two depth layers (same petal shape as SakuraView.java) ----------
 * Far layer behind the content (small, slow, some softly blurred), near layer in front (few, large, blurred).
 * Each petal tint/blur is rasterised once into a sprite; a frame is only drawImage calls with a per-petal
 * transform, so 50 petals cost well under 1 ms. Wind = slow gusts + the pointer's horizontal speed; petals
 * near the pointer get pushed away; scrolling moves far petals less than the page and near ones more (parallax).
 * Count adapts to the viewport and drops if frames get slow; the loop stops when the tab is hidden, the
 * reader is open or the user prefers reduced motion. */
var Sakura = (() => {
  const layers = [{ cv: $('sakura'), list: [] }, { cv: $('sakuraFront'), list: [] }];
  const DPR = Math.min(2, devicePixelRatio || 1), TINTS = ['#F2A7B8', '#F6C1CC', '#EE98AC', '#FBD9E0'];
  const sprites = new Map();
  function sprite(tint, blur) {
    const key = tint + blur;
    if (sprites.has(key)) return sprites.get(key);
    const S = 64, pad = Math.ceil(blur * 3) + 2, c = document.createElement('canvas');
    c.width = c.height = S + pad * 2;
    const g = c.getContext('2d');
    g.translate(c.width / 2, c.height / 2);
    if (blur) g.filter = `blur(${blur}px)`;
    g.scale(S, S);
    const grad = g.createLinearGradient(0, -.5, 0, .5);
    grad.addColorStop(0, '#FFF4F6'); grad.addColorStop(.38, tint); grad.addColorStop(1, mix(tint, '#B0566E', .38));
    g.fillStyle = grad;
    g.beginPath(); g.moveTo(0, -.5); g.bezierCurveTo(.42, -.35, .45, .25, .12, .5); g.lineTo(0, .38); g.lineTo(-.12, .5);
    g.bezierCurveTo(-.45, .25, -.42, -.35, 0, -.5); g.fill();
    g.strokeStyle = 'rgba(255,255,255,.4)'; g.lineWidth = .025;
    g.beginPath(); g.moveTo(0, -.42); g.quadraticCurveTo(.04, 0, 0, .34); g.stroke();
    const s = { c, S }; sprites.set(key, s); return s;
  }
  let W = innerWidth, H = innerHeight, running = false, paused = false, last = 0, t = 0, slow = 0, lastScroll = scrollY;
  const ptr = { x: -1e4, y: -1e4, vx: 0, wind: 0, on: false, at: 0 };
  const tint = () => TINTS[(Math.random() * TINTS.length) | 0];
  function make(front, anywhere) {
    const d = front ? 1.5 + Math.random() * .6 : .25 + Math.random() * .8;
    const blur = front ? 2.6 : d < .42 ? 1.4 : 0;
    return { front, d, sp: sprite(tint(), blur), size: front ? 20 + Math.random() * 14 : (8 + Math.random() * 9) * (.55 + d * .6),
      a: front ? .38 + Math.random() * .14 : .35 + .5 * d, x: Math.random() * W, y: anywhere ? Math.random() * H : -30 - Math.random() * 60,
      vx: 0, vy: 0, fall: front ? 46 + Math.random() * 20 : 16 + Math.random() * 22, rot: Math.random() * 6.28, rv: (Math.random() - .5) * 2.4,
      fl: Math.random() * 6.28, fv: 1.2 + Math.random() * 1.6, ph: Math.random() * 6.28, life: Infinity };
  }
  function target() { return { back: Math.round(Math.max(10, Math.min(44, W * H / 30000)) * (1 - slow)), front: W * H > 9e5 ? 5 : 3 }; }
  function populate() {
    const n = target();
    const back = layers[0].list.filter(p => p.life === Infinity), front = layers[1].list.filter(p => p.life === Infinity);
    while (back.length < n.back) back.push(make(false, true));
    while (front.length < n.front) front.push(make(true, true));
    back.length = Math.min(back.length, n.back); front.length = Math.min(front.length, n.front);
    layers[0].list = back.concat(layers[0].list.filter(p => p.life !== Infinity));
    layers[1].list = front.concat(layers[1].list.filter(p => p.life !== Infinity));
  }
  function resize() {
    W = innerWidth; H = innerHeight;
    for (const l of layers) { l.cv.width = W * DPR; l.cv.height = H * DPR; }
    populate(); if (!running) drawAll();
  }
  function step(p, dt) {
    const gust = Math.sin(t * .37) * Math.sin(t * .13 + 1.3) * 26 + Math.sin(t * .9 + p.ph) * 5;
    const wind = (gust + ptr.wind) * p.d;
    if (p.life !== Infinity) {                         // burst petal: ballistic with drag, then fades
      p.vx += (wind - p.vx) * dt * .8; p.vy += 70 * dt; p.vy *= 1 - dt * .9; p.life -= dt;
    } else {
      p.vx += (wind + Math.cos(t * 1.2 + p.ph) * 14 * p.d - p.vx) * Math.min(1, dt * 1.5);
      p.vy += (p.fall * p.d - p.vy) * Math.min(1, dt * 1.2);
    }
    if (ptr.on) {
      const dx = p.x - ptr.x, dy = p.y - ptr.y, R = 120 * Math.min(1.4, p.d + .3), r2 = dx * dx + dy * dy;
      if (r2 < R * R) { const r = Math.sqrt(r2) + 1, f = (1 - r / R) * (220 + Math.abs(ptr.vx) * .5) * dt * 3; p.vx += dx / r * f; p.vy += dy / r * f; }
    }
    p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.rv * dt; p.fl += p.fv * dt;
    if (p.life === Infinity) {
      if (p.y > H + 40) Object.assign(p, make(p.front, false), { x: Math.random() * (W + 200) - (ptr.wind > 0 ? 200 : 0) });
      else if (p.y < -90) p.y += H + 120;              // scrolled past the top: re-enter from below, like the page
      if (p.x < -50) p.x += W + 100; else if (p.x > W + 50) p.x -= W + 100;
    }
  }
  function draw(l) {
    const g = l.cv.getContext('2d');
    g.setTransform(1, 0, 0, 1, 0, 0); g.clearRect(0, 0, l.cv.width, l.cv.height);
    for (const p of l.list) {
      const sc = p.size / p.sp.S, flip = .3 + .7 * Math.abs(Math.cos(p.fl)), c = Math.cos(p.rot), s = Math.sin(p.rot);
      g.globalAlpha = p.a * (.78 + .22 * flip) * (p.life < 1 ? Math.max(0, p.life) : 1);
      g.setTransform(DPR * c * sc * flip, DPR * s * sc * flip, -DPR * s * sc, DPR * c * sc, DPR * p.x, DPR * p.y);
      const w = p.sp.c.width; g.drawImage(p.sp.c, -w / 2, -w / 2);
    }
  }
  const drawAll = () => layers.forEach(draw);
  function frame(now) {
    if (!running) return;
    const dt = Math.min(.05, (now - last) / 1000 || .016); last = now; t += dt;
    // adaptive quality: sustained frames slower than ~40 fps shed far petals (never below 10)
    slow = dt > .025 ? Math.min(.6, slow + dt * .05) : Math.max(0, slow - dt * .01);
    if (slow > 0 && (t * 10 | 0) % 20 === 0) populate();
    ptr.wind *= Math.exp(-dt * 1.4);
    for (const l of layers) { for (const p of l.list) step(p, dt); l.list = l.list.filter(p => p.life > 0 && p.y < H + 60); }
    drawAll();
    requestAnimationFrame(frame);
  }
  function sync() {
    const want = !paused && !document.hidden && !REDUCE.matches;
    if (want && !running) { running = true; last = performance.now(); requestAnimationFrame(frame); }
    if (!want) { running = false; drawAll(); }
  }
  addEventListener('resize', resize);
  document.addEventListener('visibilitychange', sync);
  REDUCE.addEventListener?.('change', sync);
  addEventListener('pointermove', e => {
    const now = performance.now(), dt = Math.max(1, now - ptr.at) / 1000;
    ptr.vx = (e.clientX - ptr.x) / dt; ptr.at = now;
    if (ptr.on && Math.abs(ptr.vx) < 6000) ptr.wind = Math.max(-90, Math.min(90, ptr.wind + ptr.vx * .012));
    ptr.x = e.clientX; ptr.y = e.clientY; ptr.on = true;
  }, { passive: true });
  addEventListener('mouseout', e => { if (!e.relatedTarget) { ptr.on = false; ptr.x = ptr.y = -1e4; } });
  addEventListener('scroll', () => {
    const dy = scrollY - lastScroll; lastScroll = scrollY;
    for (const p of layers[0].list) p.y -= dy * p.d * .3;
    for (const p of layers[1].list) p.y -= dy * .55;
    if (!running) drawAll();
  }, { passive: true });
  resize(); sync();
  return {
    pause(v) { paused = v; sync(); },
    /** A puff of petals from a point (a like, a 9/10, finishing a series). Drawn on the near layer. */
    burst(x, y, n = 16) {
      if (REDUCE.matches) return;
      for (let i = 0; i < n; i++) {
        const a = -Math.PI / 2 + (Math.random() - .5) * 2.4, v = 160 + Math.random() * 220;
        layers[1].list.push(Object.assign(make(true, false), { x, y, vx: Math.cos(a) * v, vy: Math.sin(a) * v, size: 11 + Math.random() * 10,
          sp: sprite(tint(), 0), a: .9, d: 1, life: 2.2 + Math.random() * 1.6, rv: (Math.random() - .5) * 9 }));
      }
      if (!running) sync();
    },
  };
})();
var burstAt = (el, n) => { const r = el.getBoundingClientRect(); Sakura.burst(r.left + r.width / 2, r.top + r.height / 2, n); };

/* ---------- small motion kit: ink ripple, cover tilt, reveal-on-scroll, count-up, toast ---------- */
var lastPress = { el: null, at: 0 };
document.addEventListener('pointerdown', e => {
  const el = e.target.closest('.cover .img, .rec .img, .lib .img') || e.target.closest('.panel')?.querySelector('img');
  if (el) { lastPress.el = el; lastPress.at = performance.now(); }
}, true);
document.addEventListener('pointerdown', e => {
  const host = e.target.closest('.btn, .chip, nav button, .modes button, .fbb, .tbtn');
  if (!host || REDUCE.matches) return;
  const r = host.getBoundingClientRect(), s = document.createElement('span');
  s.className = 'rip'; s.style.left = (e.clientX - r.left) + 'px'; s.style.top = (e.clientY - r.top) + 'px';
  s.style.setProperty('--s', Math.ceil(Math.hypot(r.width, r.height) / 6));
  host.appendChild(s); s.addEventListener('animationend', () => s.remove());
});
if (matchMedia('(hover: hover) and (pointer: fine)').matches) {
  let tiltRaf = 0, tiltEv = null;
  document.addEventListener('pointermove', e => {
    tiltEv = e;
    if (tiltRaf) return;
    tiltRaf = requestAnimationFrame(() => {
      tiltRaf = 0;
      const cover = tiltEv.target.closest && tiltEv.target.closest('.cover');
      document.querySelectorAll('.cover.tilting').forEach(c => { if (c !== cover) { c.classList.remove('tilting'); c.style.removeProperty('--rx'); c.style.removeProperty('--ry'); } });
      if (!cover || REDUCE.matches) return;
      const img = cover.querySelector('.img'), r = img.getBoundingClientRect();
      const px = (tiltEv.clientX - r.left) / r.width, py = (tiltEv.clientY - r.top) / r.height;
      cover.classList.add('tilting');
      cover.style.setProperty('--ry', ((px - .5) * 14).toFixed(2) + 'deg'); cover.style.setProperty('--rx', ((.5 - py) * 12).toFixed(2) + 'deg');
      img.style.setProperty('--gx', (px * 100).toFixed(1) + '%'); img.style.setProperty('--gy', (py * 100).toFixed(1) + '%');
    });
  }, { passive: true });
}
var revealer = 'IntersectionObserver' in window ? new IntersectionObserver(es => es.forEach(e => {
  if (e.isIntersecting) { e.target.classList.add('in'); revealer.unobserve(e.target); }
}), { rootMargin: '0px 0px -8% 0px' }) : null;
function reveal(root, sel = '.rail') {
  root.querySelectorAll(sel).forEach(el => { if (!revealer || REDUCE.matches) return el.classList.add('in'); el.classList.add('reveal'); revealer.observe(el); });
}
function countUp(root) {
  root.querySelectorAll('[data-count]').forEach(el => {
    const to = +el.dataset.count, dec = +(el.dataset.dec || 0);
    if (REDUCE.matches || !isFinite(to)) { el.textContent = to.toFixed(dec); return; }
    const t0 = performance.now(), dur = 900;
    const tick = (now) => { const k = Math.min(1, (now - t0) / dur), v = to * (1 - Math.pow(1 - k, 3));
      el.textContent = dec ? v.toFixed(dec) : Math.round(v).toLocaleString(); if (k < 1) requestAnimationFrame(tick); };
    requestAnimationFrame(tick);
  });
}
var toastTimer = 0;
function toast(html, action) {
  const el = $('toast');
  el.innerHTML = `<div>${html}</div>${action ? `<button id="toastBtn">${esc(action.label)}</button>` : ''}`;
  if (action) $('toastBtn').onclick = () => { el.classList.remove('on'); action.run(); };
  el.classList.add('on'); clearTimeout(toastTimer); toastTimer = setTimeout(() => el.classList.remove('on'), 5200);
}

/* ---------- windowed list: only the rows in view (+ overscan) exist in the DOM ----------
 * 1,100 One Piece episodes or a 2,000-chapter feed would otherwise create thousands of nodes on open.
 * Fixed row height keeps the maths O(1): first = floor(scrolled / rowH). */
function virtualList(host, scroller, n, rowH, render, onClick) {
  host._vl?.destroy();
  // own = the chapter panel is the scroller. The detail sheet stays still.
  const own = scroller === host;
  host.classList.add('vlist');
  if (own) host.classList.add('vport');
  host.style.height = own ? '' : n * rowH + 'px';
  host.innerHTML = own ? `<div class="vinner" style="height:${n * rowH}px"></div>` : '';
  const rows = own ? host.querySelector('.vinner') : host;
  let a0 = -1, b0 = -1, raf = 0;
  const paint = () => {
    raf = 0;
    let a, b;
    if (own) {
      const top = host.scrollTop;
      a = Math.max(0, Math.floor(top / rowH) - 6);
      b = Math.min(n, Math.ceil((top + host.clientHeight) / rowH) + 6);
    } else {
      const top = host.getBoundingClientRect().top - scroller.getBoundingClientRect().top;
      a = Math.max(0, Math.floor(-top / rowH) - 10);
      b = Math.min(n, Math.ceil((scroller.clientHeight - top) / rowH) + 10);
    }
    if (a === a0 && b === b0) return;
    a0 = a; b0 = b;
    let html = '';
    for (let i = a; i < b; i++) html += render(i).replace('<div class="ch', `<div style="top:${i * rowH}px" class="ch`);
    rows.innerHTML = html;
  };
  const onScroll = () => { if (!raf) raf = requestAnimationFrame(paint); };
  scroller.addEventListener('scroll', onScroll, { passive: true });
  const ro = new ResizeObserver(onScroll);
  if (host.parentElement) ro.observe(host.parentElement);
  ro.observe(scroller);
  host.onclick = (e) => { const r = e.target.closest('[data-i]'); if (r) onClick(+r.dataset.i, r); };
  const vl = {
    refresh() { a0 = -1; paint(); },
    scrollTo(i) {
      const y = Math.max(0, i * rowH - scroller.clientHeight / 3);
      if (own) host.scrollTop = y;
      else scroller.scrollTop += host.getBoundingClientRect().top - scroller.getBoundingClientRect().top + i * rowH - scroller.clientHeight / 3;
    },
    destroy() {
      scroller.removeEventListener('scroll', onScroll); ro.disconnect();
      host.classList.remove('vlist', 'vport'); host.style.height = ''; host._vl = null;
    },
  };
  host._vl = vl; paint();
  return vl;
}
setTimeout(() => $('splash').classList.add('gone'), 1750);

api('/api/health').then(h => {
  $('health').innerHTML = `<span style="color:var(--like)">● up</span> · ${esc(h.database.split(' on ')[0])}<br><b data-count="${h.media}">0</b> titles · <b data-count="${h.users}">0</b> readers · <b data-count="${h.catalogSources}">0</b> catalog sources`;
  setTimeout(() => countUp($('health')), 1500);
}).catch(e => { $('health').innerHTML = `<span class="err">backend error: ${esc(e.message)}</span>`; });

