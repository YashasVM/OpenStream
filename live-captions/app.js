const $ = id => document.getElementById(id);
const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
let wanted = false;
let timer;
let history = '';
const recognition = Recognition ? new Recognition() : null;
function controls() {
  $('toggle').textContent = wanted ? 'Stop microphone' : 'Start microphone';
  $('language').disabled = wanted;
}
function start() {
  if (!wanted) return;
  try { recognition.lang = $('language').value; recognition.start(); }
  catch (error) { wanted = false; controls(); $('status').textContent = error.message; }
}
if (recognition) {
  recognition.continuous = true;
  recognition.interimResults = true;
  recognition.onstart = () => { $('status').textContent = 'Listening'; };
  recognition.onresult = event => {
    let interim = '';
    for (let i = event.resultIndex; i < event.results.length; i++) {
      if (event.results[i].isFinal) history = (history + ' ' + event.results[i][0].transcript).trim().split(/\s+/).slice(-55).join(' ');
      else interim += event.results[i][0].transcript;
    }
    $('final').textContent = history;
    $('interim').textContent = interim;
  };
  recognition.onerror = event => {
    if (event.error === 'no-speech') return;
    wanted = false;
    controls();
    $('status').textContent = ({'not-allowed':'Microphone permission denied. Allow access in browser settings.', 'audio-capture':'No microphone available. Check your input device.', network:'Speech service unavailable. Check your internet connection.'})[event.error] || 'Speech recognition error: ' + event.error;
  };
  recognition.onend = () => {
    $('interim').textContent = '';
    if (wanted) { $('status').textContent = 'Reconnecting'; timer = setTimeout(start, 400); }
  };
} else {
  $('status').textContent = 'This browser cannot transcribe speech.';
  $('toggle').textContent = 'Speech recognition unavailable';
  const help = document.createElement('p');
  help.setAttribute('role', 'alert');
  help.textContent = 'Open http://localhost:8787 in Google Chrome. This browser does not expose the speech recognition feature this app needs, so the microphone cannot start. Chromium builds and embedded preview browsers may lack this service even when microphone access works.';
  $('toggle').closest('nav').after(help);
  $('toggle').disabled = true;
}
$('toggle').onclick = () => {
  wanted = !wanted; controls(); clearTimeout(timer);
  if (wanted) { $('status').textContent = 'Starting microphone'; start(); }
  else { recognition.abort(); $('status').textContent = 'Stopped'; }
};
$('clear').onclick = () => { history = ''; $('final').textContent = ''; $('interim').textContent = ''; };
$('fullscreen').onclick = async () => {
  try { if (document.fullscreenElement) await document.exitFullscreen(); else await document.documentElement.requestFullscreen(); }
  catch { $('status').textContent = 'Full screen unavailable in this browser.'; }
};
window.addEventListener('pagehide', () => { wanted = false; clearTimeout(timer); recognition?.abort(); });
