package app.iptvplayer.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.iptvplayer.R
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.player.PlayerSession
import app.iptvplayer.core.player.VideoScale
import app.iptvplayer.core.player.redactStreamUrl
import app.iptvplayer.initialFocus
import app.iptvplayer.isTv
import kotlinx.coroutines.delay

/** The same timer and technical details in live and VOD, without restarting the session. */
@Composable
fun PlayerTools(
    player: PlayerSession,
    onDialogOpen: (Boolean) -> Unit = {},
    onSeek: (Long) -> Unit = { player.seekTo(player.position + it) },
    onPause: () -> Unit = player::togglePause,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    primaryModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val horizontal = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val state by player.state.collectAsState()
    val remaining by player.sleepRemainingMs.collectAsState()
    var timer by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var audio by remember { mutableStateOf(false) }
    var subtitles by remember { mutableStateOf(false) }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { player.addSubtitle(it.toString(), "text/vtt") }
    }
    DisposableEffect(timer, details, more, audio, subtitles) {
        onDialogOpen(timer || details || more || audio || subtitles)
        onDispose { onDialogOpen(false) }
    }
    val transport: @Composable () -> Unit = {
            if (onPrevious != null) PlayerControlButton(R.drawable.ic_skip_previous, stringResource(R.string.episode_previous), onClick = onPrevious)
            PlayerControlButton(R.drawable.ic_replay_10, stringResource(R.string.seek_back), onClick = { onSeek(-Defaults.SEEK_STEP_MS) })
            PlayerControlButton(
                if (state.error != null) R.drawable.ic_replay else if (state.paused) R.drawable.ic_play_arrow else R.drawable.ic_pause,
                stringResource(if (state.error != null) R.string.retry else if (state.paused) R.string.player_play else R.string.player_pause),
                primary = true, modifier = primaryModifier.initialFocus(),
                onClick = { if (state.error != null) player.retry() else onPause() },
            )
            PlayerControlButton(R.drawable.ic_forward_10, stringResource(R.string.seek_forward), onClick = { onSeek(Defaults.SEEK_STEP_MS) })
            if (onNext != null) PlayerControlButton(R.drawable.ic_skip_next, stringResource(R.string.episode_next), onClick = onNext)
    }
    val options: @Composable () -> Unit = {
        PlayerControlButton(R.drawable.ic_language, stringResource(R.string.player_audio), caption = !horizontal) { audio = true }
        PlayerControlButton(R.drawable.ic_subtitles, stringResource(R.string.player_subtitles), caption = !horizontal) { subtitles = true }
        PlayerControlButton(R.drawable.ic_more, stringResource(R.string.section_more), caption = !horizontal) { more = true }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (horizontal) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            transport()
            Spacer(Modifier.width(8.dp))
            options()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { transport() }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) { options() }
        }
        if (remaining != null) Text(
            stringResource(R.string.player_sleep_remaining, ((remaining!! + 59_999) / 60_000).toInt()),
            style = TvType.badge, color = Ext.now,
            modifier = Modifier.background(Color.White.copy(alpha = 0.1f), CircleShape).clickable { timer = true }.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
    if (more) {
        val sheet: @Composable () -> Unit = { ActionSheet(
        stringResource(R.string.player_controls), null,
        listOf(null to listOf(
            SheetItem(null, context.getString(R.string.player_speed, state.speed)) { player.setSpeed(if (state.speed >= 2f) 0.5f else state.speed + 0.5f) },
            SheetItem(null, context.getString(R.string.player_volume, state.volume)) { player.setVolume(if (state.volume >= 150) 50 else state.volume + 25) },
            SheetItem(null, context.getString(R.string.player_scale, state.scale.name)) { player.setScale(VideoScale.entries[(VideoScale.entries.indexOf(state.scale) + 1) % VideoScale.entries.size]) },
            SheetItem(R.drawable.ic_image, context.getString(if (state.videoEnabled) R.string.player_audio_only else R.string.player_video_on)) { player.setVideoEnabled(!state.videoEnabled) },
            SheetItem(R.drawable.ic_schedule, context.getString(R.string.player_sleep)) { timer = true },
            SheetItem(R.drawable.ic_info, context.getString(R.string.player_stream_info)) { details = true },
            SheetItem(R.drawable.ic_folder_open, context.getString(R.string.player_subtitle_file)) { subtitlePicker.launch(arrayOf("text/vtt", "application/x-subrip", "text/plain")) },
            SheetItem(R.drawable.ic_link, context.getString(R.string.player_external)) {
                player.current?.let { item ->
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.url), "video/*")) }
                        .onFailure { Toast.makeText(context, R.string.player_external_unavailable, Toast.LENGTH_SHORT).show() }
                }
            },
        )), context.isTv(), { more = false },
        ) }
        if (context.isTv()) Dialog(
            onDismissRequest = { more = false }, properties = DialogProperties(usePlatformDefaultWidth = false),
        ) { sheet() } else sheet()
    }
    val unknown = stringResource(R.string.player_info_unknown)
    if (audio && state.audioTracks.isEmpty()) AlertDialog(
        onDismissRequest = { audio = false }, title = { Text(stringResource(R.string.player_audio)) },
        text = { Text(stringResource(R.string.player_no_tracks)) },
        confirmButton = { TextButton(onClick = { audio = false }) { Text(stringResource(R.string.close)) } },
    ) else if (audio) ChoiceDialog(
        stringResource(R.string.player_audio), state.audioTracks.indices.toList(), state.audioTracks.indexOfFirst { it.selected },
        label = { index -> state.audioTracks[index].let { "${it.language ?: unknown} · ${it.codec ?: unknown} · ${it.channels}ch" } },
        onSelect = { player.selectAudioTrack(it); audio = false }, onDismiss = { audio = false },
    )
    if (subtitles) ChoiceDialog(
        stringResource(R.string.player_subtitles), listOf(-1) + state.subtitleTracks.indices, state.subtitleTracks.indexOfFirst { it.selected },
        label = { index -> if (index < 0) context.getString(R.string.player_subtitles_off) else state.subtitleTracks[index].let { "${it.language ?: unknown} · ${it.codec ?: unknown}" } },
        onSelect = { if (it < 0) player.disableSubtitles() else player.selectSubtitleTrack(it); subtitles = false },
        onDismiss = { subtitles = false },
    )
    if (timer) {
        val options = listOf(0, 15, 30, 45, 60, 90)
        ChoiceDialog(
            title = stringResource(R.string.player_sleep), options = options,
            selected = if (remaining == null) 0 else options.firstOrNull { it * 60_000L >= remaining!! } ?: 90,
            label = { if (it == 0) context.getString(R.string.player_sleep_off) else context.getString(R.string.player_sleep_minutes, it) },
            onSelect = { player.setSleepTimer(if (it == 0) null else it * 60_000L); timer = false },
            onDismiss = { timer = false },
        )
    }
    if (details) StreamDetails(player) { details = false }
}

