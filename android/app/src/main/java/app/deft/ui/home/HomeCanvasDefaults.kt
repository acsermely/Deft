package app.deft.ui.home

/**
 * Bundled home canvas files. Seeded into filesDir/deft/canvas/home on first launch.
 *
 * Styled with the design runtime (8.1, 8.2): base, tokens, components, then the theme. Tokens only.
 * The canvas content stays opaque: no blur and no animation, per the A16 audit (commit 8f9c92f).
 * The dock and the harness bar are native glass in Compose, not part of this page.
 */
object HomeCanvasDefaults {
    const val INDEX = "index.html"
    const val STYLES = "styles.css"
    const val MAIN = "main.js"

    val files: Map<String, String> = mapOf(
        INDEX to INDEX_HTML,
        STYLES to STYLES_CSS,
        MAIN to MAIN_JS,
    )

    private const val INDEX_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
  <title>Home</title>
  <link rel="stylesheet" href="/_deft/v1/base.css" />
  <link rel="stylesheet" href="/_deft/v1/tokens.css" />
  <link rel="stylesheet" href="/_deft/v1/components.css" />
  <link rel="stylesheet" href="/_deft/v1/theme.css" />
  <link rel="stylesheet" href="./styles.css" />
  <script type="module" src="./main.js"></script>
  <script type="module" src="/_deft/v1/elements.js"></script>
</head>
<body>
  <!-- static dot-matrix wallpaper behind the dock -->
  <div id="scene" aria-hidden="true"></div>
  <!-- widgets: kept above the dock via --chrome-clearance -->
  <main id="stage">
    <time id="clock" datetime="">00:00</time>
    <p id="date" class="deft-labelMono"></p>
    <button type="button" id="note" class="deft-chip">Ask AI to paint this home</button>
    <!-- the agent places <deft-widget app="myapp:…" size="1x1"> here -->
    <div class="deft-widgets" id="widgets-row"></div>
  </main>
</body>
</html>
"""

    private const val STYLES_CSS = """
/* Home canvas: opaque wallpaper and widgets. Tokens only (deft-design). No blur, no animation. */
:root {
  --chrome-clearance: 240px;
}
html, body {
  margin: 0;
  height: 100%;
  overflow: hidden;
  background: var(--deft-bg);
  color: var(--deft-label);
  font-family: var(--deft-font-text);
}
#scene {
  position: fixed;
  inset: 0;
  pointer-events: none;
  background-image: radial-gradient(color-mix(in oklab, var(--deft-accent) 50%, transparent) 1px, transparent 1.5px);
  background-size: 16px 16px;
  opacity: 0.45;
}
/* fixed, not min-height: 100dvh. Viewport-relative heights resolved to 0 in the canvas WebView on the A16 test. */
#stage {
  position: fixed;
  inset: 0;
  z-index: 1;
  overflow: hidden;
  box-sizing: border-box;
  padding: calc(env(safe-area-inset-top, 0px) + 92px) 28px calc(var(--chrome-clearance) + env(safe-area-inset-bottom, 0px));
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  gap: 8px;
}
#clock {
  display: block;
  flex-shrink: 0;
  font-family: var(--deft-font-numeric);
  font-weight: var(--deft-font-numeric-weight);
  font-size: clamp(64px, 18vw, 112px);
  line-height: 0.9;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
  color: var(--deft-label);
}
#date {
  margin: 10px 0 0;
  color: var(--deft-label-2);
}
#note {
  align-self: flex-start;
  min-height: 2.25rem;
  text-align: left;
  margin-top: 10px;
}
#widgets-row {
  flex-shrink: 0;
  margin-top: 14px;
}
"""

    private const val MAIN_JS = """
// Home clock. Only HH:MM is shown, so it wakes once a minute instead of every second.
const clock = document.getElementById("clock");
const dateEl = document.getElementById("date");
const note = document.getElementById("note");
const notes = [
  "Ask AI to paint this home",
  "Widgets live here. Tell the agent what you want",
  "Clock, weather, quotes, games. This is yours",
];
let noteIndex = 0;

function tick() {
  const now = new Date();
  const hh = String(now.getHours()).padStart(2, "0");
  const mm = String(now.getMinutes()).padStart(2, "0");
  clock.textContent = hh + ":" + mm;
  clock.setAttribute("datetime", now.toISOString());
  dateEl.textContent = now.toLocaleDateString(undefined, {
    weekday: "long",
    month: "long",
    day: "numeric",
  });
}

function scheduleNextMinute() {
  const wait = 60000 - (Date.now() % 60000) + 20;
  setTimeout(() => {
    tick();
    scheduleNextMinute();
  }, wait);
}

tick();
scheduleNextMinute();

// cycle the hint chip
note.addEventListener("click", () => {
  noteIndex = (noteIndex + 1) % notes.length;
  note.textContent = notes[noteIndex];
});
"""
}
