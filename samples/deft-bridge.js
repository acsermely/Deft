/**
 * Deft JS client. The host injects this at document start.
 * Do not <script src> it from a sample — that would double-install.
 */
(function (global) {
  if (global.deftBridge && global.deftBridge.__deftPort) return;

  var READY_MS = 5000;

  function installDefaultBack() {
    if (typeof global.__deftOnBack === 'function') return;
    global.__deftOnBack = function () {
      var sheet = document.querySelector('.deft-sheet:not(.hidden)');
      if (sheet) { sheet.classList.add('hidden'); return true; }
      var chat = document.getElementById('chat');
      var inbox = document.getElementById('inbox');
      if (chat && inbox && !chat.classList.contains('hidden')) {
        var back = document.getElementById('back');
        if (back) back.click();
        else {
          chat.classList.add('hidden');
          inbox.classList.remove('hidden');
        }
        return true;
      }
      var detail = document.getElementById('detailView');
      var list = document.getElementById('listView');
      if (detail && list && !detail.classList.contains('hidden')) {
        var back = document.getElementById('back');
        if (back) back.click();
        else {
          detail.classList.add('hidden');
          list.classList.remove('hidden');
        }
        return true;
      }
      return false;
    };
  }

  function fail(code, message) {
    var err = new Error(message || code || 'bridge error');
    err.code = code || 'unsupported';
    return err;
  }

  var port = global.jsBridge;
  if (!port || typeof port.postMessage !== 'function') return;

  var readyResolve;
  var ready = new Promise(function (res) { readyResolve = res; });
  var isReady = false;
  var allowed = new Set();
  // Methods a foreground call may send so the host can ask at the point of use (never decided or not a user denial).
  var askable = new Set();
  var caps = new Set();
  var pending = new Map();
  var queued = [];
  var handlers = new Map();
  var eventSubs = new Map();

  function send(obj) {
    port.postMessage(JSON.stringify(obj));
  }

  function flushQueue() {
    var batch = queued.slice();
    queued = [];
    batch.forEach(function (item) { item.go(); });
  }

  function whenReady(fn) {
    if (isReady) return fn();
    return new Promise(function (resolve, reject) {
      var timer = setTimeout(function () {
        reject(fail('bridge_not_ready', 'Bridge not ready'));
      }, READY_MS);
      queued.push({
        go: function () {
          clearTimeout(timer);
          try { resolve(fn()); } catch (e) { reject(e); }
        },
      });
    });
  }

  function onMessage(ev) {
    var raw = ev && ev.data;
    var msg = {};
    try { msg = typeof raw === 'string' ? JSON.parse(raw) : (raw || {}); } catch (e) { return; }
    if (msg.type === 'ready') {
      allowed = new Set(msg.allowed || []);
      askable = new Set(msg.askable || []);
      caps = new Set(msg.caps || []);
      isReady = true;
      readyResolve();
      flushQueue();
      return;
    }
    if (msg.type === 'grants') {
      allowed = new Set(msg.allowed || []);
      askable = new Set(msg.askable || []);
      caps = new Set(msg.caps || []);
      return;
    }
    if (msg.type === 'result') {
      var p = pending.get(msg.id);
      if (!p) return;
      pending.delete(msg.id);
      if (msg.ok) p.resolve(msg.value);
      else {
        var errBody = msg.error || {};
        p.reject(fail(errBody.code, errBody.message));
      }
      return;
    }
    if (msg.type === 'event') {
      var ids = eventSubs.get(msg.name);
      if (!ids) return;
      var payload = msg.payload;
      if (typeof payload === 'string') {
        try { payload = JSON.parse(payload); } catch (e) {}
      }
      ids.forEach(function (id) {
        var fn = handlers.get(id);
        if (fn) fn(payload);
      });
    }
  }

  if (typeof port.addEventListener === 'function') port.addEventListener('message', onMessage);
  else port.onmessage = onMessage;

  installDefaultBack();

  global.deftBridge = {
    __deftPort: true,
    ready: ready,
    get allowed() { return allowed; },
    ping: function () {
      return whenReady(function () {
        return new Promise(function (resolve, reject) {
          var id = 'p-' + Math.random().toString(36).slice(2) + Date.now();
          pending.set(id, { resolve: resolve, reject: reject });
          send({ type: 'ping', v: 1, id: id });
        });
      });
    },
    invoke: function (method, args) {
      return whenReady(function () {
        if (allowed.size && !allowed.has(method) && !askable.has(method)) {
          return Promise.reject(fail('not_granted', 'Not granted'));
        }
        return new Promise(function (resolve, reject) {
          var id = 'r-' + Math.random().toString(36).slice(2) + Date.now();
          pending.set(id, { resolve: resolve, reject: reject });
          send({ type: 'invoke', v: 1, id: id, method: method, args: args || {} });
        });
      });
    },
    subscribe: function (event, handler) {
      var id = 's-' + Math.random().toString(36).slice(2);
      handlers.set(id, handler);
      var start = function () {
        var set = eventSubs.get(event) || new Set();
        set.add(id);
        eventSubs.set(event, set);
        send({ type: 'subscribe', v: 1, id: id, event: event });
      };
      if (isReady) start();
      else queued.push({ go: start });
      return function () {
        handlers.delete(id);
        var set = eventSubs.get(event);
        if (set) set.delete(id);
        send({ type: 'unsubscribe', v: 1, id: id, event: event });
      };
    },
  };


  /*
   * Platform shims: standard web APIs that Android WebView lacks or drops are
   * routed to launcher methods, so ordinary web code works. Each one is gated by a grant on the
   * host; the shims only translate. Without the grant they fail like a browser that said no.
   */
  function notAllowed(message) {
    try { return new DOMException(message || 'Not allowed', 'NotAllowedError'); } catch (e) {
      var err = new Error(message || 'Not allowed'); err.name = 'NotAllowedError'; return err;
    }
  }

  function gated(method, run) {
    return whenReady(function () {
      if (!allowed.has(method)) return Promise.reject(notAllowed(method + ' is not granted'));
      return run();
    });
  }

  function call(method, args) { return global.deftBridge.invoke(method, args || {}); }

  function blobToBase64(blob) {
    return new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onload = function () { resolve(String(reader.result).replace(/^data:[^,]*,/, '')); };
      reader.onerror = function () { reject(reader.error); };
      reader.readAsDataURL(blob);
    });
  }

  /* Helpers: WebAuthn options and answers between buffers and their JSON form (base64url). */
  var WEBAUTHN_ERRORS = {
    cancelled: 'NotAllowedError',
    not_granted: 'NotAllowedError',
    unsupported: 'NotSupportedError',
    in_use: 'InvalidStateError',
    invalid_args: 'TypeError',
  };
  // Which option fields are buffers (WebAuthn Level 3 JSON): paths of dotted keys, `[]` for each item.
  var CREATION_BUFFERS = ['challenge', 'user.id', 'excludeCredentials[].id'];
  var REQUEST_BUFFERS = ['challenge', 'allowCredentials[].id'];
  var RESPONSE_BUFFERS = ['clientDataJSON', 'attestationObject', 'authenticatorData', 'signature', 'userHandle', 'publicKey'];

  function webauthnError(name, message) {
    if (name === 'TypeError') return new TypeError(message || 'invalid options');
    try { return new DOMException(message || name, name); } catch (e) {
      var err = new Error(message || name); err.name = name; return err;
    }
  }

  function b64url(buf) {
    var bytes = buf instanceof ArrayBuffer ? new Uint8Array(buf) : new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
    var bin = '';
    for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  function fromB64url(text) {
    var s = String(text).replace(/-/g, '+').replace(/_/g, '/');
    while (s.length % 4) s += '=';
    var bin = atob(s);
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out.buffer;
  }

  function isBuffer(v) { return v instanceof ArrayBuffer || ArrayBuffer.isView(v); }

  // Options with buffers -> WebAuthn JSON: every buffer becomes base64url, undefined is dropped.
  function toWebAuthnJson(value) {
    if (isBuffer(value)) return b64url(value);
    if (Array.isArray(value)) return value.map(toWebAuthnJson);
    if (value && typeof value === 'object') {
      var out = {};
      Object.keys(value).forEach(function (k) {
        if (value[k] !== undefined && typeof value[k] !== 'function') out[k] = toWebAuthnJson(value[k]);
      });
      return out;
    }
    return value;
  }

  function decodePath(obj, parts) {
    if (!obj || typeof obj !== 'object') return;
    var head = parts[0];
    var list = /\[\]$/.test(head);
    var key = list ? head.slice(0, -2) : head;
    if (parts.length === 1) {
      if (typeof obj[key] === 'string') obj[key] = fromB64url(obj[key]);
      return;
    }
    if (list) {
      if (Array.isArray(obj[key])) obj[key].forEach(function (item) { decodePath(item, parts.slice(1)); });
    } else {
      decodePath(obj[key], parts.slice(1));
    }
  }

  // WebAuthn JSON -> options with buffers (parseCreationOptionsFromJSON / parseRequestOptionsFromJSON).
  function fromWebAuthnJson(json, paths) {
    if (!json || typeof json !== 'object') throw new TypeError('options must be an object');
    var copy = JSON.parse(JSON.stringify(json));
    paths.forEach(function (path) { decodePath(copy, path.split('.')); });
    return copy;
  }

  function credentialFromJson(json, PKC) {
    var r = json.response || {};
    var response = {};
    Object.keys(r).forEach(function (k) {
      response[k] = RESPONSE_BUFFERS.indexOf(k) >= 0 && typeof r[k] === 'string' ? fromB64url(r[k]) : r[k];
    });
    var transports = Array.isArray(r.transports) ? r.transports.slice() : [];
    response.getTransports = function () { return transports.slice(); };
    if (r.publicKeyAlgorithm !== undefined) response.getPublicKeyAlgorithm = function () { return r.publicKeyAlgorithm; };
    if (typeof r.publicKey === 'string') response.getPublicKey = function () { return fromB64url(r.publicKey); };
    if (typeof r.authenticatorData === 'string') response.getAuthenticatorData = function () { return fromB64url(r.authenticatorData); };
    var cred = Object.create(PKC && PKC.prototype ? PKC.prototype : Object.prototype);
    var own = {
      id: json.id,
      rawId: fromB64url(json.rawId || json.id || ''),
      type: json.type || 'public-key',
      authenticatorAttachment: json.authenticatorAttachment || null,
      response: response,
      getClientExtensionResults: function () { return json.clientExtensionResults || {}; },
      toJSON: function () { return json; },
    };
    Object.keys(own).forEach(function (k) {
      Object.defineProperty(cred, k, { value: own[k], enumerable: typeof own[k] !== 'function', configurable: true });
    });
    return cred;
  }

  function installPlatformShims() {
    var nav = global.navigator;
    var doc = global.document;
    if (!nav || !doc) return;

    // navigator.vibrate only with the haptics grant. Defined on the prototype, non-writable,
    // so a page cannot reach the unguarded original.
    try {
      var proto = global.Navigator && global.Navigator.prototype;
      var origVibrate = proto && proto.vibrate;
      if (proto) {
        Object.defineProperty(proto, 'vibrate', {
          configurable: false, writable: false, enumerable: true,
          value: function (pattern) {
            if (!origVibrate || !caps.has('haptics')) return false;
            return origVibrate.call(this, pattern);
          },
        });
      }
    } catch (e) {}

    // window.PaymentRequest exists only with the payments.request grant. Non-configurable
    // getter, so a page cannot put back the unguarded constructor.
    try {
      var origPaymentRequest = global.PaymentRequest;
      if (typeof origPaymentRequest === 'function') {
        Object.defineProperty(global, 'PaymentRequest', {
          configurable: false, enumerable: true,
          get: function () { return caps.has('payments.request') ? origPaymentRequest : undefined; },
        });
      }
    } catch (e) {}

    // A6: navigator.share / canShare through a system chooser.
    if (typeof nav.share !== 'function') {
      try {
        nav.share = function (data) {
          data = data || {};
          return gated('intents.share', function () {
            var files = Array.prototype.slice.call(data.files || []);
            return Promise.all(files.map(function (f) {
              return blobToBase64(f).then(function (b64) {
                return { name: f.name || 'file', mime: f.type || 'application/octet-stream', base64: b64 };
              });
            })).then(function (list) {
              return call('intents.share', { title: data.title, text: data.text, url: data.url, files: list });
            }).then(function () { return undefined; });
          });
        };
        nav.canShare = function (data) {
          return !!data && !!(data.text || data.url || data.title || (data.files && data.files.length));
        };
      } catch (e) {}
    }

    // window.print opens the system print dialog.
    global.print = function () {
      gated('display.print', function () { return call('display.print', { title: doc.title }); })
        .catch(function (e) { console.warn('print:', e && e.message); });
    };

    // Screen Wake Lock keeps the screen on while the app is visible.
    var locks = new Set();
    function releaseAll() {
      locks.forEach(function (s) { s.release(); });
    }
    try {
      Object.defineProperty(nav, 'wakeLock', {
        configurable: true,
        value: {
          request: function (type) {
            if (type && type !== 'screen') return Promise.reject(new TypeError('Only "screen" wake locks exist'));
            return gated('display.keepScreenOn', function () {
              return call('display.keepScreenOn', { on: true }).then(function () {
                var sentinel = {
                  type: 'screen', released: false, onrelease: null,
                  addEventListener: function (name, fn) { if (name === 'release') sentinel.onrelease = fn; },
                  removeEventListener: function () {},
                  release: function () {
                    if (sentinel.released) return Promise.resolve();
                    sentinel.released = true;
                    locks.delete(sentinel);
                    if (typeof sentinel.onrelease === 'function') { try { sentinel.onrelease({ type: 'release' }); } catch (e) {} }
                    return locks.size ? Promise.resolve() : call('display.keepScreenOn', { on: false }).then(function () {});
                  },
                };
                locks.add(sentinel);
                return sentinel;
              });
            });
          },
        },
      });
      doc.addEventListener('visibilitychange', function () { if (doc.hidden) releaseAll(); });
    } catch (e) {}

    // screen.orientation.lock / unlock.
    try {
      var so = global.screen && global.screen.orientation;
      if (so) {
        so.lock = function (orientation) {
          return gated('display.setOrientation', function () {
            return call('display.setOrientation', { orientation: String(orientation) }).then(function () {});
          });
        };
        so.unlock = function () {
          if (allowed.has('display.setOrientation')) call('display.setOrientation', { orientation: 'any' });
        };
      }
    } catch (e) {}

    // navigator.clipboard.readText, right after a tap.
    try {
      var cb = nav.clipboard;
      if (cb) {
        cb.readText = function () {
          return gated('clipboard.readText', function () {
            return call('clipboard.readText').then(function (r) { return (r && r.text) || ''; });
          });
        };
      }
    } catch (e) {}

    // DEVICE-UNCHECKED (navigator.credentials shim): built and unit-tested, but the developer
    // has not checked it on a real device yet. Check: passkey-check registers and signs in through navigator.credentials as well as passkeys.*.
    // Remove this note once checked.
    // navigator.credentials.create/get({publicKey}) through passkeys.create/get, only with the
    // webauthn grant (the app's own website, after it opted in). Without the grant, and for any
    // other credential type, the native (disabled) behaviour stays. Options go out as WebAuthn JSON
    // (buffers as base64url); the answer comes back as a PublicKeyCredential-like object whose
    // toJSON() is the provider's JSON, so code written for Chrome works unchanged.
    try {
      var creds = nav.credentials;
      if (creds && typeof creds.create === 'function' && typeof creds.get === 'function') {
        var nativeCreate = creds.create.bind(creds);
        var nativeGet = creds.get.bind(creds);
        var PKC = global.PublicKeyCredential;
        var useBridge = function (method, options) {
          return !!options && typeof options === 'object' && !!options.publicKey && allowed.has(method);
        };
        creds.create = function (options) {
          return whenReady(function () {
            if (!useBridge('passkeys.create', options)) return nativeCreate(options);
            return passkeyCall('passkeys.create', options);
          });
        };
        creds.get = function (options) {
          return whenReady(function () {
            if (!useBridge('passkeys.get', options)) return nativeGet(options);
            if (options.mediation === 'conditional') {
              return Promise.reject(webauthnError('NotSupportedError', 'conditional mediation is not supported'));
            }
            return passkeyCall('passkeys.get', options);
          });
        };
        var passkeyCall = function (method, options) {
          var json;
          try { json = JSON.stringify(toWebAuthnJson(options.publicKey)); } catch (e) {
            return Promise.reject(new TypeError('publicKey options could not be encoded'));
          }
          return call(method, { requestJson: json }).then(function (r) {
            var parsed = JSON.parse((r && r.responseJson) || 'null');
            if (!parsed || typeof parsed !== 'object') throw webauthnError('UnknownError', 'empty answer');
            return credentialFromJson(parsed, PKC);
          }, function (err) {
            throw webauthnError(WEBAUTHN_ERRORS[err && err.code] || 'NotAllowedError', err && err.message);
          });
        };
        // Chrome 129+ statics; added when the WebView lacks them, so the usual JSON flow works.
        if (PKC) {
          if (typeof PKC.parseCreationOptionsFromJSON !== 'function') {
            PKC.parseCreationOptionsFromJSON = function (o) { return fromWebAuthnJson(o, CREATION_BUFFERS); };
          }
          if (typeof PKC.parseRequestOptionsFromJSON !== 'function') {
            PKC.parseRequestOptionsFromJSON = function (o) { return fromWebAuthnJson(o, REQUEST_BUFFERS); };
          }
        }
      }
    } catch (e) {}

    // navigator.mediaSession reaches the lock screen, headsets and Bluetooth with the
    // media.session grant and a running fgs.start {type: 'mediaPlayback'}. The page's own object
    // keeps working; the shim mirrors it to media.* and calls the page's handlers on media.action.
    try {
      var nativeSession = null;
      try { nativeSession = nav.mediaSession || null; } catch (e) {}
      var WEB_TO_BRIDGE = {
        play: 'play', pause: 'pause', stop: 'stop', nexttrack: 'next', previoustrack: 'previous',
        seekto: 'seekto', seekforward: 'seekforward', seekbackward: 'seekbackward',
      };
      var BRIDGE_TO_WEB = {};
      Object.keys(WEB_TO_BRIDGE).forEach(function (k) { BRIDGE_TO_WEB[WEB_TO_BRIDGE[k]] = k; });
      var mediaHandlers = new Map();
      var mediaMeta = null;
      var mediaState = 'none';
      var mediaPos = null;
      var mediaSent = false;
      var mediaDirty = false;
      var mediaSubscribed = false;
      var quiet = function () {};

      if (typeof global.MediaMetadata !== 'function') {
        global.MediaMetadata = function MediaMetadata(init) {
          init = init || {};
          this.title = String(init.title || '');
          this.artist = String(init.artist || '');
          this.album = String(init.album || '');
          this.artwork = Array.isArray(init.artwork) ? init.artwork.slice() : [];
        };
      }

      var absoluteUrl = function (src) {
        var text = String(src || '');
        if (/^data:/i.test(text)) return text;
        try {
          var loc = global.location || {};
          return new URL(text, loc.href || ((loc.origin || '') + '/')).href;
        } catch (e) { return ''; }
      };

      var metaArgs = function () {
        var m = mediaMeta;
        var args = { title: String(m.title || '') || doc.title || 'Playing' };
        if (m.artist) args.artist = String(m.artist);
        if (m.album) args.album = String(m.album);
        var art = Array.isArray(m.artwork) ? m.artwork.filter(function (a) { return a && a.src; }) : [];
        if (art.length) {
          var url = absoluteUrl(art[art.length - 1].src);
          if (url) args.artworkUrl = url;
        }
        if (mediaPos && isFinite(mediaPos.duration)) args.durationMs = Math.round(mediaPos.duration * 1000);
        return args;
      };

      var stateArgs = function () {
        var args = { state: mediaState };
        if (mediaPos) {
          args.positionMs = Math.round(mediaPos.position * 1000);
          if (mediaPos.playbackRate > 0) args.rate = mediaPos.playbackRate;
        }
        return args;
      };

      var actionList = function () {
        var out = [];
        mediaHandlers.forEach(function (_fn, web) { out.push(WEB_TO_BRIDGE[web]); });
        return out;
      };

      // Everything changed in one turn goes out together, metadata first.
      var flushMedia = function () {
        mediaDirty = false;
        if (!allowed.has('media.setMetadata')) return;
        if (!mediaMeta) {
          if (mediaSent) call('media.release').catch(quiet);
          mediaSent = false;
          return;
        }
        mediaSent = true;
        call('media.setMetadata', metaArgs()).catch(quiet);
        if (allowed.has('media.setActions')) call('media.setActions', { actions: actionList() }).catch(quiet);
        if (allowed.has('media.setState')) call('media.setState', stateArgs()).catch(quiet);
      };

      var markMedia = function () {
        if (mediaDirty) return;
        mediaDirty = true;
        ready.then(function () { return Promise.resolve(); }).then(flushMedia);
      };

      var onMediaAction = function (payload) {
        var web = payload && BRIDGE_TO_WEB[payload.action];
        var fn = web && mediaHandlers.get(web);
        if (!fn) return;
        var details = { action: web };
        if (typeof payload.seekTime === 'number') details.seekTime = payload.seekTime;
        try { fn(details); } catch (e) { console.error(e); }
      };

      var mediaSession = {
        get metadata() { return mediaMeta; },
        set metadata(m) {
          mediaMeta = m || null;
          try { if (nativeSession) nativeSession.metadata = m; } catch (e) {}
          markMedia();
        },
        get playbackState() { return mediaState; },
        set playbackState(s) {
          if (s !== 'none' && s !== 'paused' && s !== 'playing') return;
          mediaState = s;
          try { if (nativeSession) nativeSession.playbackState = s; } catch (e) {}
          markMedia();
        },
        setActionHandler: function (action, handler) {
          if (!Object.prototype.hasOwnProperty.call(WEB_TO_BRIDGE, action)) {
            if (nativeSession) return nativeSession.setActionHandler(action, handler);
            throw new TypeError('Unknown media session action: ' + action);
          }
          if (typeof handler === 'function') mediaHandlers.set(action, handler);
          else mediaHandlers.delete(action);
          try { if (nativeSession) nativeSession.setActionHandler(action, handler); } catch (e) {}
          if (!mediaSubscribed) {
            mediaSubscribed = true;
            global.deftBridge.subscribe('media.action', onMediaAction);
          }
          markMedia();
        },
        setPositionState: function (st) {
          if (!st || st.duration === undefined) {
            mediaPos = null;
          } else {
            var duration = Number(st.duration);
            var position = st.position === undefined ? 0 : Number(st.position);
            var rate = st.playbackRate === undefined ? 1 : Number(st.playbackRate);
            if (!(duration >= 0) || !(position >= 0) || position > duration || !rate) {
              throw new TypeError('Invalid position state');
            }
            mediaPos = { duration: duration, position: position, playbackRate: rate };
          }
          try { if (nativeSession) nativeSession.setPositionState(st); } catch (e) {}
          markMedia();
        },
        setMicrophoneActive: function (on) {
          if (nativeSession && nativeSession.setMicrophoneActive) return nativeSession.setMicrophoneActive(on);
        },
        setCameraActive: function (on) {
          if (nativeSession && nativeSession.setCameraActive) return nativeSession.setCameraActive(on);
        },
      };
      Object.defineProperty(nav, 'mediaSession', {
        configurable: true, enumerable: true,
        get: function () { return mediaSession; },
      });

      // A new mediaPlayback service has a new, empty session: send what the page set again.
      var bridgeInvoke = global.deftBridge.invoke;
      global.deftBridge.invoke = function (method, args) {
        var p = bridgeInvoke(method, args);
        if (method === 'fgs.start' && args && args.type === 'mediaPlayback') {
          p.then(function () { markMedia(); }, quiet);
        }
        return p;
      };
    } catch (e) {}

    // <a download> saves through the system "Save as" dialog (files.write grant).
    var blobs = new Map();
    try {
      var createURL = URL.createObjectURL;
      var revokeURL = URL.revokeObjectURL;
      URL.createObjectURL = function (obj) {
        var url = createURL.call(URL, obj);
        if (obj instanceof Blob) blobs.set(url, obj);
        return url;
      };
      URL.revokeObjectURL = function (url) {
        // Keep the blob a moment: pages often revoke right after a.click().
        setTimeout(function () { blobs.delete(url); }, 60000);
        return revokeURL.call(URL, url);
      };
    } catch (e) {}

    function dataUrlToBlob(href) {
      var m = /^data:([^,]*?)(;base64)?,(.*)$/i.exec(href);
      if (!m) return null;
      var mime = m[1] || 'application/octet-stream';
      var bytes;
      if (m[2]) {
        var bin = atob(m[3]);
        bytes = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      } else {
        bytes = new TextEncoder().encode(decodeURIComponent(m[3]));
      }
      return new Blob([bytes], { type: mime });
    }

    function downloadable(a) {
      if (!a || !a.hasAttribute || !a.hasAttribute('download')) return null;
      var href = a.href || '';
      if (/^blob:/i.test(href)) return blobs.has(href) ? Promise.resolve(blobs.get(href)) : null;
      if (/^data:/i.test(href)) { var b = dataUrlToBlob(href); return b ? Promise.resolve(b) : null; }
      try {
        if (new URL(href).origin === global.location.origin) {
          return fetch(href).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.blob(); });
        }
      } catch (e) {}
      return null;
    }

    function save(a, blobPromise) {
      var name = a.getAttribute('download') || (a.pathname || '').split('/').pop() || 'download';
      gated('files.saveAs', function () {
        return blobPromise.then(function (blob) {
          return blobToBase64(blob).then(function (b64) {
            return call('files.saveAs', { name: name, mime: blob.type || 'application/octet-stream', base64: b64 });
          });
        });
      }).catch(function (e) { if (!e || e.code !== 'cancelled') console.warn('download:', e && e.message); });
    }

    try {
      var anchorClick = HTMLAnchorElement.prototype.click;
      HTMLAnchorElement.prototype.click = function () {
        var p = downloadable(this);
        if (p) { save(this, p); return; }
        return anchorClick.call(this);
      };
    } catch (e) {}
    doc.addEventListener('click', function (ev) {
      var a = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
      var p = downloadable(a);
      if (!p) return;
      ev.preventDefault();
      save(a, p);
    }, true);
  }

  installPlatformShims();

  send({ type: 'hello', v: 1 });
})(typeof window !== 'undefined' ? window : this);
