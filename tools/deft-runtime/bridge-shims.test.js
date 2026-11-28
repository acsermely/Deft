import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";

// The host injects samples/deft-bridge.js at document start. These tests run it against a
// fake page and check the platform shims route standard APIs to launcher methods.
const SOURCE = readFileSync(join(import.meta.dir, "../../samples/deft-bridge.js"), "utf8");

function page() {
  const sent = [];
  const listeners = [];
  const vibrated = [];
  class Navigator {}
  Navigator.prototype.vibrate = (p) => { vibrated.push(p); return true; };
  const navigator = new Navigator();
  navigator.clipboard = { readText: () => Promise.resolve("native") };
  const nativeCreds = [];
  navigator.credentials = {
    create: (o) => { nativeCreds.push(["create", o]); return Promise.reject(Object.assign(new Error("native"), { name: "NotAllowedError" })); },
    get: (o) => { nativeCreds.push(["get", o]); return Promise.reject(Object.assign(new Error("native"), { name: "NotAllowedError" })); },
  };
  class PublicKeyCredential {}
  const docListeners = {};
  const document = {
    hidden: false,
    title: "Test",
    addEventListener: (name, fn) => { (docListeners[name] ||= []).push(fn); },
    querySelector: () => null,
    getElementById: () => null,
  };
  const win = {
    navigator,
    Navigator,
    document,
    screen: { orientation: {} },
    location: { origin: "https://abc.app.deft.internal" },
    jsBridge: {
      postMessage: (raw) => sent.push(JSON.parse(raw)),
      addEventListener: (_name, fn) => listeners.push(fn),
    },
    PaymentRequest: class PaymentRequest {},
    PublicKeyCredential,
  };
  const prevDoc = globalThis.document;
  globalThis.document = document;
  try {
    new Function("window", SOURCE.replace(/typeof window !== 'undefined' \? window : this/, "window"))(win);
  } finally {
    globalThis.document = prevDoc;
  }
  const deliver = (msg) => listeners.forEach((fn) => fn({ data: JSON.stringify(msg) }));
  const answer = () => {
    // Resolve every pending invoke with ok.
    for (const m of sent.filter((m) => m.type === "invoke" && !m.answered)) {
      m.answered = true;
      deliver({ type: "result", id: m.id, ok: true, value: { text: "pasted" } });
    }
  };
  return { win, sent, deliver, answer, vibrated, nativeCreds };
}

const tick = () => new Promise((r) => setTimeout(r, 0));

