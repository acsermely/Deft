package app.deft.ui.agent

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.deft.DeftApp
import app.deft.a11y.A11yGuard
import app.deft.agent.AgentDictation
import app.deft.agent.AgentAttachment
import app.deft.agent.AgentAttachments
import app.deft.agent.AgentImages
import app.deft.R
import app.deft.agent.OpenRouterSignIn
import app.deft.agent.AgentLine
import app.deft.agent.AgentSession
import app.deft.agent.AgentTabView
import app.deft.agent.AgentTools
import app.deft.agent.PendingTool
import app.deft.agent.ToolKind
import app.deft.workspace.AgentWorkspace
import app.deft.workspace.ProjectInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun HomeAgentOverlay(
    hasKey: Boolean,
    autoFocus: Boolean = false,
    autoListen: Boolean = false,
    onSavedKey: () -> Unit,
    onClose: () -> Unit,
    onOpenPreview: (String) -> Unit,
    onPreviewChange: (Boolean) -> Unit = {},
    reveal: Float = 1f,
    sheetHintPx: Float = 0f,
    onHeaderDragStart: () -> Unit = {},
    onHeaderDrag: (Float) -> Unit = {},
    onHeaderDragEnd: (Float) -> Unit = {},
    onSheetHeight: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var keyDraft by remember { mutableStateOf("") }
    var drafts by remember { mutableStateOf(mapOf<String, String>()) }
    var listening by remember { mutableStateOf(false) }
    var dictationError by remember { mutableStateOf<String?>(null) }
    var listenPrefix by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf(mapOf<String, List<AgentAttachment>>()) }
    val imageScope = rememberCoroutineScope()
    val tabs by AgentSession.tabs.collectAsState()
    val selectedId by AgentSession.selectedId.collectAsState()
    val selected = tabs.find { it.id == selectedId } ?: tabs.firstOrNull()
    val agentDraft = drafts[selectedId].orEmpty()
    val agentBusy = selected?.busy == true
    val pending = selected?.pendingTools.orEmpty()
    val dictation = remember(context) { AgentDictation(context) }
    dictation.onTranscript = { piece ->
        drafts = drafts + (selectedId to joinDictation(listenPrefix, piece))
    }
    dictation.onListening = { on -> listening = on }
    dictation.onError = { message -> dictationError = message }
    DisposableEffect(dictation) {
        onDispose { dictation.destroy() }
    }
    // While the panel shows, the home screen is a guarded screen for accessibility:
    // a holder must not read the conversation or press Send / Apply.
    DisposableEffect(Unit) {
        A11yGuard.agentPanelShown(true)
        onDispose { A11yGuard.agentPanelShown(false) }
    }
    val draftSnapshot = rememberUpdatedState(agentDraft)
    val dictationSnapshot = rememberUpdatedState(dictation)
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            dictationError = null
            listenPrefix = draftSnapshot.value
            dictationSnapshot.value.start()
        } else {
            dictationError = "Microphone permission is required to dictate."
        }
    }
    fun beginListen() {
        dictationError = null
        listenPrefix = agentDraft
        dictation.start()
    }
    fun toggleMic() {
        if (listening) {
            dictation.stop()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) beginListen() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(autoListen) {
        if (autoListen) toggleMic()
    }
    var lastTab by remember { mutableStateOf(selectedId) }
    LaunchedEffect(selectedId) {
        if (lastTab != selectedId) dictation.cancel()
        lastTab = selectedId
    }
    LaunchedEffect(agentBusy, pending.isNotEmpty()) {
        if (agentBusy || pending.isNotEmpty()) dictation.cancel()
    }
    val myApps = DeftApp.instance.myApps
    val workspace = DeftApp.instance.agentWorkspace
    var projects by remember { mutableStateOf(myApps.listProjects()) }
    LaunchedEffect(tabs, agentBusy) {
        projects = myApps.listProjects()
    }
    val selectedEmpty = selected?.empty == true
    val recents = remember(projects, tabs) {
        val bySlug = projects.associateBy { it.slug }
        workspace.recents().mapNotNull { bySlug[it] }
    }
    AgentPanel(
        hasKey = hasKey,
        keyDraft = keyDraft,
        onKeyDraft = { keyDraft = it },
        onSaveKey = {
            OpenRouterSignIn.savePastedKey(context, keyDraft)
            keyDraft = ""
            onSavedKey()
        },
        lines = selected?.lines.orEmpty(),
        draft = agentDraft,
        onDraft = { drafts = drafts + (selectedId to it) },
        busy = agentBusy,
        pending = pending,
        error = dictationError ?: selected?.error?.display(),
        listening = listening,
        onMic = { toggleMic() },
        onSend = {
            dictation.cancel()
            val text = agentDraft.trim()
            val picked = attachments[selectedId].orEmpty()
            if ((text.isNotEmpty() || picked.isNotEmpty()) && !agentBusy) {
                drafts = drafts + (selectedId to "")
                attachments = attachments - selectedId
                AgentSession.send(context, text, attachments = picked)
            }
        },
        attachments = attachments[selectedId].orEmpty(),
        onPickAttachments = { uris ->
            val tabId = selectedId
            val room = (AgentAttachments.MAX_ATTACHMENTS - attachments[tabId].orEmpty().size).coerceAtLeast(0)
            imageScope.launch {
                // One toast for the whole pick: the cap, then the reason for each file that was refused.
                val problems = mutableListOf<String>()
                if (uris.size > room) problems += "Up to ${AgentAttachments.MAX_ATTACHMENTS} attachments per message"
                for (uri in uris.take(room)) {
                    when (val r = AgentAttachments.load(context, uri)) {
                        is AgentAttachments.Result.Ok -> {
                            val current = attachments[tabId].orEmpty()
                            if (current.size >= AgentAttachments.MAX_ATTACHMENTS) break
                            attachments = attachments + (tabId to current + r.attachment)
                        }
                        is AgentAttachments.Result.Rejected -> problems += r.reason
                    }
                }
                if (problems.isNotEmpty()) {
                    Toast.makeText(context, problems.joinToString("\n"), Toast.LENGTH_LONG).show()
                }
            }
        },
        onRemoveAttachment = { id ->
            attachments = attachments + (selectedId to attachments[selectedId].orEmpty().filter { it.id != id })
        },
        onApply = {
            if (pending.isNotEmpty() && !agentBusy) AgentSession.apply(context)
        },
        onReject = {
            if (pending.isNotEmpty() && !agentBusy) AgentSession.reject(context)
        },
        onStop = {
            dictation.cancel()
            AgentSession.stop()
        },
        onClose = {
            dictation.cancel()
            onClose()
        },
        tabs = tabs,
        selectedId = selectedId,
        onSelectTab = { AgentSession.select(it) },
        onCloseTab = { id ->
            drafts = drafts - id
            attachments = attachments - id
            AgentSession.close(id)
        },
        onAddTab = { AgentSession.openEmpty() },
        empty = selectedEmpty,
        recents = recents,
        allProjects = projects,
        onPickWorkspace = { slug ->
            runCatching { AgentSession.bind(selectedId, slug) }
        },
        proposal = selected?.proposal,
        onSendProposal = {
            selected?.id?.let { AgentSession.sendProposal(context, it) }
        },
        onRemoveProposal = {
            selected?.id?.let { AgentSession.dismissProposal(it) }
        },
        previewSlug = selected?.workspaceId?.takeUnless { AgentWorkspace.isHomeSlug(it) },
        previewTitle = selected?.title.orEmpty(),
        onOpenApp = onOpenPreview,
        onPreviewChange = { showing ->
            if (showing) dictation.cancel()
            onPreviewChange(showing)
        },
        onAnswer = { answers ->
            if (pending.isNotEmpty() && !agentBusy) AgentSession.answer(context, answers)
        },
        autoFocus = autoFocus && !autoListen && !selectedEmpty,
        reveal = reveal,
        sheetHintPx = sheetHintPx,
        onHeaderDragStart = onHeaderDragStart,
        onHeaderDrag = onHeaderDrag,
        onHeaderDragEnd = onHeaderDragEnd,
        onSheetHeight = onSheetHeight,
        modifier = modifier,
    )
}

