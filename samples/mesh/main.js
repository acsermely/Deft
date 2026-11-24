// Mesh: nearby BLE chat. Packets flood between phones over one service; logic is the legacy page's.
import '/_deft/v1/motion.js';
import { ready, call, on, inLauncher } from '/_deft/v1/bridge.js';

const SVC = '0000f1ee-0000-1000-8000-00805f9b34fb';
const CH = '0000f1e1-0000-1000-8000-00805f9b34fb';
const TTL = 7;
const MAX_LINKS = 6;
const $ = (id) => document.getElementById(id);
const errEl = $('err');
const logEl = $('log');
const badge = $('badge');
const liveBtn = $('live');
const inbound = new Set();
const outbound = new Set();
const connecting = new Set();
const seen = new Set();
let wantLive = false;
let reliveTimer = 0;
let going = false;

function el(tag, cls, text) {
  const node = document.createElement(tag);
  node.className = cls;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}
function emptyState(iconName, title, text) {
  const box = el('div', 'deft-empty');
  const ic = el('deft-icon');
  ic.setAttribute('name', iconName);
  ic.setAttribute('aria-hidden', 'true');
  box.append(ic, el('h2', 'deft-title3', title), el('p', 'deft-subhead', text));
  return box;
}
function err(m) {
  errEl.querySelector('span').textContent = m || '';
  errEl.hidden = !m;
}
const nick = () => ($('nick').value || 'anon').slice(0, 24);
const timeOf = (ms) => new Date(ms).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });

async function awaitBridge() {
  if (!inLauncher()) return false;
  try { await ready(); return true; } catch (e) { return false; }
}
function b64(str) {
  const bytes = new TextEncoder().encode(str);
  let bin = '';
  bytes.forEach((b) => { bin += String.fromCharCode(b); });
  return btoa(bin);
}
function fromB64(s) {
  const bin = atob(s || '');
  return new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)));
}
function pktId() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
}
function remember(id) {
  seen.add(id);
  if (seen.size > 250) seen.delete(seen.values().next().value);
}
function setStatus() {
  if (!wantLive) {
    badge.textContent = 'Off air';
    badge.toggleAttribute('data-off', true);
    liveBtn.textContent = 'Live';
    return;
  }
  const n = new Set([...inbound, ...outbound]).size;
  badge.textContent = n ? (n + ' nearby') : 'Listening';
  badge.toggleAttribute('data-off', false);
  liveBtn.textContent = 'Stop';
}
// The chat log lives in sessionStorage so it survives a tab switch.
function readLog() {
  try { return JSON.parse(sessionStorage.getItem('meshLog') || '[]') || []; } catch (e) { return []; }
}
function bubble(from, text, mine, at) {
  const div = el('div', 'bubble' + (mine ? ' me' : ''));
  const meta = el('div', 'deft-caption bubble-meta', from + (at ? ' · ' + timeOf(at) : ''));
  div.append(meta, document.createTextNode(text));
  return div;
}
function addMsg(from, text, mine) {
  const rows = readLog();
  rows.push({ from, text, mine, at: Date.now() });
  sessionStorage.setItem('meshLog', JSON.stringify(rows.slice(-80)));
  logEl.appendChild(bubble(from, text, mine, Date.now()));
  logEl.scrollTop = logEl.scrollHeight;
}
function restoreLog() {
  const rows = readLog();
  if (!rows.length) {
    logEl.replaceChildren(emptyState('wifi', 'Nearby chat', 'Go live. Nearby radios relay short messages with no internet.'));
    return;
  }
  logEl.replaceChildren(...rows.map((r) => bubble(r.from, r.text, r.mine, r.at)));
}
function parsePkt(raw) {
  let o;
  try { o = JSON.parse(raw); } catch (e) { return null; }
  if (!o || (o.t ?? null) === null) return null;
  return {
    v: 1,
    id: o.id || (String(o.n || '') + '\0' + String(o.t)),
    ttl: Math.max(0, Number(o.ttl ?? TTL) || 0),
    n: String(o.n || 'peer').slice(0, 24),
    t: String(o.t).slice(0, 80),
  };
}
async function flood(pkt, exceptId) {
  const encoded = b64(JSON.stringify(pkt));
  const skips = exceptId ? new Set([exceptId]) : new Set();
  for (const id of outbound) {
    if (skips.has(id)) continue;
    try {
      await call('bluetooth.bleWrite', {
        deviceId: id, service: SVC, characteristic: CH, base64: encoded, writeType: 'noResponse',
      });
    } catch (e) { /* drop */ }
  }
  for (const id of inbound) {
    if (skips.has(id)) continue;
    try {
      await call('bluetooth.bleServerNotify', {
        deviceId: id, service: SVC, characteristic: CH, base64: encoded,
      });
    } catch (e) { /* drop */ }
  }
}
async function handlePkt(raw, fromId) {
  let pkt;
  try { pkt = parsePkt(raw); } catch (e) { return; }
  if (!pkt || seen.has(pkt.id)) return;
  remember(pkt.id);
  if (pkt.n !== nick()) {
    if (logEl.querySelector('.deft-empty')) logEl.replaceChildren();
    addMsg(pkt.n, pkt.t, false);
  }
  if (!wantLive || pkt.ttl <= 1) return;
  await flood({ v: 1, id: pkt.id, ttl: pkt.ttl - 1, n: pkt.n, t: pkt.t }, fromId);
}
async function link(id) {
  if (!wantLive || outbound.has(id) || connecting.has(id) || inbound.has(id)) return;
  if (outbound.size >= MAX_LINKS) return;
  connecting.add(id);
  try {
    await call('bluetooth.bleConnect', { deviceId: id });
    await call('bluetooth.bleDiscover', { deviceId: id });
    await call('bluetooth.bleNotifyStart', { deviceId: id, service: SVC, characteristic: CH });
    outbound.add(id);
    setStatus();
  } catch (e) { /* radio busy */ }
  connecting.delete(id);
}
function scheduleRelive(reason) {
  if (!wantLive) return;
  err(reason);
  clearTimeout(reliveTimer);
  reliveTimer = setTimeout(() => { goLive(); }, 900);
}
async function goLive() {
  wantLive = true;
  if (going) return;
  going = true;
  err('');
  try {
    const enable = await call('bluetooth.bleEnable', {});
    if (enable && enable.enabled === false) {
      err('Turn Bluetooth on, then tap Live');
      wantLive = false;
      setStatus();
      return;
    }
    await call('bluetooth.bleServerClear', {});
    await call('bluetooth.bleServerAddService', {
      uuid: SVC,
      type: 'primary',
      characteristics: [{
        uuid: CH,
        properties: ['read', 'write', 'writeNoResponse', 'notify'],
        permissions: ['read', 'write'],
      }],
    });
    await new Promise((r) => setTimeout(r, 400));
    await call('bluetooth.bleAdvertiseStart', {
      serviceUuids: [SVC],
      connectable: true,
      includeDeviceName: false,
    });
    await call('bluetooth.bleScanStart', {
      filters: [{ services: [SVC] }],
      options: { scanMode: 'lowLatency' },
    });
    setStatus();
  } catch (e) { err(e.message); wantLive = false; setStatus(); }
  finally { going = false; }
}
async function stopLive() {
  wantLive = false;
  clearTimeout(reliveTimer);
  err('');
  try {
    await call('bluetooth.bleScanStop', {});
    await call('bluetooth.bleAdvertiseStop', {});
    for (const id of [...outbound]) {
      try { await call('bluetooth.bleDisconnect', { deviceId: id }); } catch (e) { /* gone */ }
    }
  } catch (e) { err(e.message); }
  outbound.clear();
  inbound.clear();
  connecting.clear();
  setStatus();
}
async function send() {
  const text = $('text');
  const t = text.value.trim().slice(0, 80);
  if (!t) return;
  if (!wantLive) { err('Go live first'); return; }
  const pkt = { v: 1, id: pktId(), ttl: TTL, n: nick(), t };
  remember(pkt.id);
  err('');
  if (logEl.querySelector('.deft-empty')) logEl.replaceChildren();
  addMsg(pkt.n, pkt.t, true);
  text.value = '';
  await flood(pkt, null);
  if (inbound.size === 0 && outbound.size === 0) {
    err('On air, but nobody is in range yet.');
  }
}

