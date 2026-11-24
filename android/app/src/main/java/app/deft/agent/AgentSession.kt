package app.deft.agent

import android.content.Context
import app.deft.DeftApp
import app.deft.R
import app.deft.workspace.AgentWorkspace
import app.deft.workspace.MyAppsStore
import app.deft.workspace.OpenTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AgentTabView(
    val id: String,
    val workspaceId: String?,
    val title: String,
    val empty: Boolean,
    val busy: Boolean,
    val pending: Boolean,
    val hasProposal: Boolean,
    val proposal: String?,
    val snippet: String?,
    val state: String,
    val lines: List<AgentLine>,
    val error: AgentError?,
    val pendingTools: List<PendingTool>,
)

object AgentSession {
    private const val KEEP_LINES = 80
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()

    @Volatile
    var panelOpen: Boolean = false
        private set

    fun setPanelVisible(open: Boolean) {
        panelOpen = open
        if (open) dismissNotify(workspace.selected.value)
    }

    private fun showTab(id: String) {
        workspace.select(id)
        if (panelOpen) dismissNotify(id)
    }

    private fun dismissNotify(tabId: String) {
        AgentNotify.cancelTab(DeftApp.instance, tabId)
    }

    private val runtimes = LinkedHashMap<String, TabRuntime>()

    private val _tabs = MutableStateFlow(
        listOf(viewOf(OpenTab(AgentWorkspace.HOME_ID, AgentWorkspace.HOME_ID), null)),
    )
    val tabs: StateFlow<List<AgentTabView>> = _tabs.asStateFlow()

    val selectedId: StateFlow<String>
        get() = workspace.selected

    private val workspace get() = DeftApp.instance.agentWorkspace
    private val myApps get() = DeftApp.instance.myApps

    fun select(id: String) {
        val tab = workspace.tab(id)
            ?: runCatching { workspace.tabForWorkspace(id) }.getOrNull()
            ?: return
        showTab(tab.id)
        syncPreviewSlug()
        publish()
    }

    fun openEmpty() {
        val id = workspace.openEmpty()
        showTab(id)
        syncPreviewSlug()
        publish()
    }

    fun bind(tabId: String, slug: String) {
        val clean = MyAppsStore.requireSlug(slug)
        if (!myApps.hasProject(clean)) throw IllegalArgumentException("unknown app $clean")
        val existing = workspace.tabForWorkspace(clean)
        if (existing != null) {
            workspace.touchRecent(clean)
            showTab(existing.id)
            if (existing.id != tabId && workspace.tab(tabId)?.empty == true) {
                close(tabId)
            } else {
                syncPreviewSlug()
                publish()
            }
            return
        }
        workspace.bind(tabId, clean)
        showTab(tabId)
        synchronized(lock) { ensureLocked(tabId) }
        syncPreviewSlug()
        publish()
    }

    fun open(slug: String, select: Boolean = false) {
        val clean = MyAppsStore.requireSlug(slug)
        if (!myApps.hasProject(clean)) throw IllegalArgumentException("unknown app $clean")
        val id = workspace.openWorkspace(clean)
        synchronized(lock) { ensureLocked(id) }
        if (select) {
            showTab(id)
            syncPreviewSlug()
        }
        publish()
    }

    fun close(tabId: String) {
        if (AgentWorkspace.isHomeSlug(tabId)) return
        val runtime: TabRuntime?
        synchronized(lock) {
            runtime = runtimes.remove(tabId)
        }
        runtime?.job?.cancel()
        runtime?.harness?.close()
        runCatching { workspace.close(tabId) }
        syncPreviewSlug()
        publish()
    }

    fun closeWorkspace(slug: String) {
        val tab = workspace.tabForWorkspace(slug) ?: return
        workspace.forgetRecent(slug)
        close(tab.id)
    }

    fun propose(slug: String, prompt: String) {
        val clean = MyAppsStore.requireSlug(slug)
        if (!myApps.hasProject(clean)) throw IllegalArgumentException("unknown app $clean")
        val id = workspace.openWorkspace(clean)
        synchronized(lock) {
            val tab = ensureLocked(id)
            tab.proposal = AgentTabRules.proposalFor(clean, tab.proposal, prompt)
        }
        publish()
    }

    /** Open the my-app tab, switch to it, and send [prompt] as its first message. Safe off the main thread. */
    fun handoff(slug: String, prompt: String) {
        val clean = MyAppsStore.requireSlug(slug)
        if (!myApps.hasProject(clean)) throw IllegalArgumentException("unknown app $clean")
        val id = workspace.openWorkspace(clean)
        val text = synchronized(lock) {
            val tab = ensureLocked(id)
            AgentTabRules.handoffFor(clean, tab.busy, tab.pending.isNotEmpty(), prompt)
        }
        scope.launch {
            showTab(id)
            syncPreviewSlug()
            send(DeftApp.instance, text, id)
        }
    }

