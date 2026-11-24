package app.deft.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun AppIcon(item: Launchable, size: Dp, modifier: Modifier = Modifier) {
    when (item) {
        is Launchable.Native -> {
            val icon = item.app.icon
            if (icon != null && item.app.mono) {
                // Alpha mask from the monochrome layer: tinted with the label colour, no tile behind it.
                Image(
                    bitmap = icon,
                    contentDescription = item.label,
                    modifier = modifier.size(size),
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface, BlendMode.SrcIn),
                )
            } else if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = item.label,
                    modifier = modifier
                        .size(size)
                        .clip(RoundedCornerShape(size * 0.22f)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                letterBadge(item.label, size, modifier)
            }
        }
        is Launchable.Web -> letterBadge(item.label, size, modifier)
    }
}

@Composable
fun AppListRow(item: Launchable, onOpen: () -> Unit) {
    val (click, menu) = rememberShortcutClick(item, onOpen = onOpen)
    Row(
        Modifier
            .fillMaxWidth()
            .then(click)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        menu()
        AppIcon(item, 40.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                item.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun DockSlot(item: Launchable?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val (click, menu) = rememberShortcutClick(item, onOpen = onOpen)
    Column(
        modifier
            .fillMaxWidth()
            .then(click)
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        menu()
        if (item != null) {
            AppIcon(item, 56.dp)
            Text(
                item.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        } else {
            Box(Modifier.size(56.dp))
        }
    }
}

@Composable
private fun letterBadge(label: String, size: Dp, modifier: Modifier) {
    // Neutral fill, not accent (8.1): the accent is reserved for the primary action and one active signal.
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.take(1).uppercase(),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
