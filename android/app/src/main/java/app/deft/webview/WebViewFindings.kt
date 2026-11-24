package app.deft.webview

import android.webkit.ConsoleMessage

data class WebViewFinding(
    val level: String,
    val kind: String,
    val text: String,
    val source: String = "",
    val line: Int = 0,
) {
    fun lineText(): String = buildString {
        append(level).append(' ').append(kind).append(' ').append(text)
        if (source.isNotBlank() && kind == "console") {
            append(" @ ").append(source)
            if (line > 0) append(':').append(line)
        }
    }

    fun fingerprint(): Long {
        var h = FNV_OFFSET
        h = fnv(h, level)
        h = fnv(h, kind)
        h = fnv(h, text)
        h = fnv(h, source)
        h = h xor line.toLong()
        return h * FNV_PRIME
    }
}

private const val FNV_OFFSET = -3750763034362895579L
private const val FNV_PRIME = 1099511628211L

private fun fnv(seed: Long, s: String): Long {
    var h = seed
    for (i in s.indices) {
        h = h xor s[i].code.toLong()
        h *= FNV_PRIME
    }
    h = h xor 0x1FL
    return h * FNV_PRIME
}

object WebViewFindings {
    const val MAX = 40
    const val TEXT_MAX = 400
    private val HTTP_SUBRESOURCE_EXTS = setOf("js", "mjs", "css", "html", "htm", "json", "webmanifest")

    fun clip(raw: String): String {
        if (raw.isEmpty()) return raw
        val out = StringBuilder(raw.length.coerceAtMost(TEXT_MAX))
        for (ch in raw) {
            if (out.length >= TEXT_MAX) break
            out.append(if (ch == '\n' || ch == '\r') ' ' else ch)
        }
        return out.trim().toString()
    }

    fun finding(
        level: String,
        kind: String,
        text: String,
        source: String = "",
        line: Int = 0,
    ): WebViewFinding = WebViewFinding(
        level = level,
        kind = kind,
        text = clip(text),
        source = clip(source),
        line = line,
    )

    /** Same cap as [TEXT_MAX]; clip in JS so Chromium never materializes a huge console string. */
    val HOOK_JS = """
(function(){
  function clip(v) {
    var s = (v && typeof v === 'object' && v.stack) ? String(v.stack) : String(v == null ? '' : v);
    return s.length > $TEXT_MAX ? s.slice(0, $TEXT_MAX) : s;
  }
  window.addEventListener('error', function(e) {
    var loc = (e.filename || '') + ':' + (e.lineno || 0);
    console.error('[uncaught] ' + clip(e.message || 'error') + ' @' + clip(loc));
  });
  window.addEventListener('unhandledrejection', function(e) {
    console.error('[uncaught] unhandled ' + clip(e.reason));
  });
  try {
    // Spec 8.2: the single 'fl' policy goes to dom.js through a one-shot handshake.
    // Page code cannot reach it after that, and cannot create another 'fl' policy.
    if (window.trustedTypes && !('__deftTT' in window)) {
      var deftPolicy = trustedTypes.createPolicy('deft', {
        createHTML: function (s) { return String(s); }
      });
      Object.defineProperty(window, '__deftTT', {
        configurable: false,
        enumerable: false,
        writable: false,
        value: function () { var p = deftPolicy; deftPolicy = null; return p; }
      });
    }
  } catch (e) {}
  window.addEventListener('securitypolicyviolation', function(e) {
    console.error('[csp] ' + clip((e.effectiveDirective || '') + ' ' + (e.blockedURI || '') + ' ' + (e.sourceFile || '')));
  });
  try { window.alert = function(){ console.warn('alert is disabled; use dialog()'); }; } catch (e) {}
  try { window.confirm = function(){ console.warn('confirm is disabled; use dialog()'); return false; }; } catch (e) {}
  try { window.prompt = function(){ console.warn('prompt is disabled; use dialog()'); return null; }; } catch (e) {}
  try {
    if (navigator.serviceWorker && navigator.serviceWorker.register) {
      navigator.serviceWorker.register = function() {
        return Promise.reject(new Error('service workers are not supported'));
      };
    }
  } catch (e) {}
})();
"""