@Composable
fun AgentPanel(
    hasKey: Boolean,
    keyDraft: String,
    onKeyDraft: (String) -> Unit,
    onSaveKey: () -> Unit,
    lines: List<AgentLine>,
    draft: String,
    onDraft: (String) -> Unit,
    busy: Boolean,
    pending: List<PendingTool>,
    error: String?,
    listening: Boolean = false,
    onMic: (() -> Unit)? = null,
    onSend: () -> Unit,
    attachments: List<AgentAttachment> = emptyList(),
    onPickAttachments: (List<Uri>) -> Unit = {},
    onRemoveAttachment: (Long) -> Unit = {},
    onApply: () -> Unit,
    onReject: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    tabs: List<AgentTabView> = emptyList(),
    selectedId: String = AgentWorkspace.HOME_ID,
    onSelectTab: (String) -> Unit = {},
    onCloseTab: (String) -> Unit = {},
    onAddTab: () -> Unit = {},
    empty: Boolean = false,
    recents: List<ProjectInfo> = emptyList(),
    allProjects: List<ProjectInfo> = emptyList(),
    onPickWorkspace: (String) -> Unit = {},
    proposal: String? = null,
    onSendProposal: () -> Unit = {},
    onRemoveProposal: () -> Unit = {},
    previewSlug: String? = null,
    previewTitle: String = "",
    onOpenApp: (String) -> Unit = {},
    onPreviewChange: (Boolean) -> Unit = {},
    onAnswer: ((List<String>) -> Unit)? = null,
    autoFocus: Boolean = false,
    reveal: Float = 1f,
    sheetHintPx: Float = 0f,
    onHeaderDragStart: () -> Unit = {},
    onHeaderDrag: (Float) -> Unit = {},
    onHeaderDragEnd: (Float) -> Unit = {},
    onSheetHeight: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val headerDragStart = rememberUpdatedState(onHeaderDragStart)
    val headerDrag = rememberUpdatedState(onHeaderDrag)
    val headerDragEnd = rememberUpdatedState(onHeaderDragEnd)
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // System photo picker: all images on the phone (screenshots, transfers, camera), no permission needed.
    // The multi-select contract needs maxItems >= 2, so a single free slot uses the single-select one.
    val attachRoom = (AgentAttachments.MAX_ATTACHMENTS - attachments.size).coerceAtLeast(0)
    val pickManyContract = remember(attachRoom) {
        ActivityResultContracts.PickMultipleVisualMedia(attachRoom.coerceAtLeast(2))
    }
    val pickMany = rememberLauncherForActivityResult(pickManyContract) { uris ->
        if (uris.isNotEmpty()) onPickAttachments(uris)
    }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPickAttachments(listOf(uri))
    }
    val imagesOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    // File manager (Storage Access Framework): images, PDFs and text files. Each file is classified when loaded.
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onPickAttachments(uris)
    }
    var attachMenu by remember { mutableStateOf(false) }
    val previewProgress = remember { Animatable(0f) }
    var wantPreview by remember { mutableStateOf(false) }
    val showingPreview = previewSlug != null && wantPreview
    val previewSlide = previewProgress.value
    val gpuPreview = previewSlug != null && (wantPreview || previewSlide > 0.02f)
    LaunchedEffect(selectedId, previewSlug) {
        wantPreview = false
        previewProgress.snapTo(0f)
    }
    // apps.preview: the agent opens this tab's Preview like the Preview button does.
    val openRequest by PreviewLink.openRequest.collectAsState()
    LaunchedEffect(openRequest, previewSlug) {
        val slug = openRequest ?: return@LaunchedEffect
        if (slug == previewSlug) {
            wantPreview = true
            PreviewLink.cancelOpen(slug)
        }
    }
    DisposableEffect(previewSlug) {
        PreviewLink.panelShows(previewSlug)
        onDispose { PreviewLink.panelGone(previewSlug) }
    }
    LaunchedEffect(wantPreview, previewSlug) {
        if (previewSlug == null) {
            wantPreview = false
            if (previewProgress.value != 0f) previewProgress.snapTo(0f)
            return@LaunchedEffect
        }
        previewProgress.animateTo(
            if (wantPreview) 1f else 0f,
            spring(stiffness = Spring.StiffnessMediumLow),
        )
    }
    LaunchedEffect(gpuPreview) {
        onPreviewChange(gpuPreview)
        if (gpuPreview) keyboard?.hide()
    }
    DisposableEffect(Unit) {
        onDispose { onPreviewChange(false) }
    }
    LaunchedEffect(autoFocus, hasKey, reveal, pending.isEmpty(), showingPreview) {
        if (showingPreview || !autoFocus || reveal < 0.98f) return@LaunchedEffect
        if (!hasKey || pending.isEmpty()) {
            delay(80)
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }
    Surface(
        modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .onGloballyPositioned { coords ->
                onSheetHeight(coords.size.height.toFloat().coerceAtLeast(1f))
            }
            .then(
                if (reveal < 0.995f) {
                    Modifier.graphicsLayer {
                        val h = maxOf(size.height, sheetHintPx).coerceAtLeast(1f)
                        translationY = h * (1f - reveal)
                    }
                } else {
                    Modifier
                },
            )
            .statusBarsPadding()
            .imePadding(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(top = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .agentHeaderDrag(headerDragStart, headerDrag, headerDragEnd)
                    .padding(horizontal = 16.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(50),
                            ),
                    )
                }
                if (showingPreview) {
                    PreviewHeader(
                        title = previewTitle,
                        onBack = { wantPreview = false },
                        onOpen = { previewSlug?.let(onOpenApp) },
                        onClose = onClose,
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WorkspaceTabs(
                            tabs = tabs,
                            selectedId = selectedId,
                            onSelect = onSelectTab,
                            onClose = onCloseTab,
                            onAdd = onAddTab,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 4.dp),
                        )
                        if (previewSlug != null) {
                            IconButton(
                                onClick = { wantPreview = true },
                                enabled = !busy,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = "Preview",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            if (!hasKey) {
                AgentSignIn(
                    keyDraft = keyDraft,
                    onKeyDraft = onKeyDraft,
                    onSaveKey = onSaveKey,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    fieldModifier = Modifier.focusRequester(focusRequester),
                )
            } else if (empty) {
                WorkspaceWelcome(
                    recents = recents,
                    all = allProjects,
                    onPick = onPickWorkspace,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                )
            } else {
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .slideX(-previewSlide),
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                if (AgentWorkspace.isHomeSlug(selectedId)) {
                    val others = tabs.filter { !it.empty && it.workspaceId != AgentWorkspace.HOME_ID }
                    if (others.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            others.forEach { tab ->
                                Text(
                                    buildString {
                                        append(tab.title)
                                        append(" · ")
                                        append(
                                            when (tab.state) {
                                                "working" -> "working"
                                                "needs_apply" -> "needs apply"
                                                "suggestion" -> "suggestion"
                                                else -> "idle"
                                            },
                                        )
                                        tab.snippet?.let { snippet ->
                                            append(" · ")
                                            append(snippet)
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                val listState = rememberLazyListState()
                LaunchedEffect(lines.size, lines.lastOrNull()?.text) {
                    if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(lines, key = { it.id }) { line ->
                        if (line.text.isBlank() && line.attachments.isEmpty()) return@items
                        if (line.role == "reasoning") {
                            Text(
                                line.text,
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                            return@items
                        }
                        if (line.role == "tool") {
                            Text(
                                line.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                            return@items
                        }
                        val bg = if (line.role == "user") {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        } else {
                            MaterialTheme.colorScheme.background
                        }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(bg, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val thumbs = line.attachments.filterIsInstance<AgentAttachment.Image>()
                            if (thumbs.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    thumbs.forEach { image ->
                                        Image(
                                            bitmap = image.thumb,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(10.dp)),
                                        )
                                    }
                                }
                            }
                            FileChips(line.attachments.filter { it !is AgentAttachment.Image })
                            if (line.text.isNotBlank()) {
                                Text(
                                    line.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (!proposal.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Suggested prompt",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(proposal, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onSendProposal,
                                    enabled = !busy && pending.isEmpty(),
                                ) { Text("Send") }
                                TextButton(onClick = onRemoveProposal, enabled = !busy) { Text("Remove") }
                            }
                        }
                    }
                }
                if (pending.isNotEmpty() && pending.any { it.kind == ToolKind.QUESTION }) {
                    AgentQuestionStep(
                        pending = pending,
                        busy = busy,
                        onAnswer = onAnswer,
                        onReject = onReject,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                } else if (pending.isNotEmpty()) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Apply these changes?", style = MaterialTheme.typography.titleSmall)
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            pending.forEach { item ->
                                Text(
                                    "${item.call.function.name}\n${item.preview}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onApply, enabled = !busy) { Text("Apply") }
                            TextButton(onClick = onReject, enabled = !busy) { Text("Reject") }
                        }
                    }
                }
                if (pending.isEmpty()) {
                    val canSend = draft.isNotBlank() || attachments.isNotEmpty()
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (attachments.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(attachments, key = { it.id }) { attachment ->
                                    AttachmentPreview(attachment, onRemove = { onRemoveAttachment(attachment.id) })
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            // 28dp of layout so the icon lines up with the thumbnails; the 48dp touch target overflows it.
                            Box(
                                Modifier
                                    .padding(bottom = 4.dp)
                                    .size(width = 28.dp, height = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                IconButton(
                                    onClick = { attachMenu = true },
                                    enabled = !busy && attachments.size < AgentAttachments.MAX_ATTACHMENTS,
                                    modifier = Modifier.requiredSize(48.dp),
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = "Attach files")
                                }
                                // Sheet colours, not Material's default (lavender) surfaceContainer.
                                DropdownMenu(
                                    expanded = attachMenu,
                                    onDismissRequest = { attachMenu = false },
                                    containerColor = MaterialTheme.colorScheme.background,
                                    tonalElevation = 0.dp,
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Photos") },
                                        leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                                        onClick = {
                                            attachMenu = false
                                            if (attachRoom == 1) pickOne.launch(imagesOnly) else pickMany.launch(imagesOnly)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Files") },
                                        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                                        onClick = {
                                            attachMenu = false
                                            pickFiles.launch(arrayOf("*/*"))
                                        },
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = draft,
                                onValueChange = onDraft,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester)
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                                        if (!event.isCtrlPressed && !event.isMetaPressed) return@onPreviewKeyEvent false
                                        if ((draft.isNotBlank() || attachments.isNotEmpty()) && !busy) onSend()
                                        true
                                    },
                                enabled = !busy && !listening,
                                minLines = 1,
                                maxLines = 6,
                                shape = RoundedCornerShape(28.dp),
                                placeholder = {
                                    Text(
                                        when {
                                            busy -> "Thinking…"
                                            listening -> "Listening…"
                                            else -> "Ask…"
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences,
                                    imeAction = ImeAction.Default,
                                ),
                            )
                            // 48dp button beside a 56dp field: lift it 4dp so the centres match (the + does the same).
                            Box(Modifier.padding(bottom = 4.dp)) {
                                AgentComposerAction(
                                    busy = busy,
                                    listening = listening,
                                    hasText = canSend,
                                    onMic = onMic,
                                    onSend = onSend,
                                    onStop = onStop,
                                )
                            }
                        }
                        // The thin model line, from the start of the composer row.
                        AgentModelLine(
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().offset(y = (-6).dp),
                        )
                    }
                }
                        }
                    }
                    if (previewSlug != null && (wantPreview || previewSlide > 0.01f)) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .slideX(1f - previewSlide),
                        ) {
                            AgentMyAppPreview(
                                slug = previewSlug,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.slideX(fractionFromLeft: Float): Modifier {
    if (fractionFromLeft == 0f) return this
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.place(
                x = (placeable.width * fractionFromLeft).roundToInt(),
                y = 0,
            )
        }
    }
}

@Composable
private fun PreviewHeader(
    title: String,
    onBack: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.Outlined.ChevronLeft,
                contentDescription = "Back to chat",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpen) {
            Icon(
                Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = "Open app",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AgentComposerAction(
    busy: Boolean,
    listening: Boolean,
    hasText: Boolean,
    onMic: (() -> Unit)?,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val stopRun = busy || listening
    val send = !stopRun && hasText
    val dictate = !stopRun && onMic != null
    if (!stopRun && !send && !dictate) return
    FilledIconButton(
        onClick = when {
            busy -> onStop
            listening -> onMic ?: onStop
            send -> onSend
            else -> onMic ?: onSend
        },
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Icon(
            imageVector = when {
                stopRun -> Icons.Outlined.Stop
                send -> Icons.AutoMirrored.Outlined.Send
                else -> Icons.Outlined.Mic
            },
            contentDescription = when {
                busy -> "Stop"
                listening -> "Stop dictation"
                send -> "Send"
                else -> "Dictate"
            },
        )
    }
}

@Composable
private fun WorkspaceTabs(
    tabs: List<AgentTabView>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    var viewportW by remember { mutableIntStateOf(0) }
    var selectedX by remember { mutableIntStateOf(0) }
    var selectedW by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedId, selectedX, selectedW, viewportW, tabs.size) {
        if (viewportW <= 0 || selectedW <= 0) return@LaunchedEffect
        val pad = 16
        val start = selectedX
        val end = selectedX + selectedW
        val visStart = scroll.value
        val visEnd = scroll.value + viewportW
        val dest = when {
            start < visStart + pad -> (start - pad).coerceAtLeast(0)
            end > visEnd - pad -> (end - viewportW + pad).coerceAtLeast(0)
            else -> return@LaunchedEffect
        }
        if (abs(dest - scroll.value) > 2) scroll.animateScrollTo(dest)
    }
    Row(
        modifier
            .onSizeChanged { viewportW = it.width }
            .horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEach { tab ->
            key(tab.id) {
            val selected = tab.id == selectedId
            val home = AgentWorkspace.isHomeSlug(tab.id)
            FilterChip(
                selected = selected,
                onClick = { onSelect(tab.id) },
                modifier = Modifier
                    .then(if (home) Modifier else Modifier.widthIn(max = 168.dp))
                    .onGloballyPositioned { coords ->
                        if (!selected) return@onGloballyPositioned
                        val x = coords.positionInParent().x.roundToInt()
                        val w = coords.size.width
                        if (x != selectedX) selectedX = x
                        if (w != selectedW) selectedW = w
                    },
                label = {
                    if (home) {
                        Icon(
                            painterResource(R.drawable.ic_deft_mark),
                            contentDescription = "Home",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Text(
                            tab.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 96.dp),
                        )
                    }
                },
                leadingIcon = when {
                    tab.busy -> {
                        {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                            )
                        }
                    }
                    tab.pending || tab.hasProposal -> {
                        {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                            )
                        }
                    }
                    else -> null
                },
                trailingIcon = if (home) {
                    null
                } else {
                    {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Close ${tab.title}",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onClose(tab.id) },
                        )
                    }
                },
            )
            }
        }
        IconButton(
            onClick = onAdd,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = "New workspace",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun WorkspaceWelcome(
    recents: List<ProjectInfo>,
    all: List<ProjectInfo>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAll by remember { mutableStateOf(recents.isEmpty()) }
    val rest = all.filter { project -> recents.none { it.slug == project.slug } }
    Column(
        modifier
            .padding(horizontal = 12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("Open a workspace", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(28.dp))
        if (recents.isNotEmpty()) {
            WorkspacePickerHeading("Recent")
            recents.forEach { project ->
                WorkspacePickRow(project, onPick)
            }
        }
        if (all.isEmpty()) {
            Text(
                "No my-apps yet. Create one from Home.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (rest.isNotEmpty()) {
            WorkspacePickerHeading(
                text = "All workspaces",
                expanded = showAll,
                onToggle = { showAll = !showAll },
                modifier = Modifier.padding(top = if (recents.isNotEmpty()) 12.dp else 0.dp),
            )
            if (showAll) {
                rest.forEach { project ->
                    WorkspacePickRow(project, onPick)
                }
            }
        }
    }
}

@Composable
private fun WorkspacePickerHeading(
    text: String,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
) {
    val color = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .heightIn(min = 40.dp)
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onToggle != null) {
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                contentDescription = if (expanded) "Hide $text" else "Show $text",
                tint = color,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@Composable
private fun WorkspacePickRow(project: ProjectInfo, onPick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onPick(project.slug) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            project.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "/${project.slug}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun AgentQuestionStep(
    pending: List<PendingTool>,
    busy: Boolean,
    onAnswer: ((List<String>) -> Unit)?,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prompts = remember(pending) {
        pending.map { AgentTools.parseQuestion(it.call.function.arguments) }
    }
    var index by remember(pending) { mutableIntStateOf(0) }
    val picked = remember(pending) { mutableStateListOf(*Array(prompts.size) { 0 }) }
    val custom = remember(pending) { mutableStateListOf(*Array(prompts.size) { "" }) }
    if (prompts.isEmpty()) return
    val i = index.coerceIn(0, prompts.lastIndex)
    val q = prompts[i]
    val last = i == prompts.lastIndex
    val scroll = rememberScrollState()
    LaunchedEffect(i) { scroll.scrollTo(0) }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (prompts.size > 1) {
            Text(
                "Question ${i + 1} of ${prompts.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (q.header.isNotBlank()) {
                Text(q.header, style = MaterialTheme.typography.titleSmall)
            }
            Text(q.prompt, style = MaterialTheme.typography.bodyMedium)
            q.options.forEachIndexed { oi, label ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = picked[i] == oi, onClick = { picked[i] = oi }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = picked[i] == oi, onClick = { picked[i] = oi })
                    Text(label, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(
                value = custom[i],
                onValueChange = { custom[i] = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Custom answer (optional)") },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (i > 0) {
                TextButton(onClick = { index = i - 1 }, enabled = !busy) { Text("Back") }
            }
            Button(
                onClick = {
                    if (!last) {
                        index = i + 1
                    } else {
                        val answers = prompts.mapIndexed { pi, pq ->
                            custom[pi].trim().ifBlank { pq.options.getOrElse(picked[pi]) { "" } }
                        }
                        onAnswer?.invoke(answers)
                    }
                },
                enabled = !busy,
            ) { Text(if (last) "Submit" else "Next") }
            TextButton(onClick = onReject, enabled = !busy) { Text("Skip") }
        }
    }
}

private fun joinDictation(prefix: String, piece: String): String {
    if (prefix.isBlank()) return piece
    return prefix.trimEnd() + " " + piece
}

private fun Modifier.agentHeaderDrag(
    onStart: State<() -> Unit>,
    onDrag: State<(Float) -> Unit>,
    onEnd: State<(Float) -> Unit>,
): Modifier = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var dragging = false
        var totalX = 0f
        var totalY = 0f
        var lastUptime = down.uptimeMillis
        var velocityY = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (dragging) change.consume()
                break
            }
            val dy = change.position.y - change.previousPosition.y
            val dx = change.position.x - change.previousPosition.x
            totalX += dx
            totalY += dy
            if (!dragging) {
                if (abs(totalY) < slop && abs(totalX) < slop) continue
                // Horizontal wins so tab scrolling and header buttons keep their gestures.
                if (abs(totalX) > abs(totalY)) break
                dragging = true
                onStart.value()
            }
            val dt = (change.uptimeMillis - lastUptime).coerceAtLeast(1L)
            velocityY = dy / dt * 1000f
            lastUptime = change.uptimeMillis
            change.consume()
            onDrag.value(dy)
        }
        if (dragging) onEnd.value(velocityY)
    }
}

/** A picked attachment in the composer's preview row: a 56dp thumbnail for an image, a file tile otherwise. */
@Composable
private fun AttachmentPreview(attachment: AgentAttachment, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val tile = if (attachment is AgentAttachment.Image) Modifier.size(56.dp) else Modifier.size(width = 156.dp, height = 56.dp)
    Box(tile.clip(shape)) {
        when (attachment) {
            is AgentAttachment.Image -> Image(
                bitmap = attachment.thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp),
            )
            else -> Row(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 8.dp, end = 28.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = fileIcon(attachment),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    attachment.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Remove attachment",
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** Text and PDF attachments of a sent message, as one-line chips under its text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileChips(files: List<AgentAttachment>) {
    if (files.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { file ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = fileIcon(file),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    file.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
            }
        }
    }
}

private fun fileIcon(attachment: AgentAttachment): ImageVector = when (attachment) {
    is AgentAttachment.Pdf -> Icons.Outlined.PictureAsPdf
    else -> Icons.Outlined.Description
}