describe("platform shims", () => {
  test("vibrate needs the haptics grant and cannot be bypassed", () => {
    const p = page();
    p.deliver({ type: "ready", allowed: [], caps: [] });
    expect(p.win.navigator.vibrate(100)).toBe(false);
    expect(p.vibrated).toEqual([]);
    p.deliver({ type: "grants", allowed: [], caps: ["haptics"] });
    expect(p.win.navigator.vibrate(50)).toBe(true);
    expect(p.vibrated).toEqual([50]);
    expect(() => { p.win.Navigator.prototype.vibrate = () => true; }).toThrow();
  });

  test("PaymentRequest exists only with the payments.request grant", () => {
    const p = page();
    p.deliver({ type: "ready", allowed: [], caps: [] });
    expect(p.win.PaymentRequest).toBeUndefined();
    p.deliver({ type: "grants", allowed: [], caps: ["payments.request"] });
    expect(typeof p.win.PaymentRequest).toBe("function");
    expect(() => { p.win.PaymentRequest = null; }).toThrow();
  });

  test("navigator.share calls intents.share when granted", async () => {
    const p = page();
    p.deliver({ type: "ready", allowed: ["intents.share"], caps: ["intents.open"] });
    const done = p.win.navigator.share({ title: "T", text: "hello" });
    await tick();
    const call = p.sent.find((m) => m.type === "invoke");
    expect(call.method).toBe("intents.share");
    expect(call.args).toMatchObject({ title: "T", text: "hello", files: [] });
    p.answer();
    await expect(done).resolves.toBeUndefined();
  });

  test("ungranted APIs fail with NotAllowedError and send nothing", async () => {
    const p = page();
    p.deliver({ type: "ready", allowed: [], caps: [] });
    await expect(p.win.navigator.wakeLock.request("screen")).rejects.toMatchObject({ name: "NotAllowedError" });
    await expect(p.win.navigator.clipboard.readText()).rejects.toMatchObject({ name: "NotAllowedError" });
    await expect(p.win.screen.orientation.lock("landscape")).rejects.toMatchObject({ name: "NotAllowedError" });
    expect(p.sent.filter((m) => m.type === "invoke")).toEqual([]);
  });

  test("wake lock turns keep-screen-on on, and off after the last release", async () => {
    const p = page();
    p.deliver({ type: "ready", allowed: ["display.keepScreenOn"], caps: ["display"] });
    const req = p.win.navigator.wakeLock.request("screen");
    await tick();
    p.answer();
    const sentinel = await req;
    expect(p.sent.at(-1)).toMatchObject({ method: "display.keepScreenOn", args: { on: true } });
    const rel = sentinel.release();
    await tick();
    p.answer();
    await rel;
    expect(p.sent.at(-1)).toMatchObject({ method: "display.keepScreenOn", args: { on: false } });
    expect(sentinel.released).toBe(true);
  });

  test("clipboard.readText and print route to their methods", async () => {
    const p = page();
    p.deliver({ type: "ready", allowed: ["clipboard.readText", "display.print"], caps: [] });
    const text = p.win.navigator.clipboard.readText();
    await tick();
    p.answer();
    await expect(text).resolves.toBe("pasted");
    p.win.print();
    await tick();
    expect(p.sent.at(-1)).toMatchObject({ method: "display.print", args: { title: "Test" } });
  });

  test("mediaSession stays local without the media.session grant", async () => {
    const p = page();
    p.deliver({ type: "ready", allowed: [], caps: [] });
    const ms = p.win.navigator.mediaSession;
    ms.metadata = new p.win.MediaMetadata({ title: "Ep 1" });
    ms.playbackState = "playing";
    ms.setActionHandler("play", () => {});
    await tick();
    expect(ms.metadata.title).toBe("Ep 1");
    expect(ms.playbackState).toBe("playing");
    expect(p.sent.filter((m) => m.type === "invoke")).toEqual([]);
  });

  test("mediaSession mirrors metadata, state and handlers to media.*", async () => {
    const p = page();
    const media = ["media.setMetadata", "media.setState", "media.setActions", "media.release"];
    p.deliver({ type: "ready", allowed: media, caps: ["media.session"] });
    const ms = p.win.navigator.mediaSession;
    const calls = [];
    ms.setActionHandler("play", (d) => calls.push(d));
    ms.setActionHandler("nexttrack", (d) => calls.push(d));
    ms.setActionHandler("seekto", (d) => calls.push(d));
    ms.metadata = new p.win.MediaMetadata({
      title: "Ep 1",
      artist: "Me",
      artwork: [{ src: "small.png" }, { src: "/art/big.png", sizes: "512x512" }],
    });
    ms.playbackState = "playing";
    ms.setPositionState({ duration: 60, position: 5, playbackRate: 1 });
    await tick();
    const invokes = p.sent.filter((m) => m.type === "invoke");
    expect(invokes.map((m) => m.method)).toEqual(["media.setMetadata", "media.setActions", "media.setState"]);
    expect(invokes[0].args).toEqual({
      title: "Ep 1",
      artist: "Me",
      artworkUrl: "https://abc.app.deft.internal/art/big.png",
      durationMs: 60000,
    });
    expect(invokes[1].args).toEqual({ actions: ["play", "next", "seekto"] });
    expect(invokes[2].args).toEqual({ state: "playing", positionMs: 5000, rate: 1 });
    expect(p.sent.some((m) => m.type === "subscribe" && m.event === "media.action")).toBe(true);

    p.deliver({ type: "event", name: "media.action", payload: JSON.stringify({ action: "next" }) });
    p.deliver({ type: "event", name: "media.action", payload: { action: "seekto", seekTime: 12.5 } });
    p.deliver({ type: "event", name: "media.action", payload: { action: "pause" } });
    expect(calls).toEqual([{ action: "nexttrack" }, { action: "seekto", seekTime: 12.5 }]);

    ms.setActionHandler("nexttrack", null);
    await tick();
    expect(p.sent.filter((m) => m.method === "media.setActions").at(-1).args).toEqual({ actions: ["play", "seekto"] });
    expect(() => ms.setActionHandler("dance", () => {})).toThrow();
    expect(() => ms.setPositionState({ duration: 10, position: 20 })).toThrow();

    ms.metadata = null;
    await tick();
    expect(p.sent.filter((m) => m.type === "invoke").at(-1).method).toBe("media.release");
  });

  test("mediaSession is sent again after fgs.start mediaPlayback", async () => {
    const p = page();
    p.deliver({ type: "ready", allowed: ["fgs.start", "media.setMetadata", "media.setState", "media.setActions"], caps: [] });
    const ms = p.win.navigator.mediaSession;
    ms.metadata = new p.win.MediaMetadata({ title: "Ep 2" });
    await tick();
    const before = p.sent.filter((m) => m.method === "media.setMetadata").length;
    expect(before).toBe(1);
    const started = p.win.deftBridge.invoke("fgs.start", { type: "mediaPlayback", title: "Podcast" });
    await tick();
    p.answer();
    await started;
    await tick();
    expect(p.sent.filter((m) => m.method === "media.setMetadata").length).toBe(before + 1);
    expect(p.sent.filter((m) => m.method === "media.setMetadata").at(-1).args).toEqual({ title: "Ep 2" });
  });
});

