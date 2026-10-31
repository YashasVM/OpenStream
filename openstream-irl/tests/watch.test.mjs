import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../web/watch.js', import.meta.url), 'utf8');

function receiver() {
  const intervals = [];
  const timeouts = [];
  const elements = new Map();
  let bufferedEnd = 0;
  let played = 0;
  for (const selector of ['#video', '#overlay', '#status', '#play', '#badge']) {
    elements.set(selector, {
      textContent: '', hidden: selector === '#play', currentTime: 0,
      buffered: { length: 1, start: () => 0, end: () => bufferedEnd },
      addEventListener() {}, play: async () => { played++; }, pause() {},
    });
  }
  class Player {
    static Events = { LEVEL_LOADED: 'level', FRAG_BUFFERED: 'buffer', ERROR: 'error' };
    static ErrorTypes = { NETWORK_ERROR: 'network', MEDIA_ERROR: 'media' };
    static isSupported() { return true; }
    static instance;
    levels = [];
    loads = [];
    starts = 0;
    handlers = new Map();
    constructor() { Player.instance = this; }
    on(event, handler) { this.handlers.set(event, handler); }
    attachMedia() {}
    loadSource(url) { this.loads.push(url); }
    startLoad() { this.starts++; }
    destroy() {}
  }
  vm.runInNewContext(source, {
    document: { querySelector: selector => elements.get(selector) },
    window: { addEventListener() {} },
    location: { origin: 'https://relay.example', hash: `#session=123e4567-e89b-42d3-a456-426614174000&token=${'a'.repeat(64)}&delay=10` },
    URL, URLSearchParams, Hls: Player,
    setInterval: callback => { intervals.push(callback); return intervals.length; },
    setTimeout: callback => { timeouts.push(callback); return timeouts.length; },
    clearInterval() {}, clearTimeout() {},
    fetch: async () => ({ ok: true, json: async () => ({ runId: 'run-1' }) }),
  });
  return { player: Player.instance, Player, timeouts, intervals, elements,
    setBuffered: value => { bufferedEnd = value; }, played: () => played };
}

test('an initial manifest failure requests the manifest again', () => {
  const { player, Player, timeouts } = receiver();
  assert.equal(player.loads.length, 1);
  player.handlers.get(Player.Events.ERROR)(null, { fatal: true, type: Player.ErrorTypes.NETWORK_ERROR });
  timeouts.at(-1)();
  assert.equal(player.loads.length, 2);
  assert.equal(player.loads[1], player.loads[0]);
  assert.equal(player.starts, 0);
});

test('a failure after manifest discovery resumes fragment loading', () => {
  const { player, Player, timeouts } = receiver();
  player.levels = [{}];
  player.handlers.get(Player.Events.ERROR)(null, { fatal: true, type: Player.ErrorTypes.NETWORK_ERROR });
  timeouts.at(-1)();
  assert.equal(player.loads.length, 1);
  assert.equal(player.starts, 1);
});

test('playback waits for the requested recovery buffer', async () => {
  const app = receiver();
  app.setBuffered(5);
  app.intervals[0]();
  assert.equal(app.played(), 0);
  app.setBuffered(10);
  app.intervals[0]();
  await new Promise(setImmediate);
  assert.equal(app.played(), 1);
  assert.equal(app.elements.get('#overlay').hidden, true);
});