liveBtn.addEventListener('click', () => (wantLive ? stopLive() : goLive()));
$('send').addEventListener('click', send);
$('text').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { e.preventDefault(); send(); }
});

(async () => {
  restoreLog();
  setStatus();
  if (!(await awaitBridge())) {
    err('Open in Deft');
    return;
  }
  on('bluetooth.bleScanResult', (p) => {
    if (!wantLive || !p || p.error || !p.deviceId) return;
    const uuids = (p.serviceUuids || []).map((u) => String(u).toLowerCase());
    if (uuids.length && !uuids.includes(SVC)) return;
    link(p.deviceId);
  });
  on('bluetooth.bleCharacteristic', (p) => {
    if (p && p.base64) handlePkt(fromB64(p.base64), p.deviceId);
  });
  on('bluetooth.bleServerWriteRequest', async (p) => {
    if (p && p.base64) handlePkt(fromB64(p.base64), p.deviceId);
    if (p && p.responseNeeded && p.requestId) {
      try { await call('bluetooth.bleServerRespond', { requestId: p.requestId, status: 0 }); }
      catch (e) { err(e.message); }
    }
  });
  on('bluetooth.bleServerConnection', (p) => {
    if (!p || !p.deviceId) return;
    if (p.connected) inbound.add(p.deviceId);
    else inbound.delete(p.deviceId);
    setStatus();
  });
  on('bluetooth.bleConnection', (p) => {
    if (!p || !p.deviceId) return;
    if (!p.connected) {
      outbound.delete(p.deviceId);
      connecting.delete(p.deviceId);
      setStatus();
    }
  });
  on('bluetooth.bleAdapter', (p) => {
    if (!p || !wantLive) return;
    if (p.enabled === false) {
      err('Bluetooth radio reset. Waiting…');
      outbound.clear();
      inbound.clear();
      setStatus();
      return;
    }
    scheduleRelive('Bluetooth back — restarting…');
  });
})();
