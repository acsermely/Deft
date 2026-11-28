# Deft landing

SvelteKit + Tailwind + daisyUI. The homepage is **prerendered** (SSR at build). `/deft.apk` is an **SSR** Pages Function that streams the package from R2.

```sh
cd web
bun install
bun run dev          # http://localhost:5173 — local APK from android/app/build/outputs
bun run build
bun run deploy       # wrangler pages deploy
```

Cloudflare Pages settings if you use Git integration:

| Field | Value |
| --- | --- |
| Root directory | `web` |
| Framework | SvelteKit |
| Build command | `npm run build` |
| Build output | `.svelte-kit/cloudflare` |
| Compatibility flags | `nodejs_compat`, `nodejs_als` |

The debug APK is larger than Pages’ 25 MB static-file cap. After the first Pages deploy, publish the binary:

```sh
./scripts/publish-apk.sh
```

That creates the `deft-apk` R2 bucket (binding `APK` in `wrangler.toml`) and uploads `deft.apk`. Optional Pages secret `APK_DOWNLOAD_URL` redirects instead, if you host the file elsewhere.
