// Wallpaper Clock: draws an analog clock on a 2D canvas. Shared by the app page
// (preview) and the wallpaper page. Colours come from the launcher theme tokens.

export function themeColors() {
  const css = getComputedStyle(document.documentElement);
  const pick = (name, fallback) => css.getPropertyValue(name).trim() || fallback;
  return {
    bg: pick('--deft-bg', 'black'),
    fg: pick('--deft-label', 'white'),
    accent: pick('--deft-accent', 'orange'),
    muted: pick('--deft-label-3', 'gray'),
  };
}

// Sizes the canvas backing store to its CSS size times devicePixelRatio; returns the CSS size.
export function fitCanvas(canvas) {
  const ratio = window.devicePixelRatio || 1;
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  const bw = Math.max(1, Math.round(w * ratio));
  const bh = Math.max(1, Math.round(h * ratio));
  if (canvas.width !== bw || canvas.height !== bh) {
    canvas.width = bw;
    canvas.height = bh;
  }
  const ctx = canvas.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  return { ctx, w, h };
}

function hand(ctx, cx, cy, angle, length, width, color) {
  ctx.strokeStyle = color;
  ctx.lineWidth = width;
  ctx.lineCap = 'round';
  ctx.beginPath();
  ctx.moveTo(cx, cy);
  ctx.lineTo(cx + Math.sin(angle) * length, cy - Math.cos(angle) * length);
  ctx.stroke();
}

// Draws the clock face for `date` centred at (cx, cy) with radius r.
export function drawClock(ctx, cx, cy, r, date, colors) {
  ctx.strokeStyle = colors.muted;
  for (let i = 0; i < 60; i++) {
    const a = (i / 60) * Math.PI * 2;
    const inner = i % 5 === 0 ? r * 0.86 : r * 0.93;
    ctx.lineWidth = i % 5 === 0 ? Math.max(2, r * 0.025) : 1;
    ctx.beginPath();
    ctx.moveTo(cx + Math.sin(a) * inner, cy - Math.cos(a) * inner);
    ctx.lineTo(cx + Math.sin(a) * r, cy - Math.cos(a) * r);
    ctx.stroke();
  }
  const s = date.getSeconds();
  const m = date.getMinutes() + s / 60;
  const h = (date.getHours() % 12) + m / 60;
  hand(ctx, cx, cy, (h / 12) * Math.PI * 2, r * 0.5, Math.max(3, r * 0.05), colors.fg);
  hand(ctx, cx, cy, (m / 60) * Math.PI * 2, r * 0.75, Math.max(2, r * 0.035), colors.fg);
  hand(ctx, cx, cy, (s / 60) * Math.PI * 2, r * 0.85, Math.max(1, r * 0.012), colors.accent);
  ctx.fillStyle = colors.accent;
  ctx.beginPath();
  ctx.arc(cx, cy, Math.max(3, r * 0.03), 0, Math.PI * 2);
  ctx.fill();
}