@Composable
private fun PlayerControlButton(
    @DrawableRes icon: Int, label: String, primary: Boolean = false, caption: Boolean = false,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = primary || focused
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier.size(if (primary) 64.dp else 48.dp)
                .background(if (accent) Ext.focus else MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .focusRing(CircleShape).clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), label, tint = if (accent) Ext.onFocus else Color.White, modifier = Modifier.size(if (primary) 32.dp else 24.dp))
        }
        if (caption) Text(label, style = TvType.badge, color = Color.White)
    }
}

@Composable
private fun StreamDetails(player: PlayerSession, onDismiss: () -> Unit) {
    var info by remember(player) { mutableStateOf(player.streamInfo()) }
    LaunchedEffect(player) {
        while (true) {
            info = player.streamInfo()
            delay(1_000)
        }
    }
    val unknown = stringResource(R.string.player_info_unknown)
    fun String?.known() = this?.takeIf { it.isNotBlank() } ?: unknown
    val depth = info?.bitDepth?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.player_info_depth_bits, it, it) } ?: unknown
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_stream_info)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.player_info_video, info?.videoCodec.known(),
                    info?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}×${it.height}" } ?: unknown))
                Text(stringResource(R.string.player_info_fps, info?.fps?.takeIf { it > 0 }?.toString() ?: unknown))
                Text(stringResource(R.string.player_info_bitrate, info?.bitrate?.takeIf { it > 0 }?.let { "${it / 1_000}" } ?: unknown))
                Text(stringResource(R.string.player_info_audio, info?.audioCodec.known(), info?.audioChannels?.takeIf { it > 0 }?.toString() ?: unknown))
                Text(stringResource(R.string.player_info_transfer, info?.hdr.known()))
                Text(stringResource(R.string.player_info_depth, depth))
                Text(stringResource(R.string.player_info_decoder, info?.videoDecoder.known()))
                Text(stringResource(R.string.player_info_buffer, info?.bufferedMs?.let { "${it / 1_000}" } ?: unknown))
                Text(stringResource(R.string.player_info_engine, info?.engine.known()))
                Text(stringResource(R.string.player_info_url, player.current?.url?.let(::redactStreamUrl) ?: unknown))
                Text(stringResource(R.string.player_info_url_hidden))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.initialFocus().focusRing(RoundedCornerShape(50))) {
                Text(stringResource(R.string.close))
            }
        },
    )
}
