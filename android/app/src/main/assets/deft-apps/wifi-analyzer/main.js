// Lists nearby Wi-Fi networks through the bridge. Names are written with textContent only.
import '/_deft/v1/motion.js';
const $ = (id) => document.getElementById(id);

function setStatus(text) {
  $('status').textContent = text;
}

function showNetworks(rows) {
  const list = $('networks');
  list.replaceChildren();
  for (const row of rows) {
    const cell = document.createElement('div');
    cell.className = 'deft-cell';
    const body = document.createElement('span');
    body.className = 'deft-cell-body';
    const name = document.createElement('span');
    name.className = 'deft-body deft-cell-title';
    name.textContent = row.ssid ?? '(hidden network)';
    const detail = document.createElement('span');
    detail.className = 'deft-footnote deft-cell-sub';
    detail.textContent = `${row.security} ${row.band ?? ''} ${row.level} dBm`;
    body.append(name, detail);
    cell.append(body);
    list.append(cell);
  }
}

async function run(fn) {
  try {
    await fn();
  } catch (e) {
    setStatus((e && (e.code ? e.code + ': ' : '') + (e.message || e.name)) || String(e));
  }
}

$('scan').addEventListener('click', () => run(async () => {
  setStatus('Scanning...');
  const r = await window.deftBridge.invoke('wifi.scan', {});
  showNetworks(r.networks);
  setStatus(r.fresh ? `${r.networks.length} networks found` : `${r.networks.length} networks (last scan, Android limits scans)`);
}));

$('info').addEventListener('click', () => run(async () => {
  const r = await window.deftBridge.invoke('wifi.info', {});
  setStatus(r.ssid ? `Connected to ${r.ssid} at ${r.rssi} dBm, ${r.linkSpeed} Mbps` : 'Not connected to a Wi-Fi network');
}));

$('panel').addEventListener('click', () => run(async () => {
  await window.deftBridge.invoke('wifi.openPanel', {});
  setStatus('Wi-Fi settings opened');
}));

setStatus('Tap Scan to list networks');
