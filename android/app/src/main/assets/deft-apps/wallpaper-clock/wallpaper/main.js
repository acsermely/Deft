// Wallpaper Clock wallpaper page (manifest wallpaper.page): drawn by the launcher's
// live wallpaper while the home or lock screen shows. Redraws once a second (plus while a tap
// ripple fades), pans with the home screen pages (wallpaper.offsets) and shows a ripple where you
// tap (wallpaper.touch).
import { themeColors, fitCanvas, drawClock } from './clock.js';

const canvas = document.getElementById('wall');
let xOffset = 0.5;
let ripple = null; // { x, y, start } in screen CSS pixels
let lastSecond = -1;
let dirty = true;

// The wallpaper can be wider than the screen so it can pan. The page does not know the screen
// width, so guess a phone-shaped visible width and pan across the rest.
function visibleWidth(w, h) {
  return Math.min(w, Math.round(h * 0.5));
}

function frame(t) {
  const now = new Date();
  const fading = ripple && t - ripple.start < 600;
  if (dirty || fading || now.getSeconds() !== lastSecond) {
    dirty = false;
    lastSecond = now.getSeconds();
    const { ctx, w, h } = fitCanvas(canvas);
    const colors = themeColors();
    ctx.fillStyle = colors.bg;
    ctx.fillRect(0, 0, w, h);
    const vw = visibleWidth(w, h);
    const left = xOffset * (w - vw);
    drawClock(ctx, left + vw / 2, h * 0.4, vw * 0.38, now, colors);
    if (fading) {
      const p = (t - ripple.start) / 600;
      ctx.strokeStyle = colors.accent;
      ctx.globalAlpha = 1 - p;
      ctx.lineWidth = 3;
      ctx.beginPath();
      ctx.arc(left + ripple.x, ripple.y, 10 + p * 60, 0, Math.PI * 2);
      ctx.stroke();
      ctx.globalAlpha = 1;
    } else if (ripple) {
      ripple = null;
      dirty = true;
    }
  }
  requestAnimationFrame(frame);
}

if (window.deftBridge) {
  window.deftBridge.subscribe('wallpaper.offsets', (e) => {
    if (e && typeof e.xOffset === 'number') {
      xOffset = e.xOffset;
      dirty = true;
    }
  });
  window.deftBridge.subscribe('wallpaper.touch', (e) => {
    if (e && (e.action === 'tap' || e.action === 'down')) {
      ripple = { x: e.x, y: e.y, start: performance.now() };
    }
  });
}

window.addEventListener('resize', () => { dirty = true; });
requestAnimationFrame(frame);
