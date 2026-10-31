import { createServer } from 'node:http';
import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { mkdir, open, readFile, readdir, rename, rm, realpath } from 'node:fs/promises';
import { pipeline } from 'node:stream/promises';
import { homedir } from 'node:os';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}';
const ROUTE = new RegExp(`^/api/sessions/(${UUID})(/.*)$`);
const UPLOAD = new RegExp(`^/runs/(${UUID})/segments/(0|[1-9][0-9]*)$`);
const IDLE = 86_400_000;
const LIMIT = 8 * 1024 * 1024;
const PRIVATE = { 'Cache-Control': 'private, no-store', 'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff' };
const WEB = fileURLToPath(new URL('../web/', import.meta.url));
const STATIC = new Set(['/index.html', '/watch.html', '/pair.js', '/watch.js', '/style.css', '/vendor/hls.min.js', '/vendor/hls.LICENSE.txt']);
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.txt': 'text/plain; charset=utf-8' };
class HttpError extends Error {
  constructor(status, message, nextSequence) { super(message); this.status = status; this.nextSequence = nextSequence; }
}
const hash = value => createHash('sha256').update(value).digest();
const bearer = req => req.headers.authorization?.startsWith('Bearer ') ? req.headers.authorization.slice(7) : '';
const authorized = (provided, storedHash) => timingSafeEqual(hash(provided), Buffer.from(storedHash, 'hex'));
function integer(value, min, max) {
  if (typeof value !== 'string' || !/^(0|[1-9][0-9]*)$/.test(value)) throw new HttpError(400, 'Expected unsigned integer');
  const result = Number(value);
  if (!Number.isSafeInteger(result) || result < min || result > max) throw new HttpError(400, 'Integer out of range');
  return result;
}
async function atomicWrite(path, body) {
  const temp = `${path}.${randomUUID()}.tmp`;
  try {
    const file = await open(temp, 'wx', 0o600);
    try { await file.writeFile(body); await file.sync(); } finally { await file.close(); }
    await rename(temp, path);
    const directory = await open(dirname(path), 'r');
    try { await directory.sync(); } finally { await directory.close(); }
  } finally { await rm(temp, { force: true }); }
}
function body(req, timeoutMs) {
  const declared = req.headers['content-length'];
  if (declared !== undefined && integer(declared, 0, Number.MAX_SAFE_INTEGER) > LIMIT) throw new HttpError(413, 'Segment exceeds 8 MiB');
  return new Promise((resolveBody, reject) => {
    let total = 0;
    const chunks = [];
    const finish = error => {
      clearTimeout(timer);
      req.off('data', data); req.off('end', end); req.off('error', errorEvent); req.off('aborted', aborted);
      req.pause();
      if (error) reject(error);
      else resolveBody(Buffer.concat(chunks, total));
    };
    const data = chunk => {
      total += chunk.length;
      if (total > LIMIT) return finish(new HttpError(413, 'Segment exceeds 8 MiB'));
      chunks.push(chunk);
    };
    const end = () => finish(!total || (declared !== undefined && Number(declared) !== total) ? new HttpError(400, 'Invalid segment length') : null);
    const errorEvent = () => finish(new HttpError(400, 'Upload interrupted'));
    const aborted = () => finish(new HttpError(400, 'Upload interrupted'));
    const timer = setTimeout(() => finish(new HttpError(408, 'Upload body timed out')), timeoutMs);
    req.on('data', data); req.once('end', end); req.once('error', errorEvent); req.once('aborted', aborted);
    if (req.destroyed || req.readableEnded) finish(new HttpError(400, 'Upload interrupted'));
    else req.resume();
  });
}
function json(value, status = 200) { return { status, headers: { ...PRIVATE, 'Content-Type': 'application/json' }, body: JSON.stringify(value) }; }

