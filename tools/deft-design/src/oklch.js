// OKLCH to sRGB helpers for the token generator. Kotlin mirrors the same math in ui/theme/Oklch.kt.

const clamp01 = (x) => Math.min(1, Math.max(0, x));

export function oklchToLinearRgb(l, c, h) {
  const rad = (h * Math.PI) / 180;
  const a = c * Math.cos(rad);
  const b = c * Math.sin(rad);
  const l_ = l + 0.3963377774 * a + 0.2158037573 * b;
  const m_ = l - 0.1055613458 * a - 0.0638541728 * b;
  const s_ = l - 0.0894841775 * a - 1.291485548 * b;
  const L = l_ ** 3;
  const M = m_ ** 3;
  const S = s_ ** 3;
  return [
    4.0767416621 * L - 3.3077115913 * M + 0.2309699292 * S,
    -1.2684380046 * L + 2.6097574011 * M - 0.3413193965 * S,
    -0.0041960863 * L - 0.7034186147 * M + 1.707614701 * S,
  ];
}

function encodeSrgb(x) {
  return x <= 0.0031308 ? 12.92 * x : 1.055 * x ** (1 / 2.4) - 0.055;
}

/** Clamped sRGB channels 0..255 for an OKLCH colour. */
export function oklchToRgb255(l, c, h) {
  return oklchToLinearRgb(l, c, h).map((v) => Math.round(clamp01(encodeSrgb(clamp01(v))) * 255));
}

/** Unsigned 0xAARRGGBB for an OKLCH colour with alpha 0..1. */
export function oklchToArgb(l, c, h, alpha = 1) {
  const [r, g, b] = oklchToRgb255(l, c, h);
  const a = Math.round(clamp01(alpha) * 255);
  return ((a << 24) | (r << 16) | (g << 8) | b) >>> 0;
}

export function num(n) {
  return String(Math.round(n * 10000) / 10000);
}

/** CSS oklch() text for a colour token: { l, c, h, alpha }. */
export function oklchCss({ l, c, h, alpha = 1 }) {
  const a = alpha === 1 ? "" : ` / ${num(alpha)}`;
  return `oklch(${num(l)} ${num(c)} ${num(h)}${a})`;
}
