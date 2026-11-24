package app.deft.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.deft.config.ColorTokens

@Composable
fun HomeScreen(
    recents: List<Launchable>,
    onOpen: (Launchable) -> Unit,
    onAskAi: () -> Unit,
    onDictate: () -> Unit,
    canvas: HomeCanvasStore,
    canvasRevision: Int,
    tokens: ColorTokens,
    chromeModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    peekEnabled: Boolean = true,
    canvasPaused: Boolean = false,
    canvasVisible: Boolean = true,
    onPeekDelta: (Float) -> Unit = {},
    onPeekEnd: () -> Unit = {},
    onPeekCancel: () -> Unit = {},
) {
    val placed by canvas.placedWidgets.collectAsState()
    Box(modifier.fillMaxSize()) {
        HomeCanvas(
            store = canvas,
            revision = canvasRevision,
            tokens = tokens,
            modifier = Modifier.fillMaxSize(),
            peekEnabled = peekEnabled,
            paused = canvasPaused,
            visible = canvasVisible,
            onPeekDelta = onPeekDelta,
            onPeekEnd = onPeekEnd,
            onPeekCancel = onPeekCancel,
        )
        Column(
            chromeModifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            // W1: an app the canvas already shows as an deft-widget is not drawn twice.
            WidgetRow(hiddenApps = placed.mapTo(mutableSetOf()) { it.app }, modifier = Modifier.fillMaxWidth())
            RecentsDock(items = recents, onOpen = onOpen, modifier = Modifier.fillMaxWidth())
            HarnessBar(onAsk = onAskAi, onMic = onDictate, modifier = Modifier.fillMaxWidth())
        }
    }
}
