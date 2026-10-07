const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
async function api(path, opts = {}) {
  const res = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...opts });
  if (!res.ok) {
    let detail = res.statusText;
    try { detail = (await res.json()).detail || detail; } catch {}
    throw new Error(`${res.status}: ${detail}`);
  }
  return res.status === 204 ? null : res.json();
}
const TYPE = { ANIME:'#7C9CE0', MANGA:'#E5532D', MANHWA:'#4DB38A', MANHUA:'#EFB23B', WEBCOMIC:'#E88FA6' };
const STATUS = { CURRENT:['#4DB38A','Watching','Reading'], COMPLETED:['#7C9CE0','Completed'], PLANNING:['#B9AD9A','Planning'], PAUSED:['#EFB23B','Paused'], DROPPED:['#E5532D','Dropped'] };
const statusLabel = (s, anime) => !STATUS[s] ? 'Add' : s === 'CURRENT' ? (anime ? 'Watching' : 'Reading') : STATUS[s][1];
const sealColor = (score) => mix('#EFB23B', '#E5532D', (score - 1) / 9);
const typeColor = (t) => TYPE[t] || TYPE.MANGA;
const mix = (hex, other, p) => {
  const a = hex.match(/\w\w/g).map(x => parseInt(x, 16)), b = other.match(/\w\w/g).map(x => parseInt(x, 16));
  return '#' + a.map((v, i) => Math.round(v + (b[i] - v) * p).toString(16).padStart(2, '0')).join('');
};
const state = { user: +(localStorage.getItem('aniga.user') || 1), userName: localStorage.getItem('aniga.userName') || 'Kenji', ratings: new Map(), media: [], library: new Map(), seen: new Map() };
const REDUCE = matchMedia('(prefers-reduced-motion: reduce)');
/** Remember every catalog title we render so detail/patching never needs a refetch. */
const remember = (list) => { for (const m of list) if (m && typeof m.id === 'number') state.seen.set(m.id, m); return list; };

