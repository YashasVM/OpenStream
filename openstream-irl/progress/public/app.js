export const labels = {
  implemented: 'Implemented',
  'in-progress': 'In progress',
  pending: 'Pending',
};

export function summarize(data) {
  if (!data || !/^\d{4}-\d{2}-\d{2}$/.test(data.updated) || typeof data.summary !== 'string' || !Array.isArray(data.items)) {
    throw new Error('Invalid status data');
  }
  const counts = { implemented: 0, 'in-progress': 0, pending: 0, 'not-device-tested': 0 };
  for (const item of data.items) {
    if (!item || !Object.hasOwn(labels, item.status) || typeof item.title !== 'string' || typeof item.detail !== 'string' || typeof item.deviceTested !== 'boolean') {
      throw new Error('Invalid checklist item');
    }
    counts[item.status]++;
    if (!item.deviceTested) counts['not-device-tested']++;
  }
  return counts;
}

function element(tag, text, className) {
  const node = document.createElement(tag);
  node.textContent = text;
  if (className) node.className = className;
  return node;
}

export async function loadStatus() {
  const message = document.getElementById('load-status');
  try {
    const response = await fetch('/irl/status.json', { cache: 'no-store' });
    if (!response.ok) throw new Error('Status request failed');
    const data = await response.json();
    const counts = summarize(data);
    const totals = document.getElementById('counts');
    for (const [status, count] of Object.entries(counts)) {
      const entry = document.createElement('div');
      entry.append(element('dt', labels[status] || 'Not device tested'), element('dd', String(count)));
      totals.append(entry);
    }
    const list = document.getElementById('items');
    for (const item of data.items) {
      const row = document.createElement('li');
      const description = document.createElement('div');
      description.append(element('h3', item.title), element('p', item.detail));
      const badges = element('div', '', 'labels');
      badges.append(element('span', labels[item.status], `badge ${item.status}`), element('p', item.deviceTested ? 'Device tested' : 'Not device tested', 'device'));
      row.append(description, badges);
      list.append(row);
    }
    document.getElementById('summary').textContent = data.summary;
    const date = document.getElementById('updated');
    date.textContent = data.updated;
    date.dateTime = data.updated;
    totals.hidden = false;
    message.textContent = `${data.items.length} tracked items`;
  } catch {
    message.textContent = 'Status data could not be loaded. Reload this page or read the status JSON below.';
    document.getElementById('updated').textContent = 'unavailable';
  }
}

if (typeof document !== 'undefined') loadStatus();