// navigator.credentials maps to passkeys.* only with the webauthn grant.
describe("navigator.credentials shim", () => {
  const b64u = (bytes) => Buffer.from(bytes).toString("base64url");
  const creation = () => ({
    publicKey: {
      rp: { id: "example.com", name: "Example" },
      user: { id: new Uint8Array([1, 2, 3]), name: "a", displayName: "A" },
      challenge: new Uint8Array([9, 9, 9, 9]).buffer,
      pubKeyCredParams: [{ type: "public-key", alg: -7 }],
      excludeCredentials: [{ type: "public-key", id: new Uint8Array([7, 7]) }],
      timeout: undefined,
    },
  });
  const answerWith = (p, value) => {
    for (const m of p.sent.filter((m) => m.type === "invoke" && !m.answered)) {
      m.answered = true;
      p.deliver({ type: "result", id: m.id, ...value });
    }
  };

  test("without the grant the native (disabled) behaviour stays and nothing is sent", async () => {
    const p = page();
    p.deliver({ type: "ready", allowed: ["intents.share"], caps: [] });
    await expect(p.win.navigator.credentials.create(creation())).rejects.toMatchObject({ name: "NotAllowedError" });
    await expect(p.win.navigator.credentials.get({ publicKey: { challenge: new Uint8Array([1]) } })).rejects.toMatchObject({ name: "NotAllowedError" });
    expect(p.nativeCreds.map((c) => c[0])).toEqual(["create", "get"]);
    expect(p.sent.filter((m) => m.type === "invoke")).toEqual([]);
  });

  test("create sends WebAuthn JSON to passkeys.create and returns a PublicKeyCredential", async () => {
    const p = page();
    p.deliver({ type: "ready", allowed: ["passkeys.create", "passkeys.get", "passkeys.status"], caps: ["webauthn"] });
    const done = p.win.navigator.credentials.create(creation());
    await tick();
    const call = p.sent.find((m) => m.type === "invoke");
    expect(call.method).toBe("passkeys.create");
    const sentJson = JSON.parse(call.args.requestJson);
    expect(sentJson).toEqual({
      rp: { id: "example.com", name: "Example" },
      user: { id: b64u([1, 2, 3]), name: "a", displayName: "A" },
      challenge: b64u([9, 9, 9, 9]),
      pubKeyCredParams: [{ type: "public-key", alg: -7 }],
      excludeCredentials: [{ type: "public-key", id: b64u([7, 7]) }],
    });
    const responseJson = {
      id: b64u([5, 6]), rawId: b64u([5, 6]), type: "public-key", authenticatorAttachment: "platform",
      response: { clientDataJSON: b64u([123, 125]), attestationObject: b64u([1]), transports: ["internal"], publicKeyAlgorithm: -7 },
      clientExtensionResults: { credProps: { rk: true } },
    };
    answerWith(p, { ok: true, value: { responseJson: JSON.stringify(responseJson) } });
    const cred = await done;
    expect(cred instanceof p.win.PublicKeyCredential).toBe(true);
    expect(cred.id).toBe(b64u([5, 6]));
    expect(new Uint8Array(cred.rawId)).toEqual(new Uint8Array([5, 6]));
    expect(new Uint8Array(cred.response.clientDataJSON)).toEqual(new Uint8Array([123, 125]));
    expect(cred.response.getTransports()).toEqual(["internal"]);
    expect(cred.response.getPublicKeyAlgorithm()).toBe(-7);
    expect(cred.getClientExtensionResults()).toEqual({ credProps: { rk: true } });
    expect(cred.toJSON()).toEqual(responseJson);
    expect(p.nativeCreds).toEqual([]);
  });

  test("get goes to passkeys.get, and bridge errors become WebAuthn errors", async () => {
    const p = page();
    p.deliver({ type: "ready", allowed: ["passkeys.get"], caps: ["webauthn"] });
    const options = { publicKey: { rpId: "example.com", challenge: new Uint8Array([4]), allowCredentials: [{ type: "public-key", id: new Uint8Array([8]) }] } };
    const cancelled = p.win.navigator.credentials.get(options);
    await tick();
    const call = p.sent.find((m) => m.type === "invoke");
    expect(call.method).toBe("passkeys.get");
    expect(JSON.parse(call.args.requestJson)).toEqual({ rpId: "example.com", challenge: b64u([4]), allowCredentials: [{ type: "public-key", id: b64u([8]) }] });
    answerWith(p, { ok: false, error: { code: "cancelled", message: "closed" } });
    await expect(cancelled).rejects.toMatchObject({ name: "NotAllowedError" });

    const none = p.win.navigator.credentials.get(options);
    await tick();
    answerWith(p, { ok: false, error: { code: "unsupported", message: "no provider" } });
    await expect(none).rejects.toMatchObject({ name: "NotSupportedError" });

    // Conditional mediation and non-publicKey requests are not routed.
    await expect(p.win.navigator.credentials.get({ ...options, mediation: "conditional" })).rejects.toMatchObject({ name: "NotSupportedError" });
    await expect(p.win.navigator.credentials.get({ password: true })).rejects.toMatchObject({ name: "NotAllowedError" });
    expect(p.nativeCreds.map((c) => c[0])).toEqual(["get"]);
  });

  test("parseCreationOptionsFromJSON and parseRequestOptionsFromJSON turn base64url into buffers", () => {
    const p = page();
    const PKC = p.win.PublicKeyCredential;
    const created = PKC.parseCreationOptionsFromJSON({
      rp: { id: "example.com", name: "E" }, user: { id: b64u([1]), name: "a", displayName: "A" },
      challenge: b64u([2, 3]), pubKeyCredParams: [], excludeCredentials: [{ type: "public-key", id: b64u([4]) }],
    });
    expect(new Uint8Array(created.challenge)).toEqual(new Uint8Array([2, 3]));
    expect(new Uint8Array(created.user.id)).toEqual(new Uint8Array([1]));
    expect(new Uint8Array(created.excludeCredentials[0].id)).toEqual(new Uint8Array([4]));
    expect(created.rp.id).toBe("example.com");
    const request = PKC.parseRequestOptionsFromJSON({ rpId: "example.com", challenge: b64u([5]), allowCredentials: [{ type: "public-key", id: b64u([6]) }] });
    expect(new Uint8Array(request.challenge)).toEqual(new Uint8Array([5]));
    expect(new Uint8Array(request.allowCredentials[0].id)).toEqual(new Uint8Array([6]));
  });
});

