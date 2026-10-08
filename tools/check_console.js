// Sanity check for the web console: every $('id') in the scripts exists in the markup, and the scripts parse.
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '../backend/src/main/resources/static');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const order = ['core.js', 'shelf.js', 'foryou.js', 'rate.js', 'detail.js', 'discover.js', 'library.js', 'reader.js', 'sources.js'];
const js = order.map(name => fs.readFileSync(path.join(root, 'js', name), 'utf8')).join('\n');
const ids = new Set([...(html + js).matchAll(/id="([\w-]+)"/g)].map(m => m[1]));
const used = new Set([...js.matchAll(/\$\('([\w-]+)'\)/g)].map(m => m[1]));
const missing = [...used].filter(x => !ids.has(x));
console.log('files:', order.length);
console.log('missing ids:', missing.join(' ') || 'none');
try { new Function(js); console.log('syntax ok'); } catch (e) { console.log('SYNTAX', e.message); process.exitCode = 1; }
if (missing.length) process.exitCode = 1;