/* ---------- illustrations ---------- */
let svgSeq = 0;
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
const shelfOf = (m) => typeof m.id === 'number' ? state.library.get(m.id) : null;
const totalOf = (m) => (m.mediaType || m.type) === 'ANIME' ? m.episodes : m.chapters;
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
const canVT = () => !!document.startViewTransition && !REDUCE.matches;
if (document.startViewTransition) document.documentElement.classList.add('vt');
function switchTab(b) {
  document.querySelectorAll('nav button, main section').forEach(x => x.classList.remove('on'));
  b.classList.add('on'); $(b.dataset.tab).classList.add('on');
}
const tabIndex = (b) => [...document.querySelectorAll('nav button')].indexOf(b);
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
const Sakura = (() => {
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
const burstAt = (el, n) => { const r = el.getBoundingClientRect(); Sakura.burst(r.left + r.width / 2, r.top + r.height / 2, n); };

/* ---------- small motion kit: ink ripple, cover tilt, reveal-on-scroll, count-up, toast ---------- */
const lastPress = { el: null, at: 0 };
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
const revealer = 'IntersectionObserver' in window ? new IntersectionObserver(es => es.forEach(e => {
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
let toastTimer = 0;
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
  host.classList.add('vlist'); host.style.height = n * rowH + 'px'; host.innerHTML = '';
  let a0 = -1, b0 = -1, raf = 0;
  const paint = () => {
    raf = 0;
    const top = host.getBoundingClientRect().top - scroller.getBoundingClientRect().top;
    const a = Math.max(0, Math.floor(-top / rowH) - 10), b = Math.min(n, Math.ceil((scroller.clientHeight - top) / rowH) + 10);
    if (a === a0 && b === b0) return;
    a0 = a; b0 = b;
    let html = '';
    for (let i = a; i < b; i++) html += render(i).replace('<div class="ch', `<div style="top:${i * rowH}px" class="ch`);
    host.innerHTML = html;
  };
  const onScroll = () => { if (!raf) raf = requestAnimationFrame(paint); };
  scroller.addEventListener('scroll', onScroll, { passive: true });
  // content above the list (shelf panel, gallery) can load later and push it down without any scroll event
  const ro = new ResizeObserver(onScroll);
  ro.observe(host.parentElement); ro.observe(scroller);
  host.onclick = (e) => { const r = e.target.closest('[data-i]'); if (r) onClick(+r.dataset.i, r); };
  const vl = {
    refresh() { a0 = -1; paint(); },
    scrollTo(i) { scroller.scrollTop += host.getBoundingClientRect().top - scroller.getBoundingClientRect().top + i * rowH - scroller.clientHeight / 3; },
    destroy() { scroller.removeEventListener('scroll', onScroll); ro.disconnect(); host.classList.remove('vlist'); host.style.height = ''; host._vl = null; },
  };
  host._vl = vl; paint();
  return vl;
}
setTimeout(() => $('splash').classList.add('gone'), 1750);

api('/api/health').then(h => {
  $('health').innerHTML = `<span style="color:var(--like)">● up</span> · ${esc(h.database.split(' on ')[0])}<br><b data-count="${h.media}">0</b> titles · <b data-count="${h.users}">0</b> readers · <b data-count="${h.catalogSources}">0</b> catalog sources`;
  setTimeout(() => countUp($('health')), 1500);
}).catch(e => { $('health').innerHTML = `<span class="err">backend error: ${esc(e.message)}</span>`; });

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

/* ---------- for you ---------- */
let recType = '';
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
let tasteKind = 'genre', lastTaste = null, recsTimer = 0;
const pct = (x) => Math.round(x * 100) + '%';
const featLabel = (f) => ((lastTaste && lastTaste.features.find(x => x.feature === f)) || {}).label || f.replace(/^(g|t|type):/, '');
const fmtDelta = (deltas, n = 3) => Object.entries(deltas || {}).sort((a, b) => Math.abs(b[1]) - Math.abs(a[1])).slice(0, n)
  .map(([f, d]) => `<span class="${d > 0 ? 'up' : 'dn'}">${esc(featLabel(f))} ${d > 0 ? '+' : '−'}${Math.abs(d).toFixed(2)}</span>`).join(' · ');
const ago = (s) => {
  const t = Date.parse(String(s).replace(' ', 'T').replace(/([+-]\d\d)$/, '$1:00'));
  if (!t) return '';
  const m = (Date.now() - t) / 6e4;
  return m < 1 ? 'now' : m < 60 ? Math.round(m) + 'm' : m < 1440 ? Math.round(m / 60) + 'h' : Math.round(m / 1440) + 'd';
};
async function recommend() { loadTaste(); await loadRecs(); }
const refreshRecsSoon = () => { clearTimeout(recsTimer); recsTimer = setTimeout(loadRecs, 500); };

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

const KINDS = [['genre', 'Genres'], ['tag', 'Tags'], ['type', 'Types']];
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

/* ---------- rate ---------- */
let catType = '';
$('catalogTypes').innerHTML = ['', 'ANIME', 'MANGA', 'MANHWA', 'MANHUA'].map(t => `<span class="chip ${t === '' ? 'on' : ''}" data-t="${t}">${t || 'All types'}</span>`).join('');
$('catalogTypes').querySelectorAll('.chip').forEach(c => c.onclick = () => {
  catType = c.dataset.t; $('catalogTypes').querySelectorAll('.chip').forEach(x => x.classList.toggle('on', x === c)); searchMedia();
});
function rateRow(mid) {
  const cur = state.ratings.get(mid);
  return `<div class="rate" data-mid="${mid}">${[...Array(10)].map((_, i) => `<button class="${cur && i < cur ? 'on' : ''}" data-s="${i + 1}" title="${i + 1}">${i + 1}</button>`).join('')}</div>`;
}
function wireRating(root) {
  root.querySelectorAll('.rate').forEach(row => row.querySelectorAll('button').forEach(b => b.onclick = async (ev) => {
    ev.stopPropagation();
    const mid = +row.dataset.mid, s = +b.dataset.s, cur = state.ratings.get(mid);
    try {
      if (cur === s) await api(`/api/users/${state.user}/ratings/${mid}`, { method: 'DELETE' });
      else await api(`/api/users/${state.user}/ratings/${mid}`, { method: 'PUT', body: JSON.stringify({ score: s }) });
      await refreshRatings();
      if (cur !== s && s >= 8) burstAt(b, 12);
      row.outerHTML = rateRow(mid); wireRating(root);
    } catch (e) { alert(e.message); }
  }));
}
/* Catalog grid: pages of 48 appended by an IntersectionObserver sentinel (infinite scroll over all 900 titles).
 * Cells off-screen are skipped by content-visibility, so a long scroll stays cheap. */
const CAT_PAGE = 48;
const cat = { offset: 0, done: false, busy: false, gen: 0 };
const catObserver = new IntersectionObserver(es => { if (es.some(e => e.isIntersecting)) moreMedia(); }, { rootMargin: '900px 0px' });
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
      $('catMore').innerHTML = cat.done ? `<span class="kicker">${cat.offset} titles</span>` : '<div class="sentinel"></div>';
      const s = $('catMore').querySelector('.sentinel');
      catObserver.disconnect(); if (s) catObserver.observe(s);
    }
  }
}
$('catalogGrid').onclick = (e) => { const el = e.target.closest('.cover'); if (el) openDetail(+el.dataset.id); };
$('mediaBtn').onclick = searchMedia;
$('mediaQ').onkeydown = (e) => { if (e.key === 'Enter') searchMedia(); };

/* ---------- detail ---------- */
function tame(hex) {
  // Clamp lightness so arbitrary AniList accents stay readable on ink (mirrors Ui.tame on Android).
  let [r, g, b] = hex.match(/\w\w/g).map(x => parseInt(x, 16) / 255);
  const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2;
  const target = Math.max(.52, Math.min(.68, l));
  return l < target ? mix(hex, '#ffffff', (target - l) / (1 - l)) : l > target ? mix(hex, '#000000', (l - target) / l) : hex;
}
const readLang = () => localStorage.getItem('aniga.lang') || 'en';
const GRID = (html) => `<div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:22px 16px;margin-top:16px">${html}</div>`;

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
  ctx.vl = virtualList(out, $('sheet'), total, 64, row, (i) => ctx.setProgress && ctx.setProgress(i + 1));
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
    const hits = await api(`/api/read/find?title=${encodeURIComponent(ctx.title)}&lang=${readLang()}${aliases ? '&aliases=' + encodeURIComponent(aliases) : ''}`);
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
    ctx.vl = virtualList(out, $('sheet'), chs.length, 64, row, (i) => {
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

/* ---------- discover: title (per source + language) · story (BM25) · image (trace.moe + dHash) ---------- */
const disc = { mode: 'title', source: 'mangadex', sources: [] };
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

/* ---------- library ---------- */
let libFilter = '';
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

/* ---------- reader ---------- */
const TONES = [['night', 'Night ink', '#0E0C0A', '#E9E1D2'], ['sepia', 'Sepia', '#EDE0C4', '#4A3F33'], ['paper', 'Paper', '#F6F1E7', '#221C17'], ['oled', 'OLED', '#000000', '#D8D2C8']];
const rd = { title: '', type: '', chs: [], i: 0, lastY: 0 };
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

/* ---------- sources ---------- */
let srcPage = 0;
async function loadSources() {
  const body = $('srcBody');
  try {
    const p = await api(`/api/sources?page=${srcPage}&size=50&q=${encodeURIComponent($('srcQ').value)}&hint=${$('srcHint').value}&lang=${encodeURIComponent($('srcLang').value.trim())}&status=${$('srcStatus').value}${$('srcReadable').checked ? '&readable=true' : ''}`);
    body.innerHTML = p.items.map(s => `<tr><td>${esc(s.name)}</td><td class="mono">${esc(s.lang)}</td><td><span class="tag" style="background:${s.contentHint === 'MIXED' ? '#85796A' : typeColor(s.contentHint)}">${s.contentHint}</span></td>
      <td>${s.health ? `<span class="health ${esc(s.health)}">${s.health}</span><span class="mono muted" style="font-size:11px"> ${s.httpStatus ?? ''}${s.latencyMs != null ? ' · ' + s.latencyMs + 'ms' : ''}</span>` : '<span class="muted">—</span>'}</td>
      <td class="muted">${esc(s.homeUrl)}</td>
      <td>${s.adapterCode ? `<span class="readable" data-src="${esc(s.adapterCode)}" data-lang="${esc(s.adapterLang || '')}">read via ${esc(s.adapterCode)} · ${esc(s.adapterLang || '')} ›</span>` : '<span class="muted mono" style="font-size:12px">index only</span>'}</td></tr>`).join('');
    body.querySelectorAll('.readable').forEach(el => el.onclick = () => {
      if (el.dataset.lang) localStorage.setItem('aniga.lang', el.dataset.lang);
      disc.source = el.dataset.src; disc.mode = 'title';
      document.querySelector('nav button[data-tab="read"]').click();
      if (disc.sources.length) { initDiscover(); }
    });
    $('srcPage').textContent = `page ${p.page + 1} of ${Math.max(1, Math.ceil(p.total / p.size))} · ${p.total} sources`;
  } catch (e) { body.innerHTML = `<tr><td colspan="6" class="err">${esc(e.message)}</td></tr>`; }
}
async function loadHealth() {
  try {
    const s = await api('/api/sources/stats'), h = s.healthCheck;
    const parse = s.parseOutcomes || {};
    $('healthStats').innerHTML = s.byHealth.map(e => { const [k, v] = Object.entries(e)[0]; return `<span class="health ${esc(k)}">${k} ${v}</span>`; }).join(' ')
      + ` · pages <b>${parse.OK ?? 0}</b> ok · <b>${parse.FAIL ?? 0}</b> failed · <b>${s.reports ?? 0}</b> reports`
      + ` · <b>${s.readableInApp}</b> readable in Aniga` + (h.running ? ` · checking ${h.done}/${h.total}…` : '');
    $('healthBtn').disabled = h.running;
    if (h.running) setTimeout(loadHealth, 4000);
  } catch {}
}
$('healthBtn').onclick = async () => { await api('/api/sources/health/run', { method: 'POST' }); loadHealth(); };
loadHealth();
$('srcBtn').onclick = () => { srcPage = 0; loadSources(); };
$('srcPrev').onclick = () => { if (srcPage > 0) { srcPage--; loadSources(); } };
$('srcNext').onclick = () => { srcPage++; loadSources(); };
api('/api/sources/stats').then(s => {
  $('srcStats').innerHTML = s.byContentHint.map(e => { const [k, v] = Object.entries(e)[0];
    return `<span class="stat"><b style="color:${k === 'MIXED' ? 'var(--paper-300)' : typeColor(k)}">${v}</b><span class="kicker">${esc(k)}</span></span>`; }).join('');
}).catch(() => {});
api('/api/sources/adapters').then(a => {
  $('adapters').innerHTML = '<span class="kicker" style="margin-right:10px">Readable adapters</span>' +
    a.map(x => `<span class="chip" style="cursor:default">${esc(x.displayName)} · ${esc(x.kind)} · ${x.enabled ? 'enabled' : 'off'}</span>`).join('');
}).catch(() => {});

loadUsers().then(() => { refreshRatings(); loadShelf(); }).catch(() => loadShelf());
