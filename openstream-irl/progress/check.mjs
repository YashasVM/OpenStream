import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { summarize } from './public/app.js';
import worker from './worker.js';

const data = JSON.parse(await readFile(new URL('./public/status.json', import.meta.url)));
assert.equal(Object.values(summarize(data)).slice(0, 3).reduce((sum, count) => sum + count, 0), data.items.length);
const example = { updated: '2026-10-21', summary: 'Check', items: [
  { title: 'A', detail: 'A', status: 'implemented', deviceTested: false },
  { title: 'B', detail: 'B', status: 'in-progress', deviceTested: true },
  { title: 'C', detail: 'C', status: 'pending', deviceTested: false },
] };
assert.deepEqual(summarize(example), { implemented: 1, 'in-progress': 1, pending: 1, 'not-device-tested': 2 });
assert.throws(() => summarize({ ...example, items: [{ ...example.items[0], status: 'unknown' }] }));
assert.throws(() => summarize({ ...example, items: [{ ...example.items[0], deviceTested: 'false' }] }));
const env = { ASSETS: { fetch: async request => new Response(new URL(request.url).pathname) } };
for (const path of ['/irl', '/irl/', '/irl/index.html']) {
  const response = await worker.fetch(new Request('https://a.yash0.in' + path), env);
  assert.equal(await response.text(), '/index.html');
  assert.equal(response.headers.get('Cache-Control'), 'no-cache');
}
for (const path of ['/irl/style.css', '/irl/app.js', '/irl/status.json']) {
  const response = await worker.fetch(new Request('https://a.yash0.in' + path), env);
  assert.equal(await response.text(), path.slice(4));
}
for (const path of ['/', '/openstream/', '/irl-other', '/irl/private']) {
  assert.equal((await worker.fetch(new Request('https://a.yash0.in' + path), env)).status, 404);
}
assert.equal((await worker.fetch(new Request('https://a.yash0.in/irl', { method: 'POST' }), env)).status, 405);
console.log('Status counts, validation, and Worker routing checks passed.');
