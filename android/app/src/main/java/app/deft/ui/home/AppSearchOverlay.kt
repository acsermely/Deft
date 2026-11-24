package app.deft.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import app.deft.ui.theme.LocalDeftConfig
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

@Composable
fun AppSearchOverlay(
    progress: () -> Float,
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<Launchable>,
    imeWanted: Boolean,
    interactive: Boolean,
    onOpen: (Launchable) -> Unit,
    onDismiss: () -> Unit,
    onPullDelta: (Float) -> Unit,
    onPullEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val listState = rememberLazyListState()
    var resultsBottom by remember { mutableStateOf(0.dp) }
    LaunchedEffect(query) {
        listState.scrollToItem(0)
    }
    // Inset padding starts once there are results. Subscribing during the open
    // animation would remeasure this tree on every keyboard frame.
    LaunchedEffect(query.isEmpty()) {
        if (query.isEmpty()) {
            resultsBottom = 0.dp
            return@LaunchedEffect
        }
        snapshotFlow { with(density) { ime.getBottom(this).toDp() } }
            .distinctUntilChanged()
            .collect { resultsBottom = it }
    }
    LaunchedEffect(imeWanted) {
        if (imeWanted) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
            // The input session sometimes isn't attached on the same frame focus flips on.
            withFrameNanos { }
            if (imeWanted) {
                runCatching { focusRequester.requestFocus() }
                keyboard?.show()
            }
        } else {
            focusManager.clearFocus(true)
            keyboard?.hide()
        }
    }
    val center = LocalDeftConfig.current.shell.searchCenter
    Column(
        modifier
            .offset { if (interactive) IntOffset.Zero else IntOffset(0, -100_000) }
            .fillMaxSize()
            .statusBarsPadding()
            .graphicsLayer {
                val p = progress().coerceIn(0f, 1f)
                translationY = -80f * (1f - p)
                val scale = 0.92f + 0.08f * p
                scaleX = scale
                scaleY = scale
                alpha = p
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .then(if (center) Modifier.widthIn(max = 560.dp).fillMaxWidth() else Modifier.fillMaxWidth())
                .focusRequester(focusRequester)
                .focusProperties { canFocus = imeWanted },
            placeholder = { Text("Search apps", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .searchEmptyGesture(
                    touchSlop = touchSlop,
                    onDismiss = onDismiss,
                    onPullDelta = onPullDelta,
                    onPullEnd = onPullEnd,
                ),
        ) {
            if (query.isNotBlank()) {
                if (results.isEmpty()) {
                    Text(
                        "No apps found",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = resultsBottom),
                        modifier = Modifier.fillMaxSize().padding(top = 12.dp),
                    ) {
                        items(results, key = { it.key }) { item ->
                            AppListRow(item) { onOpen(item) }
                        }
                        item(key = "dismiss-space") {
                            Box(
                                Modifier
                                    .fillParentMaxSize()
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                        onClick = onDismiss,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.searchEmptyGesture(
    touchSlop: Float,
    onDismiss: () -> Unit,
    onPullDelta: (Float) -> Unit,
    onPullEnd: () -> Unit,
): Modifier = pointerInput(touchSlop) {
    awaitEachGesture {
        val down = awaitFirstDown()
        var dragging = false
        var total = 0f
        drag(down.id) { change ->
            val dy = change.position.y - change.previousPosition.y
            if (dragging || abs(total + dy) > touchSlop) {
                dragging = true
                total += dy
                change.consume()
                onPullDelta(dy)
            }
        }
        if (dragging) {
            onPullEnd()
        } else {
            onDismiss()
        }
    }
}

fun filterLaunchables(query: String, web: List<Launchable.Web>, native: List<Launchable.Native>): List<Launchable> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return (web + native).filter { item ->
        item.label.lowercase().contains(q) || (item.subtitle?.lowercase()?.contains(q) == true)
    }
}
