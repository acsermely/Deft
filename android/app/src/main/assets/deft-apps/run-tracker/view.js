// Run Tracker view: the distance, the route, the buttons and the log. Text is set with textContent.
import { totalMetres, project } from './track.js';

const $ = (id) => document.getElementById(id);

export function log(line) {
  $('log').textContent = line + '\n' + $('log').textContent;
  console.log('RUN ' + line);
}

export function draw(track) {
  $('distance').setAttribute('value', (totalMetres(track) / 1000).toFixed(2));
  const p = project(track);
  $('track').setAttribute('points', p ? p.points : '');
  const [hx, hy] = p ? p.here : [-10, -10];
  $('here').setAttribute('cx', hx);
  $('here').setAttribute('cy', hy);
}

export function show(track, running) {
  $('status').textContent = running ? 'Recording' : (track.length ? 'Stopped' : 'Not recording');
  $('start').disabled = running;
  $('stop').disabled = !running;
  $('map').toggleAttribute('data-live', running);
}

// Shown after show(), which resets the status line.
export function showNotice(text) {
  $('status').textContent = text;
}
