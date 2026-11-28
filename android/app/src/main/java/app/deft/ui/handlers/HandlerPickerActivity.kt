package app.deft.ui.handlers

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.deft.DeftApp
import app.deft.data.InstalledWebApp
import app.deft.share.InboxDelivery
import app.deft.ui.OverlayGuard
import app.deft.ui.host.WebViewHostActivity
import app.deft.ui.theme.AppDeftTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// DEVICE-UNCHECKED (inbound "Open with" handlers): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: "Open with" a PDF from the Files app opens the FL app with the content readable via the bridge.
// Remove this note once checked.
/**
 * "Open with" target for other Android apps. Every alias in [HandlerAlias] points here.
 * Lists the installed apps whose manifest `handles` the request, with "Always" per alias, then
 * copies `content://` items into the chosen app's share inbox (same rules as share-in) or passes
 * the `mailto:` / `web+deft:` URL as data, opens the app and delivers `intents.viewReceived`.
 * Raw content URIs never reach the page.
 */
class HandlerPickerActivity : ComponentActivity() {
    private sealed interface Request {
        val type: String

        data class Files(override val type: String, val uris: List<Uri>) : Request
        data class Link(override val type: String, val url: String) : Request
    }

    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayGuard.protect(this)
        setTitle("Open with")
        val alias = HandlerAlias.forClassName(intent.component?.className) ?: run { finish(); return }
        val request = parse(alias, intent) ?: run {
            Toast.makeText(this, "This can't be opened here.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        lifecycleScope.launch {
            val candidates = withContext(Dispatchers.IO) { candidates(request) }
            if (candidates.isEmpty()) {
                Toast.makeText(this@HandlerPickerActivity, "No app here can open this.", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            val defaults = HandlerDefaults(this@HandlerPickerActivity)
            val remembered = HandlerMatch.rememberedChoice(defaults.get(alias), candidates.map { it.id })
            if (remembered != null) {
                deliver(candidates.first { it.id == remembered }, request)
                return@launch
            }
            show(alias, request, candidates, defaults)
        }
    }

    private fun parse(alias: HandlerAlias, intent: Intent): Request? {
        alias.scheme?.let { scheme ->
            val url = intent.dataString
            if (!HandlerMatch.validLinkUrl(url, scheme)) return null
            return Request.Link(scheme, url!!)
        }
        val range = alias.mime ?: return null
        val data = intent.data
        val type = HandlerMatch.normalizeMime(
            intent.type ?: data?.let { runCatching { contentResolver.getType(it) }.getOrNull() },
        ) ?: return null
        if (!HandlerMatch.mimeMatches(range, type)) return null
        val uris = buildList {
            data?.let { add(it) }
            intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { add(it) } }
        }.distinct().filter { InboxDelivery.accepts(this, it) }
        if (uris.isEmpty()) return null
        return Request.Files(type, uris)
    }

    private suspend fun candidates(request: Request): List<InstalledWebApp> {
        val repo = DeftApp.instance.repository
        return repo.allApps().filter { app ->
            val handles = HandlerAliases.effectiveHandles(repo, app) ?: return@filter false
            when (request) {
                is Request.Files -> HandlerMatch.handlesMime(handles, request.type)
                is Request.Link -> HandlerMatch.handlesScheme(handles, request.type)
            }
        }.sortedBy { it.name.lowercase() }
    }

    private fun show(alias: HandlerAlias, request: Request, apps: List<InstalledWebApp>, defaults: HandlerDefaults) {
        setContent {
            var always by remember { mutableStateOf(false) }
            AppDeftTheme {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Open with", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (request) {
                                is Request.Files -> "The app gets a copy of the file."
                                is Request.Link -> "The app gets this link."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(apps, key = { it.id }) { app ->
                                Text(
                                    text = app.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (always) defaults.set(alias, app.id)
                                            lifecycleScope.launch { deliver(app, request) }
                                        }
                                        .padding(vertical = 12.dp),
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable { always = !always },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = always, onCheckedChange = { always = it })
                            Text("Always use this app for ${alias.label}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { finish() }) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }

    private suspend fun deliver(app: InstalledWebApp, request: Request) {
        if (busy) return
        busy = true
        val payload = when (request) {
            is Request.Files -> {
                // Copied while this activity still holds the sender's URI grant.
                val items = InboxDelivery.copy(this, app.id, request.uris, request.type)
                if (items.isEmpty()) {
                    val message = InboxDelivery.fullMessage(this, app.id, app.name) ?: "Could not read this file."
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    finish()
                    return
                }
                buildJsonObject {
                    put("type", JsonPrimitive(request.type))
                    put("items", InboxDelivery.itemsJson(items))
                }
            }
            is Request.Link -> buildJsonObject {
                put("type", JsonPrimitive(request.type))
                put("url", JsonPrimitive(request.url))
                put("items", InboxDelivery.itemsJson(emptyList()))
            }
        }.toString()
        InboxDelivery.emit(app.id, "intents.viewReceived", payload)
        startActivity(WebViewHostActivity.intent(this, app.id))
        finish()
    }
}