    /** Main-frame load failures only. Skip navigation cancel/abort/block. */
    fun shouldRecordLoad(isForMainFrame: Boolean, description: CharSequence?): Boolean {
        if (!isForMainFrame) return false
        val desc = description?.toString().orEmpty()
        return !desc.contains("ERR_ABORTED", ignoreCase = true) &&
            !desc.contains("ERR_BLOCKED", ignoreCase = true)
    }

    /** Main document, or JS/CSS/HTML/JSON subresources. Skip favicon and other noise. */
    fun shouldRecordHttp(isForMainFrame: Boolean, url: String): Boolean {
        if (isForMainFrame) return true
        val path = url.substringBefore('#').substringBefore('?').substringAfterLast('/').lowercase()
        if (path.startsWith("favicon.")) return false
        val ext = path.substringAfterLast('.', missingDelimiterValue = "")
        return ext in HTTP_SUBRESOURCE_EXTS
    }

    /** Intercepted responses (local my-apps) usually never reach [android.webkit.WebViewClient.onReceivedHttpError]. */
    fun fromIntercept(isForMainFrame: Boolean, url: String, statusCode: Int?): WebViewFinding? {
        val code = statusCode ?: return null
        if (code < 400) return null
        if (!shouldRecordHttp(isForMainFrame, url)) return null
        return http(code, url)
    }

    fun fromConsole(msg: ConsoleMessage): WebViewFinding? {
        val level = when (msg.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> "ERROR"
            ConsoleMessage.MessageLevel.WARNING -> "WARN"
            else -> return null
        }
        return consoleFinding(level, msg.message().orEmpty(), msg.sourceId().orEmpty(), msg.lineNumber())
    }

    /** Chromium also emits `Uncaught …` when [HOOK_JS] already logged `[uncaught]`. */
    fun consoleFinding(
        level: String,
        text: String,
        source: String = "",
        line: Int = 0,
    ): WebViewFinding? {
        if (isNativeUncaughtDuplicate(text)) return null
        val kind = if (text.contains("[csp]")) "csp" else "console"
        return finding(level = level, kind = kind, text = text, source = source, line = line)
    }

    fun isNativeUncaughtDuplicate(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("Uncaught ")
    }

    fun http(code: Int, url: String): WebViewFinding =
        finding(level = "ERROR", kind = "http", text = "$code $url", source = url)

    fun load(description: CharSequence?, url: String): WebViewFinding =
        finding(level = "ERROR", kind = "load", text = "$description $url", source = url)

    fun format(
        slug: String,
        findings: List<WebViewFinding>,
        url: String? = null,
        finished: Boolean = true,
        probeRaw: String = "",
        emptyHint: String? = null,
        overflow: Boolean = false,
    ): String {
        val errors = findings.count { it.level == "ERROR" }
        val warnings = findings.count { it.level == "WARN" }
        val status = when {
            !finished -> "timeout"
            errors > 0 || warnings > 0 -> "issues"
            else -> "ok"
        }
        return buildString {
            append(status).append('\n')
            append("slug=").append(slug)
            if (!url.isNullOrBlank()) append('\n').append("url=").append(url)
            if (!finished) append("\npage=did-not-finish")
            if (probeRaw.isNotBlank()) append('\n').append("probe=").append(clip(probeRaw))
            append('\n').append("errors=").append(errors)
            append('\n').append("warnings=").append(warnings)
            for (item in findings) {
                append('\n')
                append(item.lineText())
            }
            if (overflow) append("\n…truncated")
            if (findings.isEmpty() && !emptyHint.isNullOrBlank()) {
                append('\n').append(emptyHint)
            }
        }
    }
}

/** Newest-[WebViewFindings.MAX] findings; duplicate fingerprints are ignored. */
class WebViewFindingRing(private val max: Int = WebViewFindings.MAX) {
    private val lock = Any()
    private val items = ArrayDeque<WebViewFinding>(max)
    private val seen = HashSet<Long>(max)
    private var overflow = false

    fun offer(finding: WebViewFinding) {
        val id = finding.fingerprint()
        synchronized(lock) {
            if (!seen.add(id)) return
            if (items.size >= max) {
                overflow = true
                val old = items.removeFirst()
                seen.remove(old.fingerprint())
            }
            items.addLast(finding)
        }
    }

    fun snapshot(): Pair<List<WebViewFinding>, Boolean> = synchronized(lock) {
        items.toList() to overflow
    }
}
