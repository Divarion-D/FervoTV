package app.iptvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.iptvplayer.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.iptvplayer.core.input.Action
import app.iptvplayer.core.model.Channel
import app.iptvplayer.initialFocus
import app.iptvplayer.remoteKeys

/**
 * One line of a sheet: [selected] non-null = a choice with a radio dot; [checked] non-null = an on/off option with a
 * switch; [danger] = a destructive action.
 */
class SheetItem(
    @DrawableRes val icon: Int?,
    val label: String,
    val danger: Boolean = false,
    val selected: Boolean? = null,
    val checked: Boolean? = null,
    val onClick: () -> Unit,
)

/** Group caption (null = none) and its lines. */
typealias SheetGroup = Pair<String?, List<SheetItem>>

/**
 * Context menu and "pick one" in one shape (UI-011, 33-design 4.8). On a wide screen it slides in from the right over
 * a scrim, so the element it was opened on stays visible on the left; on a phone it is an M3 bottom sheet. A tap
 * on a line or OK runs it after closing the sheet; Back or a tap outside closes without action. Focus starts on the
 * chosen option (a choice) or the first line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String, channel: Channel?, groups: List<SheetGroup>, wide: Boolean, onDismiss: () -> Unit) {
    val items = groups.flatMap { it.second }
    val start = items.indexOfFirst { it.selected == true }.coerceAtLeast(0)
    val first = remember { FocusRequester() }
    var focused by remember { mutableStateOf<SheetItem?>(null) }
    val run: (SheetItem) -> Unit = { onDismiss(); it.onClick() }
    val content: @Composable () -> Unit = {
        LazyColumn(
            Modifier.remoteKeys { a, long -> if (a == Action.Ok && !long) { focused?.let(run); true } else false },
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Row(Modifier.padding(start = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (channel != null) {
                        ChannelLogo(channel, Modifier.size(40.dp, 25.dp), top = 4.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(title, style = TvType.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            for ((caption, lines) in groups) {
                if (caption != null) item {
                    Text(
                        caption.uppercase(), style = TvType.badge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                for (line in lines) item {
                    val i = items.indexOf(line)
                    SheetLine(line, if (i == start) Modifier.focusRequester(first) else Modifier, { focused = line }) { run(line) }
                }
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos {}
            runCatching { first.requestFocus() }
        }
    }
    if (wide) {
        BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            )
            val shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
            Box(
                Modifier.align(Alignment.CenterEnd).width(320.dp).fillMaxHeight()
                    .shadow(8.dp, shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
                    .padding(top = 8.dp)
            ) { content() }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) { Box(Modifier.navigationBarsPadding()) { content() } }
    }
}

@Composable
private fun SheetLine(item: SheetItem, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fg = when {
        focused -> Ext.onFocus
        item.danger -> c.error
        item.selected == true -> c.primary
        else -> c.onSurface
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(if (focused) Ext.focus else Color.Transparent, RoundedCornerShape(12.dp))
            .onFocusChanged { if (it.isFocused) onFocus() }
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.icon != null) {
            Icon(painterResource(item.icon), contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(item.label, style = TvType.label, color = fg, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (item.checked != null) {
            Switch(
                item.checked, onCheckedChange = null,
                colors = if (focused) SwitchDefaults.colors(checkedTrackColor = Ext.onFocus, checkedThumbColor = Ext.focus) else SwitchDefaults.colors(),
            )
        }
        if (item.selected != null) {
            RadioButton(
                item.selected, onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = fg, unselectedColor = fg),
            )
        }
    }
}

/** Text input in a centered dialog (renaming): [hint] under the field; OK on the keyboard or "Готово" applies. */
@Composable
fun TextDialog(title: String, initial: String, hint: String?, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val shape = CircleShape
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text, { text = it }, Modifier.fillMaxWidth().initialFocus(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDone(text); onDismiss() }),
                )
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text); onDismiss() }, Modifier.focusRing(shape)) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = onDismiss, Modifier.focusRing(shape)) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Confirmation of an action that changes a lot or can't be undone; focus starts on the safe "Отмена". */
@Composable
fun ConfirmDialog(title: String, text: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val shape = CircleShape
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, maxLines = 12, overflow = TextOverflow.Ellipsis) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }, Modifier.focusRing(shape)) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss, Modifier.initialFocus().focusRing(shape)) { Text(stringResource(R.string.cancel)) } },
    )
}
