// LAN Finder: lists _http._tcp services on the Wi-Fi, then opens a raw TCP connection to one
// the user taps and asks it for "/". Only LAN addresses are reachable with net.local.
import '/_deft/v1/motion.js';
import { call, on } from '/_deft/v1/bridge.js';
import { showRows } from './rows.js';
const SERVICE_TYPE = '_http._tcp';
const $ = (id) => document.getElementById(id);
const found = new Map();
let socketId = null;
let decoder = new TextDecoder();

function log(line) {
  $('log').textContent = line + '\n' + $('log').textContent;
}

function render() {
  showRows($('found'), [...found.keys()], fetchRoot);
  $('status').textContent = found.size ? 'Tap a device to fetch its home page.' : 'Nothing found yet.';
}

function decodeChunk(b64) {
  const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  return decoder.decode(bytes, { stream: true });
}

async function fetchRoot(name) {
  try {
    if (socketId) await call('net.close', { socketId });
    socketId = null;
    $('body').textContent = '';
    const target = await call('net.resolve', { name, type: SERVICE_TYPE });
    log(`resolved ${name} to ${target.host}:${target.port}`);
    const conn = await call('net.tcpConnect', {
      host: target.host,
      port: String(target.port),
      timeoutMs: '5000',
    });
    socketId = conn.socketId;
    const request = 'GET / HTTP/1.0\r\nHost: ' + target.host + '\r\nConnection: close\r\n\r\n';
    await call('net.tcpSend', { socketId, base64: btoa(request) });
  } catch (e) {
    log('FAIL ' + (e && e.message ? e.message : String(e)));
  }
}

on('net.found', (p) => { found.set(p.name, true); render(); });

on('net.lost', (p) => { found.delete(p.name); render(); });

on('net.data', (p) => {
  if (p.socketId !== socketId) return;
  $('body').textContent += decodeChunk(p.base64);
});

on('net.closed', (p) => {
  if (p.socketId !== socketId) return;
  socketId = null;
  $('body').textContent += decoder.decode();
  log('connection closed');
});

$('find').addEventListener('click', async () => {
  try {
    found.clear();
    render();
    await call('net.discover', { type: SERVICE_TYPE });
    $('status').textContent = 'Searching the Wi-Fi...';
  } catch (e) {
    log('FAIL ' + (e && e.message ? e.message : String(e)));
  }
});

$('stop').addEventListener('click', async () => {
  await call('net.discoverStop', {});
  $('status').textContent = 'Search stopped.';
});
