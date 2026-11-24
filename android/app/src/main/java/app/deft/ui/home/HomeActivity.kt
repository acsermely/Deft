package app.deft.ui.home

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.deft.DeftApp
import app.deft.agent.AgentKeyStore
import app.deft.agent.AgentSession
import app.deft.bridge.BridgeService
import app.deft.data.RecentLaunchEntity
import app.deft.data.declaredCapabilities
import app.deft.ui.agent.HomeAgentOverlay
import app.deft.ui.dashboard.DashboardActivity
import app.deft.ui.host.WebViewHostActivity
import app.deft.ui.workspace.WorkspaceLaunchActivity
import app.deft.ui.theme.AppDeftTheme
import app.deft.ui.theme.LocalDeftConfig
import app.deft.ui.consent.ConsentActivity
import app.deft.workspace.AppDeps
import app.deft.workspace.BundledApps
import app.deft.workspace.StorageMigration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : ComponentActivity() {
    private val homeReset = MutableStateFlow(0)
    private val openAgent = MutableStateFlow(false)
    private val gesturesReady = MutableStateFlow(true)
    private var allowIme = false
    private var gestureGate = 0
    private var reopenAgent = false
    private val migratePickerNeeded = MutableStateFlow(false)
    private val urlAppsNotice = MutableStateFlow(false)
    private val migratePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            runCatching { StorageMigration.importFromTree(this, uri) }
        } else {
            StorageMigration.setNeedsPicker(this, false)
        }
        migratePickerNeeded.value = StorageMigration.needsPicker(this)
    }

    override fun onResume() {
        super.onResume()
        suppressHomeGestures()
        hideIdleImeSoon()
        app.deft.bridge.StepStore.get(this).setHomeVisible(true)
    }

    override fun onPause() {
        super.onPause()
        app.deft.bridge.StepStore.get(this).setHomeVisible(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_REOPEN_AGENT, reopenAgent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideIdleImeSoon()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeAgentIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            homeReset.value += 1
            suppressHomeGestures()
            hideIdleImeSoon()
        }
    }

    private fun suppressHomeGestures() {
        val token = ++gestureGate
        gesturesReady.value = false
        window.decorView.postDelayed({
            if (token == gestureGate) gesturesReady.value = true
        }, 450)
    }

    private fun hideIdleImeSoon() {
        hideIdleIme()
        window.decorView.post { hideIdleIme() }
    }

    private fun hideIdleIme() {
        if (allowIme) return
        currentFocus?.clearFocus()
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (app.deft.ui.setup.SetupActivity.needed(this)) {
            startActivity(Intent(this, app.deft.ui.setup.SetupActivity::class.java))
        }
        reopenAgent = savedInstanceState?.getBoolean(STATE_REOPEN_AGENT, false) ?: false
        migratePickerNeeded.value = StorageMigration.needsPicker(this)
        val storagePrefs = getSharedPreferences(StorageMigration.PREF, MODE_PRIVATE)
        urlAppsNotice.value = !storagePrefs.getBoolean(StorageMigration.PREF_NOTICE, false)
        consumeAgentIntent(intent)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        NativeDrawer.warm(this)
        val repo = DeftApp.instance.repository
        setContent {
            AppDeftTheme {
                val cfg = LocalDeftConfig.current
                val light = cfg.colors.mode.equals("light", ignoreCase = true)
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
                val apps by repo.observeApps().collectAsState(initial = emptyList())
                val recentsRows by repo.observeRecents().collectAsState(initial = emptyList())
                var query by remember { mutableStateOf("") }
                var searchOpen by remember { mutableStateOf(false) }
                var agentOpen by remember { mutableStateOf(false) }
                var canvasPaused by remember { mutableStateOf(false) }
                var agentFocusInput by remember { mutableStateOf(false) }
                var agentStartListen by remember { mutableStateOf(false) }
                var hasApiKey by remember { mutableStateOf(AgentKeyStore.get(this@HomeActivity) != null) }
                val wantAgent by openAgent.collectAsState()
                val askMigrate by migratePickerNeeded.collectAsState()
                val askUrlNotice by urlAppsNotice.collectAsState()
                val scope = rememberCoroutineScope()
                val agentReveal = remember { Animatable(0f) }
                var agentSheetH by remember { mutableFloatStateOf(1f) }
                val agentSpringIn = remember { spring<Float>(dampingRatio = 0.86f, stiffness = 380f) }
                val agentSpringOut = remember { spring<Float>(dampingRatio = 0.9f, stiffness = 420f) }
                fun snapAgent(v: Float) {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        agentReveal.snapTo(v.coerceIn(0f, 1f))
                    }
                }
                fun settleAgent(target: Float, spec: AnimationSpec<Float>) {
                    scope.launch { agentReveal.animateTo(target.coerceIn(0f, 1f), spec) }
                }
                fun showAgent(focusInput: Boolean = false, startListen: Boolean = false) {
                    agentFocusInput = focusInput
                    agentStartListen = startListen
                    AgentSession.setPanelVisible(true)
                    agentOpen = true
                    settleAgent(1f, agentSpringIn)
                }
                fun hideAgent(clearReopen: Boolean = true) {
                    if (clearReopen) reopenAgent = false
                    agentFocusInput = false
                    agentStartListen = false
                    AgentSession.setPanelVisible(false)
                    agentOpen = false
                    settleAgent(0f, agentSpringOut)
                }
                LaunchedEffect(wantAgent) {
                    if (wantAgent) {
                        showAgent()
                        openAgent.value = false
                    }
                }
                val nativeApps by NativeDrawer.apps.collectAsState()
                val canvasRevision by DeftApp.instance.homeCanvas.revision.collectAsState()
                val pagerState = rememberPagerState(pageCount = { 2 })
                val reset by homeReset.collectAsState()
                val searchGesturesReady by gesturesReady.collectAsState()
                // Flips only on change; the pager offset and sheet reveal are read in the derivation, not here.
                val canvasVisible by remember {
                    derivedStateOf {
                        pagerState.currentPage == 0 &&
                            !(agentOpen && agentReveal.value > 0.98f) && !searchOpen
                    }
                }
                val focusManager = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                val density = LocalDensity.current
                val fallbackSheetPx = with(density) {
                    (LocalConfiguration.current.screenHeightDp * 0.92f).dp.toPx()
                }
                var imeVisible by remember { mutableStateOf(false) }
                val imeInsets = WindowInsets.ime
                // Boolean only. Reading the inset height here would recompose the home
                // WebView on every frame of the keyboard animation.
                LaunchedEffect(imeInsets) {
                    snapshotFlow { imeInsets.getBottom(density) > 0 }
                        .distinctUntilChanged()
                        .collect { imeVisible = it }
                }
                var searchShown by remember { mutableStateOf(false) }
                var imeWanted by remember { mutableStateOf(false) }
                SideEffect { allowIme = imeWanted || agentOpen }
                LaunchedEffect(searchOpen, agentOpen, pagerState.currentPage, searchGesturesReady) {
                    if (!searchOpen && !agentOpen && (pagerState.currentPage == 0 || !searchGesturesReady)) {
                        focusManager.clearFocus(true)
                        keyboard?.hide()
                    }
                }
                val pull = remember { Animatable(0f) }
                val openPx = with(density) { cfg.home.searchOpenDp.dp.toPx() }
                val searchSnapPx = openPx * cfg.home.searchSnap
                val agentSpanPx = if (agentSheetH > 8f) agentSheetH else fallbackSheetPx
                // Commit distance follows agentOpenDp, same scale as search (~100dp).
                // The sheet still tracks the finger across its full height.
                val agentCommitPx = with(density) { cfg.home.agentOpenDp.dp.toPx() } * cfg.home.agentSnap
                val agentFlingPx = with(density) { 32.dp.toPx() }
                val searchSpringIn = remember { spring<Float>(dampingRatio = 0.82f, stiffness = 900f) }
                val searchSpringOut = remember { spring<Float>(dampingRatio = 0.9f, stiffness = 1100f) }
                val gesturesReadyRef = rememberUpdatedState(searchGesturesReady)
                val searchOpenRef = rememberUpdatedState(searchOpen)
                val openPxRef = rememberUpdatedState(openPx)
                val agentSpanRef = rememberUpdatedState(agentSpanPx)
                val agentCommitRef = rememberUpdatedState(agentCommitPx)
                val agentFlingRef = rememberUpdatedState(agentFlingPx)
                var peekUp by remember { mutableFloatStateOf(0f) }
                var peekUpVelocity by remember { mutableFloatStateOf(0f) }
                var peekSampleAt by remember { mutableLongStateOf(0L) }
                val webLaunchables = remember(apps) { apps.map { Launchable.Web(it) } }
                val nativeLaunchables = remember(nativeApps) { nativeApps.map { Launchable.Native(it) } }
                val recents = remember(recentsRows, apps, nativeApps) {
                    recentsRows.mapNotNull { row ->
                        when (row.kind) {
                            RecentLaunchEntity.KIND_WEB ->
                                apps.find { it.id == row.webAppId }?.let { Launchable.Web(it) }
                            RecentLaunchEntity.KIND_NATIVE ->
                                nativeApps.find {
                                    it.packageName == row.packageName && it.activityName == row.activityName
                                }?.let { Launchable.Native(it) }
                            else -> null
                        }
                    }
                }
                val searchResults = remember(query, webLaunchables, nativeLaunchables) {
                    filterLaunchables(query, webLaunchables, nativeLaunchables)
                }

                fun open(item: Launchable) {
                    when (item) {
                        is Launchable.Web -> {
                            val slug = item.app.workspaceSlug
                            if (slug != null) {
                                startActivity(WorkspaceLaunchActivity.intent(this@HomeActivity, slug))
                            } else {
                                scope.launch {
                                    val caps = item.app.declaredCapabilities()
                                    val need = withContext(Dispatchers.IO) {
                                        repo.ungrantedCapabilities(item.app.id, caps)
                                    }
                                    val needsDeps = withContext(Dispatchers.IO) {
                                        AppDeps.missing(this@HomeActivity, item.app.id, null).isNotEmpty()
                                    }
                                    if (need.isNotEmpty() || needsDeps) {
                                        val bundledId = BundledApps.idFromWebAppId(item.app.id)
                                        if (bundledId != null) {
                                            startActivity(
                                                ConsentActivity.bundledIntent(
                                                    this@HomeActivity,
                                                    bundledId,
                                                    item.app.name,
                                                    item.app.startUrl,
                                                    item.app.origin,
                                                    need,
                                                    newCapsOnly = true,
                                                ),
                                            )
                                            return@launch
                                        }
                                    }
                                    startActivity(WebViewHostActivity.intent(this@HomeActivity, item.app.id))
                                    repo.recordWebLaunch(item.app)
                                }
                            }
                        }
                        is Launchable.Native -> {
                            startActivity(
                                Intent(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_LAUNCHER)
                                    .setClassName(item.app.packageName, item.app.activityName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            scope.launch {
                                repo.recordNativeLaunch(item.app.packageName, item.app.activityName, item.app.label)
                            }
                        }
                    }
                }

                fun armSearch(pullPx: Float) {
                    if (pullPx > 0.5f && !searchShown) searchShown = true
                    if (!imeWanted && pullPx >= searchSnapPx) {
                        allowIme = true
                        imeWanted = true
                    }
                }

                fun clearSearchPull() {
                    searchOpen = false
                    imeWanted = false
                    searchShown = false
                    query = ""
                }

                suspend fun snapPull() {
                    val shouldOpen = pull.value > searchSnapPx
                    searchOpen = shouldOpen
                    if (shouldOpen) {
                        allowIme = true
                        imeWanted = true
                        searchShown = true
                    } else {
                        imeWanted = false
                    }
                    pull.animateTo(if (shouldOpen) openPx else 0f, if (shouldOpen) searchSpringIn else searchSpringOut)
                    if (!shouldOpen) {
                        query = ""
                        searchShown = false
                    }
                }

                fun finishHomePeek() {
                    if (peekUp > 0.5f && pull.value <= 0f && !searchOpenRef.value) {
                        val flung = peekUpVelocity > 800f && peekUp >= agentFlingRef.value
                        if (peekUp >= agentCommitRef.value || flung) showAgent() else hideAgent()
                    } else if (pull.value > 0f) {
                        scope.launch { snapPull() }
                    }
                    peekUpVelocity = 0f
                    peekSampleAt = 0L
                }

                fun onHomePeekDelta(amount: Float) {
                    if (!gesturesReadyRef.value) return
                    val agent = !searchOpenRef.value && pull.value <= 0f &&
                        (peekUp > 0f || agentReveal.value > 0.001f || amount < 0f)
                    if (agent) {
                        if (peekUp <= 0.5f) {
                            peekUpVelocity = 0f
                            peekSampleAt = 0L
                        }
                        val now = SystemClock.uptimeMillis()
                        if (peekSampleAt != 0L) {
                            val dt = (now - peekSampleAt).coerceIn(1L, 48L)
                            val upward = -amount / dt * 1000f
                            peekUpVelocity = peekUpVelocity * 0.4f + upward * 0.6f
                        }
                        peekSampleAt = now
                        val span = agentSpanRef.value.coerceAtLeast(1f)
                        peekUp = (peekUp - amount).coerceIn(0f, span)
                        snapAgent(peekUp / span)
                    } else {
                        peekUp = 0f
                        val next = (pull.value + amount).coerceIn(0f, openPxRef.value * 1.15f)
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { pull.snapTo(next) }
                        if (next == 0f) clearSearchPull() else armSearch(next)
                    }
                }

                fun onHomePeekFinished() {
                    finishHomePeek()
                    peekUp = 0f
                }

                suspend fun closeSearch() {
                    searchOpen = false
                    imeWanted = false
                    query = ""
                    pull.animateTo(0f, searchSpringOut)
                    searchShown = false
                }

                val nestedScroll = object : NestedScrollConnection {
                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                            if (!searchGesturesReady || searchOpen) return Offset.Zero
                            if (available.y < 0f && pull.value > 0f) {
                                val next = (pull.value + available.y).coerceAtLeast(0f)
                                val consumed = next - pull.value
                                scope.launch(start = CoroutineStart.UNDISPATCHED) { pull.snapTo(next) }
                                if (next == 0f) clearSearchPull() else armSearch(next)
                                return Offset(0f, consumed)
                            }
                            return Offset.Zero
                        }

                        override fun onPostScroll(
                            consumed: Offset,
                            available: Offset,
                            source: NestedScrollSource,
                        ): Offset {
                            if (searchGesturesReady && !searchOpen && available.y > 0f && pagerState.currentPage == 0) {
                                val next = (pull.value + available.y).coerceAtMost(openPx * 1.15f)
                                val consumedY = next - pull.value
                                scope.launch(start = CoroutineStart.UNDISPATCHED) { pull.snapTo(next) }
                                armSearch(next)
                                return Offset(0f, consumedY)
                            }
                            return Offset.Zero
                        }

                        override suspend fun onPreFling(available: Velocity): Velocity {
                            if (searchGesturesReady && pull.value > 0f && pagerState.currentPage == 0) {
                                snapPull()
                                return available
                            }
                            return Velocity.Zero
                        }
                }

                LaunchedEffect(reset) {
                    if (reset > 0) {
                        pagerState.animateScrollToPage(0)
                        closeSearch()
                        if (reopenAgent) {
                            reopenAgent = false
                            showAgent()
                        } else {
                            hideAgent()
                        }
                    }
                }

                BackHandler(enabled = agentOpen || searchOpen || pagerState.currentPage != 0) {
                    if (agentOpen) {
                        if (imeVisible) {
                            focusManager.clearFocus(true)
                            keyboard?.hide()
                        } else {
                            hideAgent()
                        }
                    } else if (searchOpen) {
                        scope.launch { closeSearch() }
                    } else {
                        scope.launch { pagerState.animateScrollToPage(0) }
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().nestedScroll(nestedScroll)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(cfg.colors.background)
                            .graphicsLayer {
                                val p = (pull.value / openPx).coerceIn(0f, 1f)
                                scaleX = 1f - 0.08f * p
                                scaleY = 1f - 0.08f * p
                            },
                    )
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 1,
                        userScrollEnabled = !searchOpen && !agentOpen && !searchShown,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val p = (pull.value / openPx).coerceIn(0f, 1f)
                                scaleX = 1f - 0.08f * p
                                scaleY = 1f - 0.08f * p
                                translationY = 48f * p
                                alpha = 1f - 0.5f * p
                            },
                    ) { page ->
                        if (page == 0) {
                            HomeScreen(
                                recents = recents,
                                onOpen = ::open,
                                onAskAi = { showAgent(focusInput = true) },
                                onDictate = { showAgent(startListen = true) },
                                canvas = DeftApp.instance.homeCanvas,
                                canvasRevision = canvasRevision,
                                tokens = cfg.colors,
                                modifier = Modifier.fillMaxSize(),
                                peekEnabled = searchGesturesReady && !searchOpen && !agentOpen,
                                canvasPaused = canvasPaused,
                                canvasVisible = canvasVisible,
                                onPeekDelta = ::onHomePeekDelta,
                                onPeekEnd = ::onHomePeekFinished,
                                onPeekCancel = ::onHomePeekFinished,
                                chromeModifier = Modifier.pointerInput(Unit) {
                                        detectVerticalDragGestures(
                                            onDragStart = { peekUp = 0f },
                                            onDragCancel = { onHomePeekFinished() },
                                            onDragEnd = { onHomePeekFinished() },
                                            onVerticalDrag = { change, amount ->
                                                change.consume()
                                                onHomePeekDelta(amount)
                                            },
                                        )
                                    },
                            )
                        } else {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background),
                            ) {
                                AllAppsPage(
                                    webApps = apps.filter { it.workspaceSlug == null || it.pinned },
                                    nativeApps = nativeApps,
                                    onOpen = ::open,
                                    onDashboard = {
                                        startActivity(Intent(this@HomeActivity, DashboardActivity::class.java))
                                    },
                                    onSettings = {
                                        startActivity(Intent(Settings.ACTION_SETTINGS))
                                    },
                                    onAi = { showAgent() },
                                    hasApiKey = hasApiKey,
                                    onMyApps = {
                                        startActivity(Intent(this@HomeActivity, MyAppsActivity::class.java))
                                    },
                                )
                            }
                        }
                    }
                    val searchScrim = MaterialTheme.colorScheme.scrim
                    Box(
                        Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val p = (pull.value / openPx).coerceIn(0f, 1f)
                                if (p > 0f) drawRect(searchScrim.copy(alpha = 0.45f * p))
                            },
                    )
                    AppSearchOverlay(
                        progress = { pull.value / openPx },
                        query = query,
                        onQueryChange = { query = it },
                        results = searchResults,
                        imeWanted = imeWanted,
                        interactive = searchShown,
                        onOpen = { item ->
                            scope.launch { closeSearch() }
                            open(item)
                        },
                        onDismiss = { scope.launch { closeSearch() } },
                        onPullDelta = { amount ->
                            val next = (pull.value + amount).coerceIn(0f, openPx * 1.15f)
                            scope.launch(start = CoroutineStart.UNDISPATCHED) { pull.snapTo(next) }
                            if (next == 0f) clearSearchPull()
                        },
                        onPullEnd = { scope.launch { snapPull() } },
                    )
                }
                    if (agentOpen || agentReveal.value > 0.01f) {
                        val agentScrim = remember { MutableInteractionSource() }
                        Box(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f * agentReveal.value))
                                    .clickable(
                                        interactionSource = agentScrim,
                                        indication = null,
                                        onClick = { hideAgent() },
                                    ),
                            )
                            HomeAgentOverlay(
                                hasKey = hasApiKey,
                                autoFocus = agentFocusInput,
                                autoListen = agentStartListen,
                                onSavedKey = { hasApiKey = true },
                                onClose = { hideAgent() },
                                onOpenPreview = { slug ->
                                    startActivity(WorkspaceLaunchActivity.intent(this@HomeActivity, slug))
                                },
                                onPreviewChange = { canvasPaused = it },
                                reveal = agentReveal.value,
                                sheetHintPx = agentSpanPx,
                                onHeaderDragStart = {
                                    snapAgent(agentReveal.value)
                                    focusManager.clearFocus(true)
                                    keyboard?.hide()
                                },
                                onHeaderDrag = { dy ->
                                    snapAgent(agentReveal.value - dy / agentSpanPx.coerceAtLeast(1f))
                                },
                                onHeaderDragEnd = { velocityY ->
                                    if (agentReveal.value < 0.82f || velocityY > 1200f) hideAgent()
                                    else settleAgent(1f, agentSpringIn)
                                },
                                onSheetHeight = { h -> if (h != agentSheetH) agentSheetH = h },
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }
                if (askUrlNotice) {
                    AlertDialog(
                        onDismissRequest = {
                            getSharedPreferences(StorageMigration.PREF, MODE_PRIVATE)
                                .edit()
                                .putBoolean(StorageMigration.PREF_NOTICE, true)
                                .apply()
                            urlAppsNotice.value = false
                        },
                        title = { Text("URL apps removed") },
                        text = {
                            Text(
                                "Web apps installed from a URL are no longer supported. Samples now ship inside this APK. Copy one into My Apps from Settings if you want to edit it. Local projects were moved to private storage.",
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    getSharedPreferences(StorageMigration.PREF, MODE_PRIVATE)
                                        .edit()
                                        .putBoolean(StorageMigration.PREF_NOTICE, true)
                                        .apply()
                                    urlAppsNotice.value = false
                                },
                            ) { Text("OK") }
                        },
                    )
                }
                if (askMigrate) {
                    AlertDialog(
                        onDismissRequest = {
                            StorageMigration.setNeedsPicker(this@HomeActivity, false)
                            migratePickerNeeded.value = false
                        },
                        title = { Text("Import my apps") },
                        text = {
                            Text(
                                "Local apps now live in private storage. Pick your old Documents/myapps folder to copy remaining apps. Originals stay in place.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { migratePicker.launch(null) }) { Text("Pick folder") }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    StorageMigration.setNeedsPicker(this@HomeActivity, false)
                                    migratePickerNeeded.value = false
                                },
                            ) { Text("Not now") }
                        },
                    )
                }
            }
        }
        window.decorView.post { BridgeService.start(this) }
    }

    private fun consumeAgentIntent(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_OPEN_AGENT, false)) return
        intent.getStringExtra(EXTRA_AGENT_TAB)?.let { AgentSession.select(it) }
        openAgent.value = true
    }

    companion object {
        const val EXTRA_OPEN_AGENT = "openAgent"
        const val EXTRA_AGENT_TAB = "agentTab"
        private const val STATE_REOPEN_AGENT = "reopenAgent"
    }
}
