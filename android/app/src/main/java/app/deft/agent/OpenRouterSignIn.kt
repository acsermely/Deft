package app.deft.agent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import app.deft.DeftApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * "Sign in with OpenRouter": opens openrouter.ai in a Custom Tab, waits for the redirect on a one-shot
 * localhost listener (OpenRouter allows localhost callbacks on any port), trades the code for a key and
 * stores it. The launcher is the HOME app, so the user returns with the Home button.
 */
object OpenRouterSignIn {
    sealed interface State {
        data object Idle : State
        data object Waiting : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    private const val TIMEOUT_MS = 10 * 60_000L

    fun start(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        val pkce = OpenRouterAuth.newPkce()
        val listener = try {
            Loopback.open()
        } catch (e: Exception) {
            _state.value = State.Failed("Couldn't start sign-in: ${e.message}")
            return
        }
        _state.value = State.Waiting
        job = scope.launch {
            listener.use {
                val code = withTimeoutOrNull(TIMEOUT_MS) { listener.awaitCode(pkce.state, app.packageName) }
                when (code) {
                    null -> _state.value = State.Failed("Sign-in timed out. Try again.")
                    is OpenRouterAuth.Callback.Denied -> _state.value = State.Failed("Sign-in was cancelled.")
                    is OpenRouterAuth.Callback.Code -> finish(app, code.code, pkce.verifier)
                    OpenRouterAuth.Callback.Ignore -> Unit
                }
            }
        }
        val url = Uri.parse(OpenRouterAuth.authUrl(listener.port, pkce))
        try {
            val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
            tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            tab.launchUrl(context, url)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = State.Idle
    }

    private fun finish(context: Context, code: String, verifier: String) {
        val key = try {
            OpenRouterAuth.exchange(code, verifier)
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: "Sign-in failed.")
            return
        }
        AgentKeyStore.set(context, key, AgentKeyStore.SOURCE_SIGN_IN)
        applyDefaultModel(key)
        _state.value = State.Idle
    }

    /** "Use an existing key": stores a pasted key and picks its default model like a sign-in does. */
    fun savePastedKey(context: Context, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        AgentKeyStore.set(context.applicationContext, trimmed, AgentKeyStore.SOURCE_PASTED)
        scope.launch { runCatching { applyDefaultModel(trimmed) } }
    }

    /**
     * A new key starts on the free model when its account never bought credits, and on GLM 5.3 Flash otherwise.
     * Call off the main thread. Leaves the model alone when OpenRouter can't be read.
     */
    fun applyDefaultModel(key: String) {
        val freeTier = OpenRouterAuth.isFreeTier(key) ?: return
        val store = DeftApp.instance.configStore
        val agent = store.config.value.agent
        val model = AgentModelChoices.defaultFor(freeTier)
        if (agent.model != model) store.write("agent.toml", agent.copy(model = model).toToml())
    }

    /** The listener on 127.0.0.1 and, when the phone has it, ::1 on the same port. */
    private class Loopback(private val sockets: List<ServerSocket>) : Closeable {
        val port: Int get() = sockets.first().localPort

        suspend fun awaitCode(state: String, packageName: String): OpenRouterAuth.Callback {
            val results = Channel<OpenRouterAuth.Callback>(Channel.CONFLATED)
            val accepts = sockets.map { server ->
                scope.launch {
                    while (isActive) {
                        val client = runCatching { server.accept() }.getOrNull() ?: break
                        val result = client.use { answer(it, state, packageName) }
                        if (result != OpenRouterAuth.Callback.Ignore) {
                            results.trySend(result)
                            break
                        }
                    }
                }
            }
            try {
                return results.receive()
            } finally {
                accepts.forEach { it.cancel() }
            }
        }

        private fun answer(client: Socket, state: String, packageName: String): OpenRouterAuth.Callback {
            client.soTimeout = 10_000
            val line = runCatching { client.getInputStream().bufferedReader().readLine() }.getOrNull().orEmpty()
            val result = OpenRouterAuth.parseCallback(line, state)
            val (status, message) = when (result) {
                is OpenRouterAuth.Callback.Code -> "200 OK" to "Signed in. Press Home to go back to the launcher."
                is OpenRouterAuth.Callback.Denied -> "200 OK" to "Sign-in was cancelled. Press Home to go back."
                OpenRouterAuth.Callback.Ignore -> "404 Not Found" to "Not found."
            }
            val back = "intent:#Intent;action=android.intent.action.MAIN;" +
                "category=android.intent.category.HOME;package=$packageName;end"
            val html = "<!doctype html><meta name=viewport content=\"width=device-width\">" +
                "<body style=\"font:18px sans-serif;padding:24px\"><p>$message</p>" +
                (if (result == OpenRouterAuth.Callback.Ignore) "" else "<p><a href=\"$back\">Back to the launcher</a></p>") +
                "</body>"
            val body = html.toByteArray(Charsets.UTF_8)
            runCatching {
                client.getOutputStream().apply {
                    write(
                        ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
                    )
                    write(body)
                    flush()
                }
            }
            return result
        }

        override fun close() {
            sockets.forEach { runCatching { it.close() } }
        }

        companion object {
            fun open(): Loopback {
                val v4 = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
                val v6 = runCatching { ServerSocket(v4.localPort, 4, InetAddress.getByName("::1")) }.getOrNull()
                return Loopback(listOfNotNull(v4, v6))
            }
        }
    }
}
