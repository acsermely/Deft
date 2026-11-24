# Deft JS bridge

The bridge reference has one copy: the on-device agent skill [`android/app/src/main/assets/agent/skills/js-bridge.md`](../android/app/src/main/assets/agent/skills/js-bridge.md). It ships in the APK so the agent and humans read the same text, and `BridgeCatalogTest` checks that it mentions every method.

Capability ids, risk levels and method lists are in [`android/app/src/main/assets/agent/bridge-catalog.json`](../android/app/src/main/assets/agent/bridge-catalog.json). Planned additions are in [PARITY.md](PARITY.md).

## haptics.play (no grant)

`haptics.play` vibrates the view with a View haptic. It needs no capability and no consent: it is listed in `ready` `allowed` for every app, so the page can call it at once. Foreground only; a background run gets `not_granted`. The system's touch-feedback setting still applies.

```js
import { call } from '/_deft/v1/bridge.js';
await call('haptics.play', { kind: 'light' });   // light | medium | selection | success | warning
```

Most apps use `deft.haptic(kind)` from `/_deft/v1/motion.js` instead. It is silent outside the launcher, rate-limited to one call per 40 ms, and fires `light` on every press of `.deft-btn`, `.deft-cell[href]` and `[data-deft-press]`.

Kinds map to `HapticFeedbackConstants`: `light` VIRTUAL_KEY, `medium` CONTEXT_CLICK, `selection` CLOCK_TICK, `success` CONFIRM, `warning` REJECT. An unknown kind gives `invalid_args`. Source: `bridge/HapticSpec.kt`.
