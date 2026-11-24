/**
 * One Worker, eight origins: first hostname label selects a folder under samples/.
 * contacts.example.com/ → samples/contacts/  (install this URL in the launcher)
 *
 * Path prefixes (/contacts/…) are only for the directory host / workers.dev preview.
 * Those share one origin — do not install more than one sample from that host.
 */

const APPS = {
  contacts: "Contacts",
  files: "Files",
  sms: "Messages",
  calendar: "Calendar",
  clock: "Clock",
  mesh: "Mesh",
  phone: "Phone",
  "three-tps": "Three TPS — Fireball Duel",
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname.includes("..")) {
      return new Response("Bad path", { status: 400 });
    }

    // Shared design-system runtime, served at the same path on every app host.
    if (url.pathname.startsWith("/_deft/v1/")) {
      return withAssetCache(await env.ASSETS.fetch(request));
    }

    const appId = appIdFromHost(url.hostname) ?? appIdFromPath(url.pathname);
    if (appId) {
      return serveApp(env, request, url, appId);
    }
    if (url.pathname === "/catalog.json" || url.pathname === "/catalog.json/") {
      return serveCatalog(env, request);
    }
    if (url.pathname === "/" || url.pathname === "") {
      return directoryIndex(url);
    }
    return new Response("Not found", { status: 404 });
  },
};

function appIdFromHost(hostname) {
  const host = hostname.toLowerCase().split(":")[0];
  const first = host.split(".")[0];
  return first in APPS ? first : null;
}

function appIdFromPath(pathname) {
  const first = pathname.split("/").filter(Boolean)[0];
  return first && first in APPS ? first : null;
}

async function serveApp(env, request, url, appId) {
  const assetUrl = new URL(url);
  assetUrl.pathname = assetPath(appId, url.pathname);
  const assetRequest = new Request(assetUrl.toString(), request);
  const res = await env.ASSETS.fetch(assetRequest);
  if (res.status !== 404) return withAssetCache(res);
  if (assetUrl.pathname.endsWith("/")) {
    assetUrl.pathname += "index.html";
    return withAssetCache(await env.ASSETS.fetch(new Request(assetUrl.toString(), request)));
  }
  return res;
}

async function serveCatalog(env, request) {
  const assetUrl = new URL(request.url);
  assetUrl.pathname = "/catalog.json";
  const res = await env.ASSETS.fetch(new Request(assetUrl.toString(), request));
  const headers = new Headers(res.headers);
  headers.set("content-type", "application/json; charset=utf-8");
  headers.set("cache-control", "no-store");
  return new Response(res.body, { status: res.status, statusText: res.statusText, headers });
}

function withAssetCache(res) {
  const headers = new Headers(res.headers);
  const type = (res.headers.get("content-type") || "").toLowerCase();
  headers.set(
    "Cache-Control",
    type.includes("text/html") ? "no-store" : "public, max-age=604800",
  );
  return new Response(res.body, { status: res.status, statusText: res.statusText, headers });
}

function assetPath(appId, pathname) {
  const prefix = `/${appId}`;
  let rest = pathname || "/";
  if (rest === prefix || rest.startsWith(`${prefix}/`)) {
    rest = rest.slice(prefix.length) || "/";
  }
  if (!rest.startsWith("/")) rest = `/${rest}`;
  return rest === "/" ? `${prefix}/` : `${prefix}${rest}`;
}

function directoryIndex(url) {
  const scheme = url.protocol.replace(":", "");
  const host = url.hostname;
  const port = portPart(url);
  const rows = Object.entries(APPS)
    .map(([id, name]) => {
      const origin = `${scheme}://${id}.${host}${port}`;
      const preview = `${url.origin}/${id}/`;
      return `<li><a href="${origin}/">${name}</a> — <code>${origin}/</code> (launcher)<br/><span class="muted">same-origin preview: <a href="${preview}">${preview}</a></span></li>`;
    })
    .join("\n");
  const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>Deft samples</title>
  <style>
    :root { color-scheme: dark; --deft-bg:#14120F; --deft-fg:#F2EADF; --deft-muted:#A8A29E; --deft-accent:#F0784A; --deft-surface:#1E1B17; }
    body { font-family: system-ui, sans-serif; margin: 24px; max-width: 44rem; line-height: 1.45; background: var(--deft-bg); color: var(--deft-fg); }
    a { color: var(--deft-accent); }
    code { font-size: 0.9em; background: var(--deft-surface); padding: 0.1em 0.35em; border-radius: 6px; }
    .muted { color: var(--deft-muted); font-size: 0.85em; }
    li { margin: 12px 0; }
  </style>
</head>
<body>
  <h1>Deft sample apps</h1>
  <p>Each sample must be its own origin (<code>scheme://host</code>). Install the <strong>subdomain</strong> URLs in the launcher, not the path previews.</p>
  <p>Launcher Store catalog: <a href="${url.origin}/catalog.json"><code>${url.origin}/catalog.json</code></a></p>
  <p>This directory is <code>${url.origin}/</code>. Locally, use <code>http://contacts.localhost:${url.port || "8787"}/</code> and the other first labels.</p>
  <ul>
    ${rows}
  </ul>
</body>
</html>`;
  return new Response(html, {
    headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" },
  });
}

function portPart(url) {
  return url.port && url.port !== "443" && url.port !== "80" ? `:${url.port}` : "";
}
