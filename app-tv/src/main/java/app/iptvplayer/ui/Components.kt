package app.iptvplayer.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import app.iptvplayer.initialFocus
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.iptvplayer.R
import app.iptvplayer.core.qrModules
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.floor

/**
 * Unmistakable focus mark for the remote: M3's own focus state layer is too subtle from a sofa.
 * Put it before the component's own click/focus modifiers (M3 components apply `modifier` first).
 * Always present (transparent when unfocused) so the modifier chain never changes shape.
 */
@Composable
fun Modifier.focusRing(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.isFocused }
        .border(3.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
}

@Composable
fun BackButton(onClick: () -> Unit) =
    IconButton(onClick = onClick, modifier = Modifier.focusRing(RoundedCornerShape(50))) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
    }

/** Icon + text inside a Button, per M3 spec sizes. */
@Composable
fun IconLabel(@DrawableRes icon: Int, text: String) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(text)
}

/** Pick one of [options] (M3 dialog with radio rows). Whole rows are the click/focus targets, for remote and touch. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
        Column {
            for (option in options) {
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (option == selected) Modifier.initialFocus() else Modifier)
                        .focusRing(shape)
                        .clip(shape)
                        .clickable { onSelect(option) }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option == selected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(label(option))
                }
            }
        }
    },
    confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRing(RoundedCornerShape(50))) { Text(stringResource(R.string.cancel)) } },
)

/** Settings-style row: the whole row is the click/focus target, so remote and touch behave the same. */
@Composable
fun SettingItem(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier
            .focusRing(shape)
            .clip(shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = ripple(), onClick = onClick),
    )
}

/** QR code on white with a quiet zone, whole-pixel modules so there are no seams between them. */
@Composable
fun QrCode(text: String, modifier: Modifier) {
    val modules = remember(text) { qrModules(text) }
    Canvas(modifier.background(Color.White)) {
        val n = modules.size + 8 // 4 modules of quiet zone on each side
        val cell = floor(minOf(size.width, size.height) / n)
        val origin = Offset((size.width - cell * modules.size) / 2, (size.height - cell * modules.size) / 2)
        modules.forEachIndexed { y, row ->
            row.forEachIndexed { x, dark ->
                if (dark) drawRect(Color.Black, origin + Offset(x * cell, y * cell), Size(cell, cell))
            }
        }
    }
}
