# Roadmap

Personal-use order for the app platform (packages, runtime, lint, sources). Bridge and capability work toward native parity is planned separately in [../PARITY.md](../PARITY.md). Each later milestone gets its own detailed plan when it starts. Gates run locally through `bun scripts/check.ts`.

## M0 — Security foundations (this plan)

Nine pull requests, in order. See the implementation plan for files, done criteria and the dependency graph.

0. Docs, `scripts/check.ts`, `SpikeTest`.
1. Private storage (`filesDir/deft/`), path rules, zip export/import.
2. Remove URL install. Bundle samples from APK assets.
3. `bridge-catalog.json`, split capability ids, manifest v1.
4. Per-app origins `https://<origin-id>.app.deft.internal`.
5. Shared WebView hardening, navigation lock, `MIN_WEBVIEW_MAJOR`.
6. CSP, `theme.css` stopgap, CSP-clean samples and scaffolds.
7. Bridge argument schemas, risk prompts, confirm-each, agent safety.
8. Hostile suite, migration notes, version `0.2.0`.

Gate: `bun scripts/check.ts` on 2026-11-03. Unit tests passed. Instrumented tests on the A16 (`SM-A165F`, WebView 156) passed, including the hostile cases and the corrected spike (all four assumptions PASS, no CSP nonce).

## After M0

Started 2026-11-03 on top of the M0 gate. These are working slices, not the full spec checklists.

- **M1.** `/_deft/v1/` JS is 8192 bytes and `base.css` is about 12 KB. Clock, contacts, and files render with `html` / `render`. Theme switches dispatch `deft:theme`. WebView text zoom follows the system font scale. Verify probes layout at 360 and 800 px. Keyed `repeat()` reorder is deferred so the JS budget holds. Contrast sampling across every bundled theme is still open.
- **M2.** The hand scanner covers the line-level FL0xx–FL7xx rules, with 88 tests. The bundle is `assets/lint/deft-lint.js`. `DeftLintEngine` runs on writes, verify, and package import. `AgentLog` counts lint results. `scripts/eval-baseline.ts` records that no model run has been made. Rules that need a parser, plus FL9xx, are still open.
- **M3 history.** Agent writes snapshot the previous bytes. `history.undo` restores the last one. Overlay files under `filesDir/deft/overlay/<appId>/` win over the base when the host serves an app.
- **Dev link.** `bun scripts/deft-link.ts` pulls and pushes a debug app folder with `adb run-as`.
- **M4.** `bun scripts/deft.ts` keygen, pack, and verify (ed25519 over a file list inside a Bun `Archive` zip). The on-device Keystore install pipeline is not in the host yet.
- **M5.** `bun scripts/build-index.ts` writes a signed `index.json`. The Worker does not serve it yet.
- **M4b.** `empty` (in `android/app/src/main/assets/deft-components/`) is the first shared component.
- **M7.** `LICENSE`, `SECURITY.md`, and `MAINTAINERS.md` are in the repo. No external audit and no F-Droid submission.
- **M8.** `SetupActivity` runs once: choose the home app, then a note about grants.

## After M0 (original order)

1. **M1 — Runtime and theme.** `/_deft/v1/` modules and `base.css`. `palette.json` replaces `colors.toml` (launcher Compose UI included). daisyUI gallery plus `system` dynamic color. Trusted Types. Scaffold `app.css`. Rewrite 3 reference samples.
2. **M2 — deft-lint.** `tools/deft-lint/` built with `bun build`, tested with `bun test`, committed bundle in `assets/lint/`. `DeftLintEngine.kt` and harness wiring (spec 7.8). Eval runner over adb using `scripts/agent-pull.sh`. Record a baseline before wiring lint in.
3. **History and undo (from M3).** Content-addressed history and "Undo agent session" for local apps and the canvas. Main safety net while the agent edits apps, so this comes before packages.
4. **Dev link (from M6).** Replaces the Documents folder as the way to edit from a Mac. `bun scripts/deft-link.ts` sync client.
5. **M4 — Packages and keys.** Bun `fl pack` / `fl sign` / `fl verify`, on-device Keystore key, install pipeline (spec 11.4). Bundled samples become packages signed with the author's key. Hostile packaging samples.
6. **Overlays and forks (rest of M3).** Useful once installed packages exist.
7. **M5 — Sources.** Signed `index.json` and `.deftpkg` served by the `freedom-samples` Worker (R2 if needed). Source management UI, rollback, expiry, revocation. A Bun script builds and signs the index locally.
8. **M4b — Components and recipes.** After enough reference apps exist to extract from.

## Deferred until public

- **M7** — fuzzing, external audit, F-Droid, governance files, licenses, name check.
- **M8** — device-setup wizard. `scripts/debloat.sh` covers the personal case.
