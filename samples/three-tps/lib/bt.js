// BLE duel link — host advertises a GATT service, guest scans/connects.
// Shapes matched to the working Mesh app (fully-qualified methods, base64 payloads).
const SVC  = '0000f1ee-0000-1000-8000-00805f9b34fb';
const CH   = '0000f1e1-0000-1000-8000-00805f9b34fb';

let bridge = null;
let role = null;            // 'host' | 'guest'
let guestDeviceId = null;   // host side: connected guest
let msgHandler = null;
let onStatus = null;
let scanOff = null;

function b64(str){
  const bytes = new TextEncoder().encode(str);
  let bin = ''; bytes.forEach(b => bin += String.fromCharCode(b));
  return btoa(bin);
}
function fromB64(s){
  const bin = atob(s || '');
  return new TextDecoder().decode(Uint8Array.from(bin, c => c.charCodeAt(0)));
}

function init(b, handlers){
  bridge = b; // may be null when running outside the launcher
  msgHandler = handlers.onMessage;
  onStatus = handlers.onStatus;
  if (!bridge) return; // nothing to subscribe to without the JS bridge

  // host: guest connections (track deviceId for notify)
  bridge.subscribe('bluetooth.bleServerConnection', p => {
    if (!p || !p.deviceId) return;
    if (p.connected){ guestDeviceId = p.deviceId; emitStatus('guest connected'); }
    else { guestDeviceId = null; emitStatus('guest disconnected'); }
  });
  // guest: dropped connection
  bridge.subscribe('bluetooth.bleConnection', p => {
    if (p && !p.connected && role === 'guest') emitStatus('lost connection');
  });
  // host: incoming writes from guest
  bridge.subscribe('bluetooth.bleServerWriteRequest', async p => {
    if (p && p.base64) deliver(fromB64(p.base64));
    if (p && p.responseNeeded && p.requestId){
      try { await bridge.invoke('bluetooth.bleServerRespond', { requestId: p.requestId, status: 0 }); } catch(e){}
    }
  });
  // guest: incoming notify from host
  bridge.subscribe('bluetooth.bleCharacteristic', p => {
    if (p && p.base64) deliver(fromB64(p.base64));
  });
}

function emitStatus(t){ if (onStatus) onStatus(t); }
function deliver(raw){
  try {
    const obj = JSON.parse(raw);
    if (msgHandler) msgHandler(obj);
  } catch(e){ /* ignore malformed */ }
}

// host: enable BT, register service, advertise
async function startHost(){
  role = 'host';
  const on = await bridge.invoke('bluetooth.bleEnable', {});
  if (on && on.enabled === false) throw new Error('Bluetooth is off — turn it on');
  await bridge.invoke('bluetooth.bleServerClear', {});
  await bridge.invoke('bluetooth.bleServerAddService', {
    uuid: SVC,
    type: 'primary',
    characteristics: [{
      uuid: CH,
      properties: ['read','write','writeNoResponse','notify'],
      permissions: ['read','write'],
    }],
  });
  await new Promise(r => setTimeout(r, 400)); // let the GATT server settle
  await bridge.invoke('bluetooth.bleAdvertiseStart', {
    serviceUuids: [SVC],
    connectable: true,
    includeDeviceName: false,
  });
  emitStatus('advertising — waiting for guest…');
  return role;
}

// guest: scan for a host advertising SVC; returns unsubscribe fn
function scanForHost(onResult){
  bridge.invoke('bluetooth.bleScanStart', {
    filters: [{ services: [SVC] }],
    options: { scanMode: 'lowLatency' },
  }).catch(e => emitStatus('scan failed: ' + e.message));
  scanOff = bridge.subscribe('bluetooth.bleScanResult', p => {
    if (!p || p.error || !p.deviceId) return;
    const uuids = (p.serviceUuids || []).map(u => String(u).toLowerCase());
    if (uuids.length && !uuids.includes(SVC)) return;
    const name = p.name || (p.device && p.device.name) || '';
    onResult({ deviceId: p.deviceId, name });
  });
  return () => { if (scanOff){ scanOff(); scanOff = null; } };
}
async function stopScan(){
  try { await bridge.invoke('bluetooth.bleScanStop', {}); } catch(e){}
}

// guest: connect to host and subscribe to notifications
async function connectGuest(deviceId){
  role = 'guest';
  guestDeviceId = deviceId;
  await bridge.invoke('bluetooth.bleConnect', { deviceId });
  await bridge.invoke('bluetooth.bleDiscover', { deviceId });
  await bridge.invoke('bluetooth.bleNotifyStart', { deviceId, service: SVC, characteristic: CH });
  emitStatus('connected to host');
  return role;
}

// send a JSON message over the link (either role)
async function send(obj){
  const encoded = b64(JSON.stringify(obj));
  try {
    if (role === 'host' && guestDeviceId){
      await bridge.invoke('bluetooth.bleServerNotify', {
        deviceId: guestDeviceId, service: SVC, characteristic: CH, base64: encoded,
      });
    } else if (role === 'guest' && guestDeviceId){
      await bridge.invoke('bluetooth.bleWrite', {
        deviceId: guestDeviceId, service: SVC, characteristic: CH, base64: encoded, writeType: 'noResponse',
      });
    }
  } catch(e){ emitStatus('send failed'); }
}

export default { init, startHost, scanForHost, stopScan, connectGuest, send };
