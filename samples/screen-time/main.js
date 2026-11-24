// Lists the apps on the phone, opens one, and shows today's foreground time per app.
// Names and packages are written with textContent only.
import '/_deft/v1/motion.js';
import { showRows } from './rows.js';
const $ = (id) => document.getElementById(id);

function setStatus(text) {
  $('status').textContent = text;
}

async function run(fn) {
  try {
    await fn();
  } catch (e) {
    setStatus((e && (e.code ? e.code + ': ' : '') + (e.message || e.name)) || String(e));
  }
}

$('apps').addEventListener('click', () => run(async () => {
  setStatus('Loading apps...');
  const apps = await window.deftBridge.invoke('apps.list', {});
  showRows($('rows'), apps.map((app) => ({
    name: app.label,
    detail: app.system ? 'system' : app.package,
    onTap: async () => {
      await window.deftBridge.invoke('apps.launch', { package: app.package });
      setStatus('Opened ' + app.label);
    },
  })), run);
  setStatus(`${apps.length} apps. Tap one to open it.`);
}));

$('usage').addEventListener('click', () => run(async () => {
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  setStatus('Loading usage...');
  const rows = await window.deftBridge.invoke('usage.stats', {
    sinceMs: String(start.getTime()),
    untilMs: String(Date.now()),
  });
  showRows($('rows'), rows.map((row) => ({
    name: row.package,
    detail: `${Math.round(row.foregroundMs / 60000)} min`,
  })), run);
  setStatus(rows.length ? 'Time in the foreground since midnight' : 'No app use since midnight');
}));

$('device').addEventListener('click', () => run(async () => {
  const id = await window.deftBridge.invoke('device.id', {});
  setStatus('Device id for this app: ' + id);
}));
