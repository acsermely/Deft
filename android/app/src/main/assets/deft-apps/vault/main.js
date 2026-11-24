// Vault: the secret is encrypted with an AES key that the phone keeps and that needs a fingerprint.
// Only the ciphertext and IV are stored here (localStorage). Results go to #log and console ("VAULT ...").
import '/_deft/v1/motion.js';
import { call } from '/_deft/v1/bridge.js';
const $ = (id) => document.getElementById(id);
const KEY = 'vault';
const STORE = 'vault.secret';

function log(text) {
  $('log').textContent = text + '\n' + $('log').textContent;
  console.log('VAULT ' + text);
}
async function step(fn) {
  try { await fn(); } catch (e) { log('FAIL ' + ((e && (e.name + ': ' + e.message)) || String(e))); }
}
const toBase64 = (bytes) => btoa(String.fromCharCode(...bytes));
const fromBase64 = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0));

async function ensureKey() {
  const names = await call('keystore.list', {});
  if (!names.includes(KEY)) {
    await call('keystore.generate', { alias: KEY, alg: 'AES_256', requireBiometric: 'true' });
  }
}

$('save').addEventListener('click', () => step(async () => {
  const text = $('secret').value;
  if (!text) throw new Error('type a secret first');
  await ensureKey();
  const sealed = await call('keystore.encrypt', { alias: KEY, base64: toBase64(new TextEncoder().encode(text)) });
  localStorage.setItem(STORE, JSON.stringify(sealed));
  $('secret').value = '';
  $('shown').textContent = '';
  log('saved: encrypted with a fingerprint-locked key');
}));

$('reveal').addEventListener('click', () => step(async () => {
  const sealed = JSON.parse(localStorage.getItem(STORE) || 'null');
  if (!sealed) throw new Error('nothing saved yet');
  const r = await call('keystore.decrypt', { alias: KEY, iv: sealed.iv, ciphertext: sealed.ciphertext });
  $('shown').textContent = new TextDecoder().decode(fromBase64(r.base64));
  log('revealed after a fingerprint check');
}));

$('forget').addEventListener('click', () => step(async () => {
  await call('keystore.delete', { alias: KEY });
  localStorage.removeItem(STORE);
  $('shown').textContent = '';
  log('key deleted; the saved secret can no longer be opened');
}));

log('ready');
