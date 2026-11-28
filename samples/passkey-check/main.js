// Passkey Check: passkeys.create, then passkeys.get, each checked here the way the website's server
// would check it: the challenge, the origin from passkeys.status, the rpId hash, the user-presence flags and, for
// sign-in, the signature against the public key saved at creation. Results go to #log and console ("PASSKEY ...").
import '/_deft/v1/motion.js';
import { call } from '/_deft/v1/bridge.js';

const RP = 'app.acsermely.hu';
// In the manifest, but that website has no opt-in list for this app: the host must refuse.
const UNLISTED_RP = 'acsermely.hu';
// Not in the manifest at all: the host must refuse before asking the website.
const UNDECLARED_RP = 'example.com';
const STORE = 'passkey-check.credential';

const $ = (id) => document.getElementById(id);
let facetOrigin = null;

function log(text) {
  $('log').textContent = text + '\n\n' + $('log').textContent;
  console.log('PASSKEY ' + text);
}
async function step(name, fn) {
  try {
    await fn();
  } catch (e) {
    log(`FAIL ${name}: ${(e && (e.code ? e.code + ': ' : '') + e.message) || String(e)}`);
  }
}

const b64u = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const fromB64u = (text) => Uint8Array.from(atob(text.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((text.length + 3) % 4)), (c) => c.charCodeAt(0));
const random = (n) => crypto.getRandomValues(new Uint8Array(n));
const sha256 = async (bytes) => new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
const same = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);

/** The checks a server makes on clientDataJSON and authenticatorData. Throws on the first mismatch. */
async function checkCommon(kind, challenge, clientDataB64, authData) {
  const client = JSON.parse(new TextDecoder().decode(fromB64u(clientDataB64)));
  if (client.type !== kind) throw new Error(`clientData.type is ${client.type}, expected ${kind}`);
  if (client.challenge !== challenge) throw new Error('challenge does not match');
  if (client.origin !== facetOrigin) throw new Error(`origin is ${client.origin}, expected ${facetOrigin}`);
  if (!same(authData.slice(0, 32), await sha256(new TextEncoder().encode(RP)))) throw new Error('rpIdHash is not SHA-256(' + RP + ')');
  const flags = authData[32];
  if (!(flags & 0x01)) throw new Error('user was not present (UP flag off)');
  return { client, uv: Boolean(flags & 0x04), counter: new DataView(authData.buffer, authData.byteOffset + 33, 4).getUint32(0) };
}

/** A DER ECDSA signature (what authenticators send) as the raw r||s WebCrypto wants. */
function derToRaw(der) {
  let i = 2;
  const part = () => {
    const len = der[i + 1];
    let v = der.slice(i + 2, i + 2 + len);
    i += 2 + len;
    while (v.length > 32 && v[0] === 0) v = v.slice(1);
    const out = new Uint8Array(32);
    out.set(v, 32 - v.length);
    return out;
  };
  const r = part();
  const s = part();
  return Uint8Array.from([...r, ...s]);
}

async function verifySignature(saved, authData, clientDataB64, signatureB64) {
  const signed = Uint8Array.from([...authData, ...(await sha256(fromB64u(clientDataB64)))]);
  const spki = fromB64u(saved.publicKey);
  const signature = fromB64u(signatureB64);
  if (saved.alg === -7) {
    const key = await crypto.subtle.importKey('spki', spki, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    return crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, derToRaw(signature), signed);
  }
  if (saved.alg === -257) {
    const key = await crypto.subtle.importKey('spki', spki, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
    return crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, signature, signed);
  }
  throw new Error(`cannot check algorithm ${saved.alg}`);
}

async function expectRefused(name, rpId) {
  try {
    await call('passkeys.get', { requestJson: JSON.stringify({ challenge: b64u(random(32)), rpId, userVerification: 'preferred' }) });
    log(`FAIL ${name}: the call went through, but ${rpId} should be refused`);
  } catch (e) {
    if (e && e.code === 'not_granted') log(`PASS ${name}: refused (not_granted): ${e.message}`);
    else log(`FAIL ${name}: expected not_granted, got ${(e && e.code) || ''} ${(e && e.message) || e}`);
  }
}

$('create').addEventListener('click', () => step('create', async () => {
  const challenge = b64u(random(32));
  const options = {
    challenge,
    rp: { id: RP, name: 'Passkey Check' },
    user: { id: b64u(random(16)), name: 'passkey-check', displayName: 'Passkey Check test' },
    pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
    authenticatorSelection: { residentKey: 'required', userVerification: 'required' },
    attestation: 'none',
    timeout: 120000,
  };
  const { responseJson } = await call('passkeys.create', { requestJson: JSON.stringify(options) });
  const cred = JSON.parse(responseJson);
  const r = cred.response || {};
  if (!r.authenticatorData) throw new Error('the response has no authenticatorData; cannot check it here');
  const { uv } = await checkCommon('webauthn.create', challenge, r.clientDataJSON, fromB64u(r.authenticatorData));
  if (!r.publicKey) {
    log(`PASS create (partly): passkey ${cred.id} made and checked, but the response has no publicKey, so sign-in can only check the challenge and origin`);
  } else {
    log(`PASS create: passkey ${cred.id}\nalgorithm ${r.publicKeyAlgorithm}, user verified: ${uv}, origin ${facetOrigin}`);
  }
  localStorage.setItem(STORE, JSON.stringify({ id: cred.id, publicKey: r.publicKey || null, alg: r.publicKeyAlgorithm }));
}));

$('signin').addEventListener('click', () => step('sign in', async () => {
  const saved = JSON.parse(localStorage.getItem(STORE) || 'null');
  const challenge = b64u(random(32));
  const options = {
    challenge,
    rpId: RP,
    userVerification: 'required',
    timeout: 120000,
    allowCredentials: saved ? [{ type: 'public-key', id: saved.id }] : [],
  };
  const { responseJson } = await call('passkeys.get', { requestJson: JSON.stringify(options) });
  const cred = JSON.parse(responseJson);
  const r = cred.response || {};
  const authData = fromB64u(r.authenticatorData);
  const { uv, counter } = await checkCommon('webauthn.get', challenge, r.clientDataJSON, authData);
  if (!saved || saved.id !== cred.id || !saved.publicKey) {
    log(`PASS sign in (partly): passkey ${cred.id} answered with the right challenge and origin; no saved public key to check its signature`);
    return;
  }
  const ok = await verifySignature(saved, authData, r.clientDataJSON, r.signature);
  if (!ok) throw new Error('signature does not verify with the saved public key');
  log(`PASS sign in: signature verified with the public key from step 1\nuser verified: ${uv}, counter ${counter}`);
}));

$('unlisted').addEventListener('click', () => step('unlisted website', () => expectRefused('unlisted website', UNLISTED_RP)));
$('undeclared').addEventListener('click', () => step('website not in manifest', () => expectRefused('website not in manifest', UNDECLARED_RP)));

$('forget').addEventListener('click', () => {
  localStorage.removeItem(STORE);
  log('forgot the saved passkey id and public key (the passkey itself stays in your passkey provider)');
});

step('status', async () => {
  const status = await call('passkeys.status', {});
  facetOrigin = status.origin;
  $('about').textContent = `Origin id: ${location.hostname.split('.')[0]}\nPasskeys supported: ${status.supported}\nOrigin the website sees: ${status.origin}`;
  log(status.supported ? 'ready' : 'FAIL status: this phone does not support passkeys here (Android 14 or newer is needed)');
});
