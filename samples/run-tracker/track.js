// Run Tracker track: the saved points, great-circle distance and the flat route drawing.
const STORE = 'run-tracker.track';

export function load() {
  try {
    const list = JSON.parse(localStorage.getItem(STORE) || '[]');
    return Array.isArray(list) ? list.filter((p) => Number.isFinite(p.lat) && Number.isFinite(p.lng)) : [];
  } catch (e) {
    return [];
  }
}

export function save(track) {
  localStorage.setItem(STORE, JSON.stringify(track));
}

// Great-circle distance in metres.
export function metres(a, b) {
  const r = 6371000;
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad;
  const dLng = (b.lng - a.lng) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
  return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
}

export function totalMetres(track) {
  let sum = 0;
  for (let i = 1; i < track.length; i++) sum += metres(track[i - 1], track[i]);
  return sum;
}

// Flat projection around the route's middle, scaled into the 300x300 view box with a margin.
// Returns null for an empty track, else the polyline points and the position of the last fix.
export function project(track) {
  if (track.length === 0) return null;
  const midLat = track.reduce((s, p) => s + p.lat, 0) / track.length;
  const k = Math.cos(midLat * Math.PI / 180);
  const xy = track.map((p) => ({ x: p.lng * k, y: -p.lat }));
  const minX = Math.min(...xy.map((p) => p.x));
  const maxX = Math.max(...xy.map((p) => p.x));
  const minY = Math.min(...xy.map((p) => p.y));
  const maxY = Math.max(...xy.map((p) => p.y));
  const span = Math.max(maxX - minX, maxY - minY, 1e-5);
  const scale = 260 / span;
  const offX = 20 + (260 - (maxX - minX) * scale) / 2;
  const offY = 20 + (260 - (maxY - minY) * scale) / 2;
  const pts = xy.map((p) => [offX + (p.x - minX) * scale, offY + (p.y - minY) * scale]);
  const [hx, hy] = pts[pts.length - 1];
  return {
    points: pts.map(([x, y]) => x.toFixed(1) + ',' + y.toFixed(1)).join(' '),
    here: [hx.toFixed(1), hy.toFixed(1)],
  };
}
