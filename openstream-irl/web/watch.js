const video = document.querySelector('#video');
const overlay = document.querySelector('#overlay');
const status = document.querySelector('#status');
const play = document.querySelector('#play');
const params = new URLSearchParams(location.hash.slice(1));
const session = params.get('session');
const token = params.get('token');
const requestedDelay = Number(params.get('delay') || 10);
const delay = Number.isFinite(requestedDelay) ? Math.max(6, Math.min(30, requestedDelay)) : 10;
let hls = null;
let waiting = true;
let ended = false;
let starting = false;
let retryTimer = null;
let alive = true;
let observedRun = null;
let polling = false;

function show(text) {
  status.textContent = text;
  overlay.hidden = false;
}

function bufferedAhead() {
  for (let i = 0; i < video.buffered.length; i++) {
    if (video.currentTime >= video.buffered.start(i) - 0.1 && video.currentTime <= video.buffered.end(i)) return video.buffered.end(i) - Math.max(video.currentTime, video.buffered.start(i));
  }
  return 0;
}

async function beginPlayback() {
  if (starting) return;
  starting = true;
  try {
    await video.play();
    waiting = false;
    play.hidden = true;
    overlay.hidden = true;
  } catch {
    show('Click to enable audio. In OBS, open Interact and click the button below.');
    play.hidden = false;
  } finally {
    starting = false;
  }
}

function checkBuffer() {
  if (!alive || !waiting || !play.hidden) return;
  const ahead = bufferedAhead();
  if (ahead >= delay - 0.3 || ended && ahead > 0.2) {
    beginPlayback();
  } else {
    show(ahead > 0 ? `Building recovery buffer · ${Math.floor(ahead)} / ${delay} seconds` : 'Waiting for your phone. Open OpenStream-IRL and start the camera.');
  }
}

function connect() {
  if (!alive) return;
  const url = new URL(`/api/sessions/${session}/index.m3u8`, location.origin);
  url.searchParams.set('token', token);
  if (typeof Hls !== 'undefined' && Hls.isSupported()) {
    hls = new Hls({
      autoStartLoad: true,
      liveSyncDuration: delay,
      maxLiveSyncPlaybackRate: 1,
      maxBufferLength: Math.max(40, delay + 10),
      maxMaxBufferLength: 90,
      maxBufferSize: 96 * 1024 * 1024,
      backBufferLength: 10,
      lowLatencyMode: false,
      startFragPrefetch: true,
    });
    hls.on(Hls.Events.LEVEL_LOADED, (_event, data) => {
      ended = !data.details.live;
    });
    hls.on(Hls.Events.FRAG_BUFFERED, checkBuffer);
    hls.on(Hls.Events.ERROR, (_event, data) => {
      if (!data.fatal) return;
      if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
        if (data.response?.code === 401 || data.response?.code === 403) {
          show('This receiver link is invalid or has expired. Create a new connection on the relay.');
          return;
        }
        show('The relay is unavailable. Retrying automatically…');
        clearTimeout(retryTimer);
        retryTimer = setTimeout(() => {
          if (!hls || !alive) return;
          if (hls.levels.length === 0) hls.loadSource(url.href);
          else hls.startLoad();
        }, 2500);
      } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
        hls.recoverMediaError();
      } else {
        show('This browser could not decode the camera feed. Reload this source to retry.');
      }
    });
    hls.attachMedia(video);
    hls.loadSource(url.href);
  } else {
    show('Use an OBS build with Browser Source and H.264 playback support.');
  }
}

async function checkRun() {
  if (!alive || polling) return;
  polling = true;
  try {
    const url = new URL(`/api/sessions/${session}/status`, location.origin);
    url.searchParams.set('token', token);
    const response = await fetch(url, { cache: 'no-store' });
    if (!response.ok) return;
    const result = await response.json();
    if (typeof result.runId !== 'string') return;
    if (observedRun && result.runId !== observedRun && ended) {
      hls?.destroy();
      hls = null;
      video.removeAttribute('src');
      video.load();
      waiting = true;
      ended = false;
      connect();
    }
    observedRun = result.runId;
  } catch {
    // Playlist loading owns the visible connection state and retry policy.
  } finally {
    polling = false;
  }
}

video.addEventListener('waiting', () => {
  if (video.ended) return;
  waiting = true;
  video.pause();
  checkBuffer();
});
video.addEventListener('ended', () => show('Your phone has ended this stream.'));
play.addEventListener('click', beginPlayback);
window.addEventListener('pagehide', () => {
  alive = false;
  clearTimeout(retryTimer);
  clearInterval(bufferTimer);
  clearInterval(runTimer);
  hls?.destroy();
});
const bufferTimer = setInterval(checkBuffer, 500);
const runTimer = setInterval(checkRun, 3000);
document.querySelector('#badge').textContent = params.get('stats') === '1' ? `${delay}s recovery delay` : '';
if (!session || !/^[a-f0-9-]{36}$/i.test(session) || !token || !/^[a-zA-Z0-9_-]{32,128}$/.test(token)) {
  show('Paste the complete private receiver URL from your OpenStream-IRL pairing page into OBS.');
  clearInterval(bufferTimer);
  clearInterval(runTimer);
} else {
  connect();
  checkRun();
}
