// Run Tracker recorder: starts and stops the foreground service and the location watch, and adds
// each accepted fix to the track. Fixes and the service stop arrive as bridge events (see main.js).
import { call } from '/_deft/v1/bridge.js';
import { load, save, metres, totalMetres } from './track.js';
import { draw, show, log, showNotice } from './view.js';

const MAX_ACCURACY_M = 50;
const MAX_POINTS = 5000;

export const state = { track: load(), watchId: null, running: false };

// The bridge object exists only inside the launcher; outside it the app shows a hint.
export const hasBridge = () => !!window.deftBridge;

function persist() {
  try { save(state.track); } catch (e) { log('save FAIL ' + e.message); }
}

export function onFix(p) {
  if (!p || p.watchId !== state.watchId) return;
  if (Number.isFinite(p.accuracy) && p.accuracy > MAX_ACCURACY_M) return;
  const point = { lat: p.lat, lng: p.lng, time: p.time };
  const last = state.track[state.track.length - 1];
  // Ignore jitter while standing still.
  if (last && metres(last, point) < 3) return;
  state.track.push(point);
  if (state.track.length > MAX_POINTS) state.track.shift();
  persist();
  draw(state.track);
  const km = (totalMetres(state.track) / 1000).toFixed(2);
  call('fgs.update', { text: km + ' km' }).catch(() => {});
}

export async function start() {
  if (state.running || !hasBridge()) return;
  try {
    await call('fgs.start', { type: 'location', title: 'Recording your run', text: '0.00 km' });
    log('fgs.start OK');
  } catch (e) {
    log('fgs.start FAIL ' + (e.code || '') + ' ' + e.message);
    return;
  }
  let notice = null;
  try {
    const r = await call('location.watchBackground', { intervalMs: 10000, minDistanceM: 5 });
    state.watchId = r.watchId;
    state.running = true;
    log('watch ' + state.watchId);
  } catch (e) {
    if (e.code === 'not_granted') {
      notice = "Background location is off. Allow Location and 'Your location all the time' for Run Tracker in the launcher dashboard (Run Tracker's card > Permissions).";
    } else if (e.code === 'os_permission_missing') {
      notice = "Background location is off. In Android settings, set Location for Deft to 'Allow all the time'.";
    }
    log('watchBackground FAIL ' + (e.code || '') + ' ' + e.message);
    call('fgs.stop').catch(() => {});
  }
  show(state.track, state.running);
  if (notice) showNotice(notice);
}

export async function stop() {
  if (!hasBridge()) return;
  if (state.watchId) call('location.watchStop', { watchId: state.watchId }).catch(() => {});
  state.watchId = null;
  state.running = false;
  try { await call('fgs.stop'); } catch (e) { log('fgs.stop FAIL ' + e.message); }
  show(state.track, state.running);
}

export function clear() {
  state.track = [];
  persist();
  draw(state.track);
  show(state.track, state.running);
}

// The watch ends with the service (Stop in the notification, or a grant turned off).
export function onStopped(p) {
  state.watchId = null;
  state.running = false;
  log('fgs.stopped ' + (p && p.reason));
  show(state.track, state.running);
}
