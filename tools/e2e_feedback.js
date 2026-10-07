// End-to-end check of the feedback loop against a running backend: node tools/e2e_feedback.js [baseUrl]
// Creates a throwaway user and walks the "liked horror, then rated one low, then muted it" story.
const BASE = process.argv[2] || 'http://localhost:8080';
let failures = 0;

async function api(method, path, body) {
  const t = Date.now();
  const res = await fetch(BASE + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const ms = Date.now() - t;
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status}: ${text}`);
  return { data: text ? JSON.parse(text) : null, ms };
}

function check(name, ok, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`);
  if (!ok) failures++;
}

const online = (taste, f) => (taste.features.find(x => x.feature === f) || { online: 0 }).online;
const share = (recs, g) => recs.filter(r => r.media.genres.includes(g)).length;

(async () => {
  const user = (await api('POST', '/api/users', { username: 'e2e_' + Date.now(), displayName: 'E2E' })).data;
  const u = `/api/users/${user.id}`;
  const horror = (await api('GET', '/api/media?genre=Horror&type=ANIME&limit=12')).data;
  const romance = (await api('GET', '/api/media?genre=Romance&type=ANIME&limit=6')).data
    .filter(m => !m.genres.includes('Horror'));

  for (const m of horror.slice(0, 3)) await api('PUT', `${u}/ratings/${m.id}`, { score: 9 });
  for (const m of romance.slice(0, 2)) await api('PUT', `${u}/ratings/${m.id}`, { score: 7 });
  let taste = (await api('GET', `${u}/taste/v2`)).data;
  const h1 = online(taste, 'g:Horror');
  check('high horror ratings raise the online Horror weight', h1 > 0.1, `g:Horror=${h1}`);
  check('every rating is logged as a taste change', taste.changes.length === 5, `${taste.changes.length} changes`);
  check('changes carry the model\'s prior expectation', taste.changes.every(c => typeof c.expected === 'number'));

  // The drift case: a liked genre gets a low score on a new title, the weight must drop at once.
  await api('PUT', `${u}/ratings/${horror[3].id}`, { score: 2 });
  taste = (await api('GET', `${u}/taste/v2`)).data;
  const h2 = online(taste, 'g:Horror');
  check('a low score on another horror title lowers the weight', h2 < h1, `${h1} -> ${h2}`);
  const last = taste.changes[0];
  check('the newest change explains the drop', last && last.mediaId === horror[3].id && (last.deltas['g:Horror'] ?? 0) < 0,
    JSON.stringify(last && last.deltas));

  // Recommendations with impression tracking: exploration slots + reasons.
  const r1 = await api('GET', `${u}/recommendations?limit=20&track=true`);
  const recs = r1.data;
  check('20 recommendations returned', recs.length === 20, `${r1.ms} ms`);
  check('exploration slots are marked', recs.some(r => r.slot === 'EXPLORE'),
    recs.map(r => r.slot || '-').join(','));
  check('no already-rated title is recommended', !recs.some(r => horror.slice(0, 4).some(h => h.id === r.media.id)));

  const disliked = recs[0].media;
  const ni = (await api('POST', `${u}/feedback`, { mediaId: disliked.id, kind: 'NOT_INTERESTED' })).data;
  check('NOT_INTERESTED is learned', ni.learned === true);
  const clicked = recs[1].media;
  const ck = (await api('POST', `${u}/feedback`, { mediaId: clicked.id, kind: 'CLICK' })).data;
  check('a click on a shown recommendation counts', ck.learned === true);
  const notShown = romance[romance.length - 1];
  const ck2 = (await api('POST', `${u}/feedback`, { mediaId: notShown.id, kind: 'CLICK' })).data;
  check('a click on something never recommended is not a reward', ck2.learned === false);

  const recs2 = (await api('GET', `${u}/recommendations?limit=20`)).data;
  check('the not-interested title disappears', !recs2.some(r => r.media.id === disliked.id));

  // Manual editing: boost Romance, mute Horror.
  const before = share(recs2, 'Romance');
  await api('PUT', `${u}/prefs`, { feature: 'g:Romance', weight: 1 });
  await api('PUT', `${u}/prefs`, { feature: 'g:Horror', weight: -1 });
  const recs3 = (await api('GET', `${u}/recommendations?limit=20`)).data;
  const after = share(recs3, 'Romance');
  check('boosting Romance raises its share without taking over', after > before && after <= 14,
    `${before} -> ${after} of 20`);
  check('muting Horror removes it', share(recs3, 'Horror') === 0, `${share(recs3, 'Horror')} horror left`);
  taste = (await api('GET', `${u}/taste/v2`)).data;
  check('prefs are shown first in the taste view', taste.features[0].pref !== 0, taste.features[0].feature);

  let bad = false;
  try { await api('PUT', `${u}/prefs`, { feature: 'drop table', weight: 1 }); } catch { bad = true; }
  check('malformed preference keys are rejected', bad);

  await api('DELETE', `${u}/taste/online`);
  taste = (await api('GET', `${u}/taste/v2`)).data;
  check('reset clears the learned weights', taste.features.every(f => f.online === 0));

  // Latency: warm the path, then time five calls.
  const times = [];
  for (let i = 0; i < 5; i++) times.push((await api('GET', `${u}/recommendations?limit=20`)).ms);
  times.sort((a, b) => a - b);
  check('recommendations are fast when warm', times[2] < 400, `median ${times[2]} ms`);

  console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed');
  process.exit(failures ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
