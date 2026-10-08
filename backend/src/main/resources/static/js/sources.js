/* ---------- sources ---------- */
var srcPage = 0;
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
