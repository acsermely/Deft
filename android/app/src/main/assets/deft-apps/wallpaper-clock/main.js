// Wallpaper Clock app page (PARITY G11): a preview of the clock the wallpaper page draws, and how
// to turn the wallpaper on. The wallpaper itself is wallpaper/index.html and wallpaper/main.js.
import { themeColors, fitCanvas, drawClock } from './wallpaper/clock.js';

const canvas = document.getElementById('preview');
let lastSecond = -1;

function frame() {
  const now = new Date();
  if (now.getSeconds() !== lastSecond) {
    lastSecond = now.getSeconds();
    const { ctx, w, h } = fitCanvas(canvas);
    const colors = themeColors();
    ctx.clearRect(0, 0, w, h);
    drawClock(ctx, w / 2, h / 2, Math.min(w, h) / 2 - 4, now, colors);
  }
  requestAnimationFrame(frame);
}

requestAnimationFrame(frame);
