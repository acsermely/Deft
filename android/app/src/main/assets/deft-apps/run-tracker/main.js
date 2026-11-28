// Run Tracker (sample): records a run with the screen off. Starts a foreground service of
// type location (fgs.start), watches the location in the background (location.watchBackground),
// draws the route as an SVG polyline and adds up the distance. The track is kept in localStorage.
import '/_deft/v1/motion.js';
import { on } from '/_deft/v1/bridge.js';
import { state, hasBridge, start, stop, clear, onFix, onStopped } from './recorder.js';
import { draw, show, log } from './view.js';

const $ = (id) => document.getElementById(id);

$('start').addEventListener('click', () => start());
$('stop').addEventListener('click', () => stop());
$('clear').addEventListener('click', () => clear());

if (hasBridge()) {
  on('location.fix', onFix);
  on('fgs.stopped', onStopped);
} else {
  log('No deftBridge: open this app in Deft.');
}

draw(state.track);
show(state.track, state.running);