    fun dismissProposal(tabId: String) {
        synchronized(lock) { runtimes[tabId]?.proposal = null }
        publish()
    }

    fun sendProposal(context: Context, tabId: String) {
        val text: String
        synchronized(lock) {
            val tab = runtimes[tabId] ?: return
            if (tab.busy || tab.pending.isNotEmpty()) return
            text = tab.proposal?.trim().orEmpty()
            if (text.isEmpty()) return
            tab.proposal = null
        }
        if (workspace.selected.value != tabId) select(tabId)
        send(context, text, tabId)
    }

    /**
     * Sends a user message. The transcript shows the typed text and the attachments; the model gets the typed text
     * with text files inlined, images as data URLs and PDFs as file parts.
     */
    fun send(
        context: Context,
        text: String,
        tabId: String = workspace.selected.value,
        attachments: List<AgentAttachment> = emptyList(),
    ) {
        val tab = workspace.tab(tabId) ?: return
        if (tab.workspaceId == null) return
        synchronized(lock) { ensureLocked(tabId).proposal = null }
        append(tabId, AgentLine(id = newId(), role = "user", text = text, attachments = attachments))
        val images = attachments.filterIsInstance<AgentAttachment.Image>().map { it.dataUrl }
        val files = attachments.filterIsInstance<AgentAttachment.Pdf>().map { FilePart(it.name, it.dataUrl) }
        val textFiles = attachments.count { it is AgentAttachment.Text }
        val modelText = AgentAttachments.modelText(text, attachments)
        run(context, tabId) { harness, onText, onTool, onReasoning ->
            harness.sendUser(modelText, text, images, files, textFiles, onText, onTool, onReasoning)
        }
    }

    fun apply(context: Context) {
        val tab = selectedRuntime()
        val batch = tab.pending
        if (batch.isEmpty()) return
        run(context, tab.id) { harness, onText, onTool, onReasoning ->
            harness.apply(batch, onText, onTool, onReasoning)
        }
    }

    fun reject(context: Context) {
        val tab = selectedRuntime()
        val batch = tab.pending
        if (batch.isEmpty()) return
        run(context, tab.id) { harness, onText, onTool, onReasoning ->
            harness.reject(batch, onText, onTool, onReasoning)
        }
    }

    fun answer(context: Context, answers: List<String>) {
        val tab = selectedRuntime()
        val batch = tab.pending
        if (batch.isEmpty()) return
        run(context, tab.id) { harness, onText, onTool, onReasoning ->
            harness.answer(batch, answers, onText, onTool, onReasoning)
        }
    }

    fun stop() {
        selectedRuntime().job?.cancel()
    }

    fun listText(): String = snapshot().filter { !it.empty }.joinToString("\n") { tab ->
        buildString {
            append(tab.workspaceId ?: tab.id)
            append('\t')
            append(tab.title)
            append('\t')
            append(tab.state)
            if (tab.pending) append("\tpending")
            if (tab.hasProposal) append("\tproposal")
            tab.snippet?.let { append('\t').append(it) }
        }
    }

    fun readText(slug: String): String {
        val tab = workspace.tabForWorkspace(slug)
            ?: throw IllegalArgumentException("tab $slug is not open")
        val lines = synchronized(lock) { runtimes[tab.id]?.lines.orEmpty() }
        return AgentTabRules.transcript(lines)
    }

    fun statusSystemLine(): String {
        val others = snapshot().filter { !it.empty && it.workspaceId != AgentWorkspace.HOME_ID }
        if (others.isEmpty()) {
            return "Open sessions: Home only. To build a new my-app, call apps.create with a prompt; that tab then takes over. sessions.open with a prompt hands off to an existing my-app. sessions.propose leaves a suggestion instead."
        }
        return buildString {
            append("Open sessions:\n")
            for (tab in others) {
                append("- ${tab.workspaceId} (${tab.title}): ${tab.state}")
                tab.snippet?.let { append(" — ").append(it) }
                append('\n')
            }
            append("You may sessions.list, sessions.read, sessions.open, and sessions.propose. sessions.open with a prompt hands off to an idle tab and ends your turn. Proposals wait for the user to Send.")
        }
    }

    private fun selectedRuntime(): TabRuntime {
        val id = workspace.selected.value
        return synchronized(lock) { ensureLocked(id) }
    }

    private fun ensureLocked(id: String): TabRuntime {
        return runtimes.getOrPut(id) { TabRuntime(id) }
    }

