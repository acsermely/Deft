// Platform Check tests: one handler per check item. Bridge calls go through call().
import { call } from '/_deft/v1/bridge.js';
import { log, step } from './log.js';

const $ = (id) => document.getElementById(id);
const on = (id, item, fn) => $(id).addEventListener('click', () => step(item, fn));

export function wire() {
  $('a1').addEventListener('change', (e) => {
    const files = [...e.target.files];
    log('File picker', files.length > 0, files.map((f) => `${f.name} (${f.size} B)`).join(', ') || 'no file');
  });
  $('a1').addEventListener('cancel', () => log('File picker', true, 'no file (cancelled, or the chooser did not open)'));
  on('a2', 'Download', async () => {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob(['a,b\n1,2\n'], { type: 'text/csv' }));
    a.download = 'platform-check.csv';
    document.body.appendChild(a); a.click(); a.remove();
    return 'save dialog requested';
  });
  on('a3', 'Vibrate', async () => `vibrate() returned ${navigator.vibrate([100, 50, 100])}`);
  on('a4f', 'Fullscreen', async () => {
    if (document.fullscreenElement) { await document.exitFullscreen(); return 'exited fullscreen'; }
    await document.documentElement.requestFullscreen();
    return 'fullscreen=' + !!document.fullscreenElement;
  });
  on('a4o', 'Landscape', async () => {
    await screen.orientation.lock('landscape');
    setTimeout(() => screen.orientation.unlock(), 3000);
    return 'locked landscape for 3 s';
  });
  on('a5', 'Wake lock', async () => {
    const s = await navigator.wakeLock.request('screen');
    return 'sentinel released=' + s.released;
  });
  on('a6', 'Share', async () => { await navigator.share({ title: 'Platform Check', text: 'hello from FL' }); return 'shared'; });
  on('a7', 'Paste', async () => JSON.stringify(await navigator.clipboard.readText()));
  on('a8', 'Print', async () => { window.print(); return 'print requested'; });
  on('a9d', 'DRM', async () => {
    const access = await navigator.requestMediaKeySystemAccess('com.widevine.alpha', [{
      initDataTypes: ['cenc'],
      videoCapabilities: [{ contentType: 'video/mp4; codecs="avc1.42E01E"' }],
    }]);
    await access.createMediaKeys();
    return 'widevine keys ' + access.keySystem;
  });
  on('a9m', 'MIDI', async () => {
    const midi = await navigator.requestMIDIAccess({ sysex: true });
    return `midi inputs=${midi.inputs.size} outputs=${midi.outputs.size}`;
  });
  let secure = false;
  on('f9', 'Secure', async () => {
    secure = !secure;
    await call('display.secure', { on: String(secure) });
    return 'secure=' + secure;
  });
  let torchOn = false;
  on('f4', 'Torch', async () => {
    torchOn = !torchOn;
    const r = await call('torch.set', { on: String(torchOn) });
    return 'on=' + r.on;
  });
  on('d5', 'Picture in picture', async () => {
    const r = await call('pip.enter', { aspect: '16:9' });
    return 'pip requested ' + JSON.stringify(r);
  });
  on('f3s', 'Speak', async () => {
    const r = await call('speech.speak', { text: 'Deft', lang: 'en-US' });
    return 'speaking id=' + r.id;
  });
  on('f3l', 'Listen', async () => {
    const r = await call('speech.recognize', { lang: 'en-US' });
    return 'heard ' + JSON.stringify(r.text);
  });
  on('f5v', 'Alarm volume', async () => {
    const before = await call('audio.getVolumes', {});
    const r = await call('audio.setVolume', { stream: 'alarm', value: String(before.alarm.value + 1) });
    return `alarm ${before.alarm.value} -> ${r.value} of ${r.max}`;
  });
  on('e2', 'Photo', async () => {
    const r = await call('intents.startForResult', { action: 'IMAGE_CAPTURE' });
    if (r.resultCode !== 'ok' || r.items.length === 0) return 'result ' + r.resultCode;
    const photo = await call('intents.readShare', { id: r.items[0].id });
    return `photo ${r.items[0].name} ${photo.mime} ${photo.base64.length} base64 chars`;
  });
}