describe("askable capabilities reach the host", () => {
  test("a call in askable (never decided) is sent; a call in neither is rejected not_granted", async () => {
    const p = page();
    p.deliver({
      type: "ready",
      allowed: ["intents.share"],
      askable: ["location.watchBackground"],
      caps: [],
    });
    const asked = p.win.deftBridge.invoke("location.watchBackground", { intervalMs: 10000 });
    await tick();
    expect(p.sent.filter((m) => m.method === "location.watchBackground").length).toBe(1);
    p.answer();
    await expect(asked).resolves.toEqual({ text: "pasted" });

    let code = null;
    await p.win.deftBridge.invoke("camera.capture", {}).catch((e) => { code = e.code; });
    expect(code).toBe("not_granted");
    expect(p.sent.filter((m) => m.method === "camera.capture").length).toBe(0);
  });

  test("grants refresh askable, and allowed stays strict for feature detection", async () => {
    const p = page();
    p.deliver({ type: "ready", allowed: ["intents.share"], askable: [], caps: [] });
    p.deliver({ type: "grants", allowed: ["intents.share"], askable: ["location.watchBackground"], caps: [] });
    expect(p.win.deftBridge.allowed.has("location.watchBackground")).toBe(false);
    p.win.deftBridge.invoke("location.watchBackground", {});
    await tick();
    expect(p.sent.filter((m) => m.method === "location.watchBackground").length).toBe(1);
    p.answer();
  });
});