    private fun run(
        context: Context,
        tabId: String,
        block: suspend (
            AgentHarness,
            onText: suspend (String) -> Unit,
            onTool: suspend (String) -> Unit,
            onReasoning: suspend (String) -> Unit,
        ) -> TurnResult,
    ) {
        val app = context.applicationContext
        val workspaceId = workspace.tab(tabId)?.workspaceId ?: return
        val tab: TabRuntime
        val gen: Int
        val instance: AgentHarness
        synchronized(lock) {
            tab = ensureLocked(tabId)
            tab.job?.cancel()
            gen = ++tab.generation
            tab.busy = true
            tab.error = null
            tab.pending = emptyList()
            instance = tab.harness ?: AgentHarness(app, workspaceId).also { tab.harness = it }
        }
        publish()
        var streamId: String? = null
        var reasoningId: String? = null
        tab.job = scope.launch(Dispatchers.IO) {
            var notify: Pair<String, String>? = null
            try {
                withContext(Dispatchers.Main.immediate) {
                    val id = newId()
                    reasoningId = id
                    append(tab.id, AgentLine(id = id, role = "reasoning", text = "Thinking…"))
                }
                val onText: suspend (String) -> Unit = { text ->
                    if (text.isNotBlank()) {
                        withContext(Dispatchers.Main.immediate) {
                            val lines = linesOf(tab.id)
                            val last = lines.lastOrNull()
                            val existing = streamId
                            if (existing == null || last?.role == "tool" || last?.role == "reasoning") {
                                val id = newId()
                                streamId = id
                                append(tab.id, AgentLine(id = id, role = "assistant", text = text))
                            } else {
                                replaceLine(tab.id, existing, text)
                            }
                        }
                    }
                }
                val onTool: suspend (String) -> Unit = { hint ->
                    if (hint.isNotBlank()) {
                        withContext(Dispatchers.Main.immediate) {
                            append(tab.id, AgentLine(id = newId(), role = "tool", text = hint))
                        }
                    }
                }
                val onReasoning: suspend (String) -> Unit = { text ->
                    if (text.isNotBlank()) {
                        withContext(Dispatchers.Main.immediate) {
                            val lines = linesOf(tab.id)
                            val last = lines.lastOrNull()
                            val existing = reasoningId
                            if (existing == null || last?.role == "tool" || last?.role == "assistant") {
                                val id = newId()
                                reasoningId = id
                                append(tab.id, AgentLine(id = id, role = "reasoning", text = text))
                            } else {
                                replaceLine(tab.id, existing, text)
                            }
                        }
                    }
                }
                val result = block(instance, onText, onTool, onReasoning)
                withContext(Dispatchers.Main.immediate) {
                    setError(tab.id, result.error)
                    val id = streamId
                    val current = linesOf(tab.id)
                    val idx = id?.let { current.indexOfFirst { line -> line.id == it } } ?: -1
                    if (idx >= 0) {
                        val line = current[idx]
                        val next = when {
                            line.text.isBlank() && result.text.isNullOrBlank() ->
                                current.filterIndexed { i, _ -> i != idx }
                            line.text.isBlank() && !result.text.isNullOrBlank() ->
                                cap(current.toMutableList().also { it[idx] = line.copy(text = result.text!!) })
                            else -> current
                        }
                        setLines(tab.id, next)
                    } else if (!result.text.isNullOrBlank()) {
                        append(tab.id, AgentLine(id = newId(), role = "assistant", text = result.text))
                    }
                    setPending(tab.id, result.pending)
                }
                notify = doneMessage(tab.id, result)
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    val id = streamId
                    val current = linesOf(tab.id)
                    val idx = id?.let { sid -> current.indexOfFirst { it.id == sid } } ?: -1
                    if (idx >= 0 && current[idx].text.isBlank()) {
                        setLines(tab.id, current.filterIndexed { i, _ -> i != idx })
                    }
                }
                throw e
            } catch (t: Throwable) {
                val err = AgentError.from(t)
                instance.log.error(err)
                withContext(Dispatchers.Main.immediate) { setError(tab.id, err) }
                notify = notifyTitle(tab.id) to err.display()
            } finally {
                if (gen != generationOf(tab.id)) return@launch
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    setBusy(tab.id, false)
                    setLines(
                        tab.id,
                        cap(linesOf(tab.id).filterNot { it.role == "reasoning" && it.text == "Thinking…" }),
                    )
                    val visible = panelOpen && workspace.selected.value == tab.id
                    if (notify != null && (!visible || AgentNotify.screenLocked(app))) {
                        AgentNotify.postDone(app, notify.first, notify.second, tab.id)
                    }
                }
            }
        }
    }

    private fun doneMessage(tabId: String, result: TurnResult): Pair<String, String>? {
        val app = DeftApp.instance
        val title = notifyTitle(tabId)
        val err = result.error
        if (err != null) return title to err.display()
        if (result.pending.isNotEmpty()) return title to app.getString(R.string.agent_notify_pending)
        val text = linesOf(tabId).lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.text
            ?: result.text.orEmpty()
        val snippet = text.trim().replace("\n", " ").take(80)
        if (snippet.isBlank()) return title to app.getString(R.string.agent_notify_done)
        return title to snippet
    }

    private fun notifyTitle(tabId: String): String {
        val base = DeftApp.instance.getString(R.string.agent_notify_title)
        val tab = workspace.tab(tabId)
        if (tab == null || AgentWorkspace.isHomeSlug(tab.workspaceId ?: tab.id)) return base
        return "$base · ${titleOf(tab)}"
    }

    private fun append(tabId: String, line: AgentLine) {
        setLines(tabId, cap(linesOf(tabId) + line))
    }

    private fun replaceLine(tabId: String, id: String, text: String) {
        val current = linesOf(tabId)
        val lastLine = current.lastOrNull()
        val next = if (lastLine?.id == id) {
            cap(current.dropLast(1) + lastLine.copy(text = text))
        } else {
            cap(current.map { if (it.id == id) it.copy(text = text) else it })
        }
        setLines(tabId, next)
    }

    private fun linesOf(tabId: String): List<AgentLine> =
        synchronized(lock) { runtimes[tabId]?.lines.orEmpty() }

    private fun generationOf(tabId: String): Int =
        synchronized(lock) { runtimes[tabId]?.generation ?: -1 }

    private fun setLines(tabId: String, lines: List<AgentLine>) {
        synchronized(lock) { runtimes[tabId]?.lines = lines }
        publish()
    }

    private fun setBusy(tabId: String, busy: Boolean) {
        synchronized(lock) { runtimes[tabId]?.busy = busy }
        publish()
    }

    private fun setError(tabId: String, error: AgentError?) {
        synchronized(lock) { runtimes[tabId]?.error = error }
        publish()
    }

    private fun setPending(tabId: String, pending: List<PendingTool>) {
        synchronized(lock) { runtimes[tabId]?.pending = pending }
        publish()
    }

    fun syncPreviewSlug() {
        val workspaceId = workspace.selectedTab()?.workspaceId
        if (workspaceId == null || AgentWorkspace.isHomeSlug(workspaceId) || !myApps.hasProject(workspaceId)) {
            myApps.close()
            return
        }
        myApps.open(workspaceId)
    }

    private fun snapshot(): List<AgentTabView> {
        val open = workspace.tabs.value.ifEmpty {
            listOf(OpenTab(AgentWorkspace.HOME_ID, AgentWorkspace.HOME_ID))
        }
        return synchronized(lock) { open.map { tab -> viewOf(tab, runtimes[tab.id]) } }
    }

    private fun publish() {
        _tabs.value = snapshot()
    }

    private fun viewOf(tab: OpenTab, rt: TabRuntime?): AgentTabView {
        val lines = rt?.lines.orEmpty()
        val busy = rt?.busy == true
        val pendingTools = rt?.pending.orEmpty()
        val proposal = rt?.proposal
        return AgentTabView(
            id = tab.id,
            workspaceId = tab.workspaceId,
            title = titleOf(tab),
            empty = tab.empty,
            busy = busy,
            pending = pendingTools.isNotEmpty(),
            hasProposal = !proposal.isNullOrBlank(),
            proposal = proposal,
            snippet = AgentTabRules.snippet(lines),
            state = AgentTabRules.tabState(busy, pendingTools.isNotEmpty(), !proposal.isNullOrBlank()),
            lines = lines,
            error = rt?.error,
            pendingTools = pendingTools,
        )
    }

    private fun titleOf(tab: OpenTab): String {
        val workspaceId = tab.workspaceId ?: return "Untitled"
        if (AgentWorkspace.isHomeSlug(workspaceId)) return "Home"
        return runCatching { myApps.readManifest(workspaceId).name }.getOrNull() ?: workspaceId
    }

    private fun cap(lines: List<AgentLine>): List<AgentLine> =
        if (lines.size <= KEEP_LINES) lines else lines.takeLast(KEEP_LINES)

    private fun newId(): String = System.nanoTime().toString()

    private class TabRuntime(val id: String) {
        var harness: AgentHarness? = null
        var job: Job? = null
        var generation: Int = 0
        var lines: List<AgentLine> = emptyList()
        var busy: Boolean = false
        var error: AgentError? = null
        var pending: List<PendingTool> = emptyList()
        var proposal: String? = null
    }
}
