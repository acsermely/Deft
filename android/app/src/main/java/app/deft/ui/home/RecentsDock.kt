package app.deft.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import app.deft.ui.theme.LocalDeftConfig

@Composable
fun RecentsDock(
    items: List<Launchable>,
    onOpen: (Launchable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = LocalDeftConfig.current
    val shell = config.shell
    val slots = List(shell.recentsCount) { index -> items.getOrNull(index) }
    val shape = RoundedCornerShape(shell.dockRadius.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 10.dp)
            .glassDock(shape, glassFill(MaterialTheme.colorScheme.surface, shell.dockAlpha, config.opaqueMaterial))
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        slots.forEach { item ->
            DockSlot(
                item,
                onOpen = { item?.let(onOpen) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun HarnessBar(
    onAsk: () -> Unit,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val config = LocalDeftConfig.current
    val pill = glassFill(colors.surface, config.shell.dockAlpha, config.opaqueMaterial)
    val stroke = colors.onSurface.copy(alpha = 0.22f)
    val pillShape = RoundedCornerShape(50)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(52.dp)
                .harnessChrome(pillShape, pill, stroke)
                .clickable(onClick = onAsk)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Ask AI...",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface.copy(alpha = 0.78f),
            )
        }
        Box(
            Modifier
                .size(52.dp)
                .harnessChrome(CircleShape, pill, stroke)
                .clickable(onClick = onMic),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Mic,
                contentDescription = "Dictate",
                tint = colors.onSurface,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private fun Modifier.harnessChrome(shape: Shape, fill: Color, stroke: Color): Modifier =
    shadow(10.dp, shape)
        .clip(shape)
        .background(fill)
        .border(1.dp, stroke, shape)

/**
 * Glass fill for the dock and the harness bar (8.4, 3.3). Mid and high tier keep the user's translucency so the
 * wallpaper reads through. Low tier and Reduce transparency use the opaque 92% material. Content is never glass.
 * Backdrop blur is not applied: the canvas it would sample is the WebView the A16 audit keeps unblurred.
 */
private fun glassFill(surface: Color, dockAlpha: Float, opaque: Boolean): Color =
    surface.copy(alpha = if (opaque) 0.92f else dockAlpha.coerceIn(0.2f, 1f))

/** Glass container: the translucent fill plus the 0.5 dp rim highlight from the material tokens (3.3). */
private fun Modifier.glassDock(shape: Shape, fill: Color): Modifier =
    clip(shape)
        .background(fill)
        .border(0.5.dp, Color.White.copy(alpha = 0.18f), shape)

