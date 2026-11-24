// PARITY D4 sample: a short looping episode that keeps playing with the screen off (foreground,
// type mediaPlayback) and answers the lock screen, headphones and Bluetooth (media.session)
// through the standard navigator.mediaSession API. Results go to #log and console ("POD ...").
import '/_deft/v1/motion.js';
const $ = (id) => document.getElementById(id);
const audio = $('audio');
const episodes = [
  { title: 'Episode 1: Ticks', artist: 'Deft' },
  { title: 'Episode 2: More ticks', artist: 'Deft' },
];
let index = 0;
let running = false;

function log(line) {
  $('log').textContent = line + '\n' + $('log').textContent;
  console.log('POD ' + line);
}

function bridge() { return window.deftBridge; }

function show() {
  const ep = episodes[index];
  $('title').textContent = ep.title;
  $('status').textContent = audio.paused ? (running ? 'Paused' : 'Stopped') : 'Playing';
  const playing = !audio.paused;
  $('play').setAttribute('aria-label', playing ? 'Pause' : 'Play');
  $('play').querySelector('deft-icon').setAttribute('name', playing ? 'pause' : 'play');
}

function position() {
  if (!('mediaSession' in navigator) || !isFinite(audio.duration)) return;
  try {
    navigator.mediaSession.setPositionState({
      duration: audio.duration,
      position: Math.min(audio.currentTime, audio.duration),
      playbackRate: audio.playbackRate,
    });
  } catch (e) { log('position: ' + e.message); }
}

function metadata() {
  const ep = episodes[index];
  navigator.mediaSession.metadata = new MediaMetadata({
    title: ep.title,
    artist: ep.artist,
    album: 'Sample podcast',
    artwork: [{ src: './cover.png', sizes: '64x64', type: 'image/png' }],
  });
}

// Keep running after the user leaves the app; the notification turns into the media controls.
async function keepRunning() {
  if (running || !bridge()) return;
  try {
    await bridge().invoke('fgs.start', { type: 'mediaPlayback', title: 'Playing', text: episodes[index].title });
    running = true;
    log('fgs.start OK');
  } catch (e) {
    log('fgs.start FAIL ' + (e.code || '') + ' ' + e.message);
  }
}

async function play() {
  await keepRunning();
  try { await audio.play(); } catch (e) { log('play FAIL ' + e.message); }
}

function pause() { audio.pause(); }

async function stop() {
  audio.pause();
  audio.currentTime = 0;
  navigator.mediaSession.playbackState = 'none';
  navigator.mediaSession.metadata = null;
  if (running && bridge()) {
    running = false;
    try { await bridge().invoke('fgs.stop'); } catch (e) { log('fgs.stop FAIL ' + e.message); }
  }
  show();
}

async function skip(delta) {
  index = (index + delta + episodes.length) % episodes.length;
  audio.currentTime = 0;
  metadata();
  if (running && bridge()) {
    bridge().invoke('fgs.update', { text: episodes[index].title }).catch(() => {});
  }
  show();
}

audio.addEventListener('play', () => { navigator.mediaSession.playbackState = 'playing'; position(); show(); });
audio.addEventListener('pause', () => { navigator.mediaSession.playbackState = 'paused'; position(); show(); });
audio.addEventListener('loadedmetadata', position);
audio.addEventListener('seeked', position);

const handlers = {
  play: () => play(),
  pause: () => pause(),
  stop: () => stop(),
  nexttrack: () => skip(1),
  previoustrack: () => skip(-1),
  seekto: (d) => { audio.currentTime = Math.min(d.seekTime || 0, audio.duration || 0); },
};
for (const [action, fn] of Object.entries(handlers)) {
  navigator.mediaSession.setActionHandler(action, (details) => { log('action ' + action); fn(details); });
}

$('play').addEventListener('click', () => (audio.paused ? play() : pause()));
$('prev').addEventListener('click', () => skip(-1));
$('next').addEventListener('click', () => skip(1));
$('stop').addEventListener('click', () => stop());

if (bridge()) {
  bridge().subscribe('fgs.stopped', (p) => {
    running = false;
    audio.pause();
    log('fgs.stopped ' + (p && p.reason));
    show();
  });
}

metadata();
show();