// One server process owns a data directory; session queues coordinate all its requests and cleanup.
export async function createRelay({ dataDir, adminToken = '', webDir = WEB, now = Date.now, bodyTimeoutMs = 15_000, cleanupIntervalMs = 60_000 }) {
  dataDir = resolve(dataDir);
  webDir = resolve(webDir);
  await mkdir(dataDir, { recursive: true, mode: 0o700 });
  const queues = new Map();
  async function serial(id, action) {
    let queue = queues.get(id);
    if (!queue) { queue = { tail: Promise.resolve(), count: 0 }; queues.set(id, queue); }
    if (queue.count >= 32) throw new HttpError(429, 'Session busy; retry');
    queue.count++;
    const previous = queue.tail;
    let release;
    queue.tail = new Promise(resolveQueue => { release = resolveQueue; });
    await previous;
    try { return await action(); }
    finally { queue.count--; release(); if (!queue.count) queues.delete(id); }
  }
  const statePath = id => join(dataDir, id, 'state.json');
  const segmentPath = (id, sequence) => join(dataDir, id, `${sequence}.ts`);
  async function load(id) {
    try { return JSON.parse(await readFile(statePath(id), 'utf8')); }
    catch (error) { if (error.code === 'ENOENT') throw new HttpError(404, 'Session not found'); throw error; }
  }
  const save = state => atomicWrite(statePath(state.sessionId), JSON.stringify(state));
  async function prune(state) {
    const keep = new Set(['state.json', ...state.segments.map(s => `${s.global}.ts`)]);
    for (const name of await readdir(join(dataDir, state.sessionId))) {
      if (!keep.has(name) && (/^[0-9]+\.ts$/.test(name) || name.endsWith('.tmp'))) await rm(join(dataDir, state.sessionId, name), { force: true });
    }
  }
  async function cleanup() {
    for (const entry of await readdir(dataDir, { withFileTypes: true })) {
      if (!entry.isDirectory() || !new RegExp(`^${UUID}$`).test(entry.name)) continue;
      await serial(entry.name, async () => {
        let state;
        try { state = await load(entry.name); }
        catch (error) {
          if (error.status !== 404) throw error;
          // A crash before initial metadata commit can leave an empty session directory.
          for (const name of await readdir(join(dataDir, entry.name))) {
            if (!name.endsWith('.tmp')) throw new Error('Session metadata missing');
          }
          await rm(join(dataDir, entry.name), { recursive: true, force: true });
          return;
        }
        if (state.lastActivity + IDLE <= now() && state.segments.length) {
          state.discontinuities += state.segments.filter(segment => segment.discontinuity).length;
          state.segments = [];
          state.ended = true;
          await save(state);
        }
        await prune(state);
      });
    }
  }
  async function session(req, url, id, path) {
    const state = await load(id);
    if (req.method === 'GET') {
      const view = url.searchParams.get('token') ?? '';
      if (!authorized(view, state.viewHash)) throw new HttpError(401, 'Unauthorized');
      if (path === '/status') return json({ segmentCount: state.segments.length, lastUploadAt: state.lastUploadAt, runId: state.runId });
      if (path === '/index.m3u8') {
        const lines = ['#EXTM3U', '#EXT-X-VERSION:3', '#EXT-X-TARGETDURATION:6', '#EXT-X-INDEPENDENT-SEGMENTS',
          `#EXT-X-MEDIA-SEQUENCE:${state.segments[0]?.global ?? state.globalNext}`, `#EXT-X-DISCONTINUITY-SEQUENCE:${state.discontinuities}`];
        for (const segment of state.segments) {
          if (segment.discontinuity) lines.push('#EXT-X-DISCONTINUITY');
          lines.push(`#EXT-X-PROGRAM-DATE-TIME:${new Date(segment.capture).toISOString()}`, `#EXTINF:${(segment.duration / 1000).toFixed(3)},`, `/api/sessions/${id}/media/${segment.global}.ts?token=${encodeURIComponent(view)}`);
        }
        if (state.ended) lines.push('#EXT-X-ENDLIST');
        return { status: 200, headers: { ...PRIVATE, 'Content-Type': 'application/vnd.apple.mpegurl' }, body: lines.join('\n') + '\n' };
      }
      const media = /^\/media\/(0|[1-9][0-9]*)\.ts$/.exec(path);
      if (!media || !state.segments.some(s => s.global === Number(media[1]))) throw new HttpError(404, 'Segment not retained');
      const file = await open(segmentPath(id, media[1]), 'r');
      return { status: 200, headers: { ...PRIVATE, 'Content-Type': 'video/mp2t', 'Content-Length': String((await file.stat()).size) }, stream: file.createReadStream() };
    }
    if (!authorized(bearer(req), state.publishHash)) throw new HttpError(401, 'Unauthorized');
    if (req.method === 'POST' && (path === '/start' || path === '/end')) {
      if (path === '/start') { state.runId = randomUUID(); state.nextSequence = 0; state.ended = false; }
      else state.ended = true;
      state.lastActivity = now();
      await save(state);
      await prune(state);
      return json(path === '/start' ? { runId: state.runId } : { ended: true });
    }
    const upload = UPLOAD.exec(path);
    if (!upload || req.method !== 'PUT') throw new HttpError(404, 'Not found');
    if (upload[1] !== state.runId) throw new HttpError(409, 'Stale run', state.nextSequence);
    const sequence = integer(upload[2], 0, Number.MAX_SAFE_INTEGER - 1);
    if (sequence > state.nextSequence) throw new HttpError(409, 'Sequence gap', state.nextSequence);
    if (req.headers['content-type']?.split(';')[0].trim().toLowerCase() !== 'video/mp2t') throw new HttpError(415, 'Expected video/mp2t');
    const duration = integer(req.headers['x-duration-ms'], 200, 6000);
    const capture = integer(req.headers['x-capture-time-ms'], 0, 8_640_000_000_000_000);
    const bytes = await body(req, bodyTimeoutMs);
    if (bytes.length % 188) throw new HttpError(400, 'Expected 188-byte MPEG-TS packets');
    for (let offset = 0; offset < bytes.length; offset += 188) if (bytes[offset] !== 0x47) throw new HttpError(400, 'Invalid MPEG-TS sync byte');
    const checksum = hash(bytes).toString('hex');
    if (sequence < state.nextSequence) {
      const receipt = state.segments.find(s => s.runId === state.runId && s.sequence === sequence);
      if (!receipt) throw new HttpError(410, 'Retry is outside the retained window', state.nextSequence);
      if (receipt.checksum !== checksum || receipt.duration !== duration || receipt.capture !== capture) throw new HttpError(409, 'Duplicate differs from committed segment', state.nextSequence);
      await prune(state);
      return json({ nextSequence: state.nextSequence });
    }
    if (state.ended) throw new HttpError(409, 'Run ended', state.nextSequence);
    const global = state.globalNext;
    await atomicWrite(segmentPath(id, global), bytes);
    state.segments.push({ global, duration, capture, discontinuity: sequence === 0 && global > 0, runId: state.runId, sequence, checksum });
    state.globalNext++; state.nextSequence++;
    state.lastUploadAt = now(); state.lastActivity = now();
    while (state.segments.length > 120) { if (state.segments.shift().discontinuity) state.discontinuities++; }
    await save(state);
    await prune(state);
    return json({ nextSequence: state.nextSequence });
  }
  async function route(req) {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname === '/api/sessions' && req.method === 'POST') {
      if (!adminToken) throw new HttpError(503, 'Session creation is not configured');
      if (!timingSafeEqual(hash(bearer(req)), hash(adminToken))) throw new HttpError(401, 'Unauthorized');
      const sessionId = randomUUID();
      const publishToken = randomBytes(32).toString('hex');
      const viewToken = randomBytes(32).toString('hex');
      await serial(sessionId, async () => {
        await mkdir(join(dataDir, sessionId), { mode: 0o700 });
        await save({ sessionId, publishHash: hash(publishToken).toString('hex'), viewHash: hash(viewToken).toString('hex'), runId: null, nextSequence: 0, globalNext: 0, discontinuities: 0, segments: [], ended: false, lastUploadAt: null, lastActivity: now() });
        const directory = await open(dataDir, 'r');
        try { await directory.sync(); } finally { await directory.close(); }
      });
      return json({ sessionId, publishToken, viewToken }, 201);
    }
    if (url.pathname.startsWith('/api/')) {
      const match = ROUTE.exec(url.pathname);
      if (!match) throw new HttpError(404, 'Not found');
      return serial(match[1], () => session(req, url, match[1], match[2]));
    }
    if (req.method !== 'GET' && req.method !== 'HEAD') throw new HttpError(405, 'Method not allowed');
    const path = url.pathname === '/' ? '/index.html' : url.pathname;
    if (!STATIC.has(path)) throw new HttpError(404, 'Not found');
    let file;
    try {
      const actual = await realpath(join(webDir, path));
      if (!actual.startsWith(await realpath(webDir) + '/')) throw new HttpError(404, 'Not found');
      file = await open(actual, 'r');
    } catch (error) { if (error.code === 'ENOENT') throw new HttpError(404, 'Not found'); throw error; }
    const size = (await file.stat()).size;
    if (req.method === 'HEAD') { await file.close(); return { status: 200, headers: { ...PRIVATE, 'Content-Type': MIME[extname(path)], 'Content-Length': String(size) }, body: '' }; }
    return { status: 200, headers: { ...PRIVATE, 'Content-Type': MIME[extname(path)], 'Content-Length': String(size) }, stream: file.createReadStream() };
  }
  const server = createServer(async (req, res) => {
    req.on('error', () => {});
    try {
      const result = await route(req);
      // Close on unread request bodies so rejected/slow uploads cannot poison a keep-alive connection.
      res.writeHead(result.status, { ...result.headers, ...(!req.complete ? { Connection: 'close' } : {}) });
      if (result.stream) await pipeline(result.stream, res);
      else res.end(result.body);
    } catch (error) {
      if (res.headersSent) { res.destroy(); return; }
      const result = error instanceof HttpError ? json({ error: error.message, ...(error.nextSequence === undefined ? {} : { nextSequence: error.nextSequence }) }, error.status) : json({ error: 'Relay temporarily unavailable; retry the same request' }, 503);
      if (!(error instanceof HttpError)) console.error(JSON.stringify({ event: 'relay_request_failed' }));
      res.writeHead(result.status, { ...result.headers, Connection: 'close' }); res.end(result.body);
    }
  });
  server.requestTimeout = 30_000; server.headersTimeout = 20_000;
  await cleanup();
  const timer = setInterval(() => { void cleanup().catch(() => console.error(JSON.stringify({ event: 'relay_cleanup_failed' }))); }, cleanupIntervalMs);
  timer.unref();
  return { server, cleanup, async close() { clearInterval(timer); server.closeIdleConnections(); await new Promise((resolveClose, reject) => server.close(error => error ? reject(error) : resolveClose())); } };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const adminToken = process.env.CREATE_SESSION_TOKEN_FILE ? (await readFile(process.env.CREATE_SESSION_TOKEN_FILE, 'utf8')).trim() : process.env.CREATE_SESSION_TOKEN ?? '';
    const relay = await createRelay({ dataDir: process.env.IRL_DATA_DIR ?? join(homedir(), '.local/share/openstream-irl'), adminToken });
    const port = integer(process.env.PORT ?? '8790', 1, 65535);
    relay.server.listen(port, process.env.HOST ?? '127.0.0.1', () => console.info(`OpenStream-IRL relay listening on port ${port}`));
    relay.server.on('error', () => { console.error('Relay listener failed'); process.exitCode = 1; void relay.close().catch(() => {}); });
    for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => { void relay.close().then(() => { process.exitCode = 0; }); });
  } catch { console.error('Relay startup failed; check configuration and storage permissions'); process.exitCode = 1; }
}
