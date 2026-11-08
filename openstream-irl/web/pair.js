const form = document.querySelector('#create-form');
const message = document.querySelector('#message');
const createButton = document.querySelector('#create');
const pairing = document.querySelector('#pairing');
const receiver = document.querySelector('#receiver');
const delay = document.querySelector('#delay');
let connection = null;
let pollTimer = null;

function updateReceiver() {
  document.querySelector('#delay-value').textContent = `${delay.value} seconds`;
  if (!connection) return;
  const url = new URL('/watch.html', location.origin);
  url.hash = new URLSearchParams({ session: connection.sessionId, token: connection.viewToken, delay: delay.value }).toString();
  receiver.value = url.href;
  document.querySelector('#preview').href = url.href;
}

async function copy(input, button) {
  try {
    await navigator.clipboard.writeText(input.value);
    const label = button.textContent;
    button.textContent = 'Copied';
    setTimeout(() => { button.textContent = label; }, 1800);
  } catch {
    input.type = 'text';
    input.focus();
    input.select();
    message.textContent = 'Select and copy the highlighted link. Hide it before sharing your screen.';
  }
}

async function poll() {
  if (!connection) return;
  try {
    const url = new URL(`/api/sessions/${connection.sessionId}/status`, location.origin);
    url.searchParams.set('token', connection.viewToken);
    const response = await fetch(url, { cache: 'no-store' });
    if (!response.ok) throw new Error('Relay unavailable');
    const status = await response.json();
    const age = status.lastUploadAt ? Math.max(0, Math.round((Date.now() - status.lastUploadAt) / 1000)) : null;
    document.querySelector('#feed-status').textContent = age === null ? 'Waiting for your phone.' : `${status.segmentCount} chunks available. Last upload ${age} seconds ago.`;
  } catch {
    document.querySelector('#feed-status').textContent = 'Cannot reach the relay. Retrying automatically.';
  }
  pollTimer = setTimeout(poll, 4000);
}

form.addEventListener('submit', async event => {
  event.preventDefault();
  createButton.disabled = true;
  message.textContent = 'Creating your private camera connection…';
  try {
    const response = await fetch('/api/sessions', { method: 'POST', headers: { Authorization: `Bearer ${document.querySelector('#admin-token').value.trim()}` } });
    if (!response.ok) throw new Error(response.status === 401 || response.status === 403 ? 'Check the relay access key and try again.' : 'The relay could not create a connection. Try again.');
    const result = await response.json();
    if (typeof result.sessionId !== 'string' || !/^[a-f0-9-]{36}$/i.test(result.sessionId) || typeof result.publishToken !== 'string' || typeof result.viewToken !== 'string') throw new Error('The relay returned an invalid connection.');
    connection = result;
    const url = new URL('openstreamirl://pair');
    url.search = new URLSearchParams({ relay: location.origin, session: result.sessionId, token: result.publishToken }).toString();
    pairing.value = url.href;
    document.querySelector('#open-phone').href = url.href;
    document.querySelector('#admin-token').value = '';
    document.querySelector('#connection').hidden = false;
    updateReceiver();
    message.textContent = 'Connection created. Save the pairing link and OBS URL before closing this tab.';
    clearTimeout(pollTimer);
    poll();
  } catch (error) {
    message.textContent = error instanceof Error ? error.message : 'Could not connect to the relay.';
  } finally {
    createButton.disabled = false;
  }
});
delay.addEventListener('input', updateReceiver);
document.querySelector('#copy-pairing').addEventListener('click', event => copy(pairing, event.currentTarget));
document.querySelector('#copy-receiver').addEventListener('click', event => copy(receiver, event.currentTarget));
updateReceiver();
