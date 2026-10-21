import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { request } from 'node:http';
import { createRelay } from './server.mjs';

async function fixture(t, options = {}) {
  const dataDir = await mkdtemp(join(tmpdir(), 'openstream-irl-'));
  const f = { dataDir };
  f.start = async () => {
    f.relay = await createRelay({ dataDir, adminToken: 'test-admin', ...options });
    await new Promise(resolve => f.relay.server.listen(0, '127.0.0.1', resolve));
    f.origin = `http://127.0.0.1:${f.relay.server.address().port}`;
  };
  await f.start();
  t.after(async () => { await f.relay.close(); await rm(dataDir, { recursive: true, force: true }); });
  f.call = (path, init) => fetch(f.origin + path, init);
  const created = await f.call('/api/sessions', { method: 'POST', headers: { Authorization: 'Bearer test-admin' } });
  assert.equal(created.status, 201);
  Object.assign(f, await created.json());
  f.path = `/api/sessions/${f.sessionId}`;
  f.auth = { Authorization: `Bearer ${f.publishToken}` };
  f.startRun = async () => {
    const response = await f.call(`${f.path}/start`, { method: 'POST', headers: f.auth });
    assert.equal(response.status, 200);
    f.runId = (await response.json()).runId;
  };
  await f.startRun();
  f.upload = (sequence, label = 'segment', runId = f.runId) => f.call(`${f.path}/runs/${runId}/segments/${sequence}`, {
    method: 'PUT', headers: { ...f.auth, 'Content-Type': 'video/mp2t', 'X-Duration-Ms': '1000', 'X-Capture-Time-Ms': '1700000000000' }, body: ts(label),
  });
  f.status = () => f.call(`${f.path}/status?token=${f.viewToken}`);
  return f;
}
function ts(label) {
  const bytes = Buffer.alloc(376); bytes[0] = 0x47; bytes[188] = 0x47;
  bytes.write(label, 4);
  return bytes;
}
async function raw(origin, path, headers, send) {
  return new Promise((resolve, reject) => {
    const req = request(origin + path, { method: 'PUT', headers }, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString() }));
    });
    req.on('error', reject); send(req);
  });
}

test('real HTTP auth separation, static assets and hashed durable credentials', async t => {
  const f = await fixture(t);
  assert.equal((await f.call('/api/sessions', { method: 'POST' })).status, 401);
  assert.equal((await f.call('/api/sessions', { method: 'POST', headers: { Authorization: 'test-admin' } })).status, 401);
  assert.match(f.publishToken, /^[a-f0-9]{64}$/);
  assert.notEqual(f.publishToken, f.viewToken);
  assert.equal((await f.call(`${f.path}/status?token=${f.publishToken}`)).status, 401);
  assert.equal((await f.call(`${f.path}/end`, { method: 'POST', headers: { Authorization: `Bearer ${f.viewToken}` } })).status, 401);
  const status = await f.status();
  assert.equal(status.headers.get('cache-control'), 'private, no-store');
  assert.deepEqual(await status.json(), { segmentCount: 0, lastUploadAt: null, runId: f.runId });
  const stored = await readFile(join(f.dataDir, f.sessionId, 'state.json'), 'utf8');
  assert.ok(!stored.includes(f.publishToken) && !stored.includes(f.viewToken));
  assert.equal((await f.call('/')).status, 200);
  assert.equal((await f.call('/watch.js')).status, 200);
  assert.equal((await f.call('/%2e%2e/package.json')).status, 404);
});

test('ordered concurrent retries, body checksums and process restart recovery', async t => {
  const f = await fixture(t);
  assert.equal((await f.upload(1)).status, 409);
  const responses = await Promise.all([f.upload(0), f.upload(0)]);
  for (const response of responses) { assert.equal(response.status, 200); assert.deepEqual(await response.json(), { nextSequence: 1 }); }
  assert.equal((await f.upload(0, 'different')).status, 409);
  await f.relay.close(); await f.start();
  assert.equal((await f.upload(0)).status, 200);
  const media = await f.call(`${f.path}/media/0.ts?token=${f.viewToken}`);
  assert.deepEqual(Buffer.from(await media.arrayBuffer()), ts('segment'));
  assert.equal((await f.call(`${f.path}/media/0.ts?token=no`)).status, 401);
});

test('new runs retain earlier playlist and mark discontinuity; end retains retry ACKs', async t => {
  const f = await fixture(t);
  assert.equal((await f.upload(0)).status, 200);
  const oldRun = f.runId;
  await f.startRun();
  assert.equal((await f.upload(1, 'old', oldRun)).status, 409);
  assert.equal((await f.upload(0, 'new')).status, 200);
  assert.equal((await f.call(`${f.path}/end`, { method: 'POST', headers: f.auth })).status, 200);
  const playlist = await (await f.call(`${f.path}/index.m3u8?token=${f.viewToken}`)).text();
  assert.ok(playlist.includes('#EXT-X-MEDIA-SEQUENCE:0'));
  assert.ok(playlist.includes('#EXT-X-DISCONTINUITY\n'));
  assert.ok(playlist.includes('/media/1.ts?token='));
  assert.ok(playlist.includes('#EXT-X-ENDLIST'));
  assert.ok(!playlist.includes(f.publishToken));
  assert.equal((await f.upload(0, 'new')).status, 200);
  assert.equal((await f.upload(1)).status, 409);
});

test('120-segment retention bounds disk and receipts; idle cleanup preserves permanent pairing', async t => {
  let clock = Date.now();
  const f = await fixture(t, { now: () => clock });
  for (let i = 0; i < 122; i++) assert.equal((await f.upload(i)).status, 200);
  const dir = join(f.dataDir, f.sessionId);
  assert.equal((await readdir(dir)).filter(name => name.endsWith('.ts')).length, 120);
  assert.equal((await f.upload(0)).status, 410);
  assert.equal((await f.upload(121)).status, 200);
  let stored = JSON.parse(await readFile(join(dir, 'state.json'), 'utf8'));
  assert.equal(stored.segments.length, 120);
  await writeFile(join(dir, '999.ts'), 'orphan');
  clock += 86_400_001;
  assert.equal((await f.status()).status, 200);
  await f.relay.cleanup();
  assert.deepEqual(await readdir(dir), ['state.json']);
  stored = JSON.parse(await readFile(join(dir, 'state.json'), 'utf8'));
  assert.equal(stored.lastActivity, clock - 86_400_001);
  assert.equal(stored.globalNext, 122);
  assert.equal(stored.nextSequence, 122);
  assert.equal(stored.runId, f.runId);
  assert.equal(stored.ended, true);
  assert.equal((await f.status()).status, 200);
  await f.relay.close(); await f.start();
  await f.startRun();
  assert.equal((await f.upload(0, 'next day')).status, 200);
  assert.equal((await f.call(`${f.path}/media/122.ts?token=${f.viewToken}`)).status, 200);
  assert.equal((await f.status()).status, 200);
});

test('validates MPEG-TS, duration and body bounds over chunked HTTP; times out stalled bodies', async t => {
  const f = await fixture(t, { bodyTimeoutMs: 100 });
  const path = `${f.path}/runs/${f.runId}/segments/0`;
  const headers = { ...f.auth, 'Content-Type': 'video/mp2t', 'X-Duration-Ms': '1000', 'X-Capture-Time-Ms': '1' };
  assert.equal((await f.call(path, { method: 'PUT', headers, body: 'bad' })).status, 400);
  assert.equal((await f.call(path, { method: 'PUT', headers: { ...headers, 'X-Duration-Ms': '199' }, body: ts('valid') })).status, 400);
  const invalidSync = ts('bad sync'); invalidSync[188] = 0;
  assert.equal((await f.call(path, { method: 'PUT', headers, body: invalidSync })).status, 400);
  const oversized = await raw(f.origin, path, headers, req => { req.end(Buffer.alloc(8 * 1024 * 1024 + 1)); });
  assert.equal(oversized.status, 413);
  const declaredOversize = await raw(f.origin, path, { ...headers, 'Content-Length': String(8 * 1024 * 1024 + 1) }, req => { req.flushHeaders(); });
  assert.equal(declaredOversize.status, 413);
  const stalled = await raw(f.origin, path, { ...headers, 'Content-Length': '376' }, req => { req.write(Buffer.from([0x47])); });
  assert.equal(stalled.status, 408);
  assert.equal((await f.upload(0)).status, 200);
});
