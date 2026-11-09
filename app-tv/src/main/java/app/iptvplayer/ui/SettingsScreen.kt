package app.iptvplayer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import app.iptvplayer.R
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.model.PlaylistRefresh
import app.iptvplayer.core.model.Settings
import app.iptvplayer.core.player.PlaybackLog
import app.iptvplayer.core.player.PlayerMemory
import android.widget.Toast
import app.iptvplayer.core.model.ResumeMode
import app.iptvplayer.initialFocus
import app.iptvplayer.isTv
import app.iptvplayer.LANGUAGES
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Row
import app.iptvplayer.core.home.HomeRow
import app.iptvplayer.core.home.HomeRowSetting
import app.iptvplayer.core.home.encodeHomeRows
import app.iptvplayer.core.home.homeRows
import app.iptvplayer.core.model.StartScreen
import app.iptvplayer.core.model.SubtitleBackground
import app.iptvplayer.core.model.SubtitleColor
import app.iptvplayer.core.model.SubtitleFont
import app.iptvplayer.core.model.SubtitlePosition
import app.iptvplayer.core.update.AppRelease

@androidx.annotation.StringRes
private fun StartScreen.title(): Int = when (this) {
    StartScreen.HOME -> R.string.section_home
    StartScreen.LIVE -> R.string.section_live
}

@androidx.annotation.StringRes
fun HomeRow.title(): Int = when (this) {
    HomeRow.CONTINUE -> R.string.home_continue
    HomeRow.FAVORITE_CHANNELS -> R.string.home_favorite_channels
    HomeRow.FAVORITE_MOVIES -> R.string.home_favorite_movies
    HomeRow.FAVORITE_SERIES -> R.string.home_favorite_series
    HomeRow.RECENT_CHANNELS -> R.string.home_recent_channels
}

@androidx.annotation.StringRes private fun SubtitleFont.title() = when (this) {
    SubtitleFont.SANS -> R.string.settings_subtitle_font_sans
    SubtitleFont.SERIF -> R.string.settings_subtitle_font_serif
    SubtitleFont.MONO -> R.string.settings_subtitle_font_mono
}
@androidx.annotation.StringRes private fun SubtitleColor.title() = when (this) {
    SubtitleColor.WHITE -> R.string.settings_subtitle_color_white
    SubtitleColor.YELLOW -> R.string.settings_subtitle_color_yellow
    SubtitleColor.CYAN -> R.string.settings_subtitle_color_cyan
}
@androidx.annotation.StringRes private fun SubtitleBackground.title() = when (this) {
    SubtitleBackground.NONE -> R.string.settings_subtitle_background_none
    SubtitleBackground.BLACK -> R.string.settings_subtitle_background_black
    SubtitleBackground.TRANSLUCENT -> R.string.settings_subtitle_background_translucent
}
@androidx.annotation.StringRes private fun SubtitlePosition.title() = when (this) {
    SubtitlePosition.TOP -> R.string.settings_subtitle_position_top
    SubtitlePosition.CENTER -> R.string.settings_subtitle_position_center
    SubtitlePosition.BOTTOM -> R.string.settings_subtitle_position_bottom
}

internal enum class SettingsGroup(
    @androidx.annotation.StringRes val title: Int,
    @androidx.annotation.DrawableRes val icon: Int,
) {
    PLAYLIST(R.string.settings_playlist, R.drawable.ic_playlist_play),
    HOME(R.string.settings_home, R.drawable.ic_home),
    LIVE(R.string.settings_live, R.drawable.ic_live_tv),
    LIBRARY(R.string.settings_library, R.drawable.ic_play_arrow),
    PLAYER(R.string.settings_player, R.drawable.ic_play_arrow),
    LOGOS(R.string.settings_logos, R.drawable.ic_image),
    INTERFACE(R.string.settings_interface, R.drawable.ic_language),
    FILE(R.string.settings_file, R.drawable.ic_save),
    ABOUT(R.string.settings_about, R.drawable.ic_info),
}

/** New options: add a field to [Settings] + its default to [Defaults], then a [SettingItem] here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    sourceLabel: String,
    logoStatus: String,
    playlistStatus: String,
    refreshing: Boolean,
    onChange: (Settings) -> Unit,
    onEditSource: () -> Unit,
    onRefreshNow: () -> Unit,
    updateStatus: String,
    updateChecking: Boolean,
    updateInstalling: Boolean,
    availableUpdate: AppRelease?,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: (AppRelease) -> Unit,
    onDismissUpdate: () -> Unit,
    onSaveFile: () -> Unit,
    onLoadFile: () -> Unit,
    playerMemory: PlayerMemory,
    onClose: () -> Unit,
) {
    // Row to put the focus back on after a subpage, a dialog or the source editor (UI-005); saved across the editor.
    var lastRow by rememberSaveable { mutableStateOf("refresh") }
    var subpage by remember { mutableStateOf<Pair<Int, String>?>(null) } // title to asset
    subpage?.let { (title, asset) ->
        DocScreen(stringResource(title), asset, onClose = { subpage = null })
        return
    }
    var refreshDialog by remember { mutableStateOf(false) }
    var daysDialog by remember { mutableStateOf(false) }
    var languageDialog by remember { mutableStateOf(false) }
    var resumeDialog by remember { mutableStateOf(false) }
    var playerGiveUpDialog by remember { mutableStateOf(false) }
    var playerReset by remember { mutableStateOf<Pair<List<PlayerMemory.Kind>, Boolean>?>(null) }
    var playerCounts by remember(playerMemory) { mutableStateOf(playerMemory.counts()) }
    var startDialog by remember { mutableStateOf(false) }
    var homeMoving by remember { mutableStateOf<HomeRow?>(null) }
    var groupName by rememberSaveable { mutableStateOf<String?>(null) }
    val group = groupName?.let(SettingsGroup::valueOf)
    BackHandler(onBack = { if (group == null) onClose() else groupName = null })
    val ctx = LocalContext.current
    val rows = remember { mutableMapOf<String, FocusRequester>() }
    fun Modifier.row(key: String) =
        focusRequester(rows.getOrPut(key) { FocusRequester() }).onFocusChanged { if (it.isFocused) lastRow = key }
    // TV: focus on the first row when opened, on the row we left from when back.
    val tv = remember { ctx.isTv() }
    LaunchedEffect(refreshDialog, daysDialog, languageDialog, resumeDialog, playerGiveUpDialog, startDialog) {
        if (!tv || refreshDialog || daysDialog || languageDialog || resumeDialog || playerGiveUpDialog || startDialog) return@LaunchedEffect
        withFrameNanos {}
        runCatching { rows[lastRow]?.requestFocus() }
    }
    // Edited locally and applied by button, so the logo index isn't rebuilt on every keystroke.
    var logoSource by remember(settings.logoSource) { mutableStateOf(settings.logoSource) }
    val resetLogoSource = {
        logoSource = Defaults.LOGO_SOURCE
        onChange(settings.copy(logoSource = Defaults.LOGO_SOURCE))
    }

    if (group == null) {
        SettingsGroupList(onOpen = { groupName = it.name }, onClose = onClose)
        return
    }

    homeMoving?.let { start ->
        val home = homeRows(settings.homeRows, settings.homeChannels)
        ReorderMode(
            stringResource(R.string.settings_home),
            home.map { ReorderEntry(it.row.name, stringResource(it.row.title())) },
            start.name,
            onSave = { order ->
                val byName = home.associateBy { it.row.name }
                onChange(settings.copy(homeRows = encodeHomeRows(order.mapNotNull(byName::get))))
                homeMoving = null
            },
            onCancel = { homeMoving = null },
        )
        return
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopAppBar(
            title = { Text(stringResource(group.title)) },
            navigationIcon = { BackButton { groupName = null } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (group == SettingsGroup.PLAYLIST) {
            SectionTitle(stringResource(R.string.settings_playlist))
            SettingItem(
                R.drawable.ic_refresh, stringResource(R.string.settings_refresh_now), playlistStatus.ifEmpty { null },
                Modifier.row("refresh"),
                trailing = if (refreshing) {
                    { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                } else null,
            ) { if (!refreshing) onRefreshNow() }
            SettingItem(R.drawable.ic_playlist_play, stringResource(R.string.settings_source), sourceLabel, Modifier.row("source"), onClick = onEditSource)
            SettingItem(
                R.drawable.ic_schedule, stringResource(R.string.settings_auto_refresh),
                stringResource(R.string.settings_auto_refresh_summary, settings.playlistRefresh.label(ctx, settings.refreshDays)),
                Modifier.row("schedule"),
            ) { refreshDialog = true }
            if (settings.playlistRefresh == PlaylistRefresh.EVERY_N_DAYS) {
                SettingItem(
                    R.drawable.ic_schedule, stringResource(R.string.settings_refresh_interval), daysText(ctx, settings.refreshDays), Modifier.row("days"),
                ) { daysDialog = true }
            }
            if (refreshDialog) {
                ChoiceDialog(
                    title = stringResource(R.string.settings_refresh_title),
                    options = PlaylistRefresh.entries,
                    selected = settings.playlistRefresh,
                    label = { it.label(ctx, settings.refreshDays) },
                    onSelect = {
                        onChange(settings.copy(playlistRefresh = it))
                        refreshDialog = false
                        if (it == PlaylistRefresh.EVERY_N_DAYS) daysDialog = true
                    },
                    onDismiss = { refreshDialog = false },
                )
            }
            if (daysDialog) {
                ChoiceDialog(
                    title = stringResource(R.string.settings_refresh_every),
                    options = Defaults.PLAYLIST_REFRESH_DAYS_OPTIONS,
                    selected = settings.refreshDays,
                    label = { daysText(ctx, it) },
                    onSelect = { onChange(settings.copy(refreshDays = it)); daysDialog = false },
                    onDismiss = { daysDialog = false },
                )
            }
            }

            // HOME-001, HOME-005
            if (group == SettingsGroup.HOME) {
            SectionTitle(stringResource(R.string.settings_home))
            SettingItem(
                R.drawable.ic_home, stringResource(R.string.settings_home_visible), stringResource(R.string.settings_home_visible_summary),
                Modifier.row("homeVisible"), trailing = { Switch(settings.homeVisible, onCheckedChange = null) },
            ) {
                val visible = !settings.homeVisible
                onChange(settings.copy(homeVisible = visible, startScreen = if (visible) settings.startScreen else StartScreen.LIVE))
            }
            if (settings.homeVisible) {
            SettingItem(
                R.drawable.ic_play_arrow, stringResource(R.string.settings_home_preview), stringResource(R.string.settings_home_preview_summary),
                Modifier.row("homePreview"), trailing = { Switch(settings.homePreview, onCheckedChange = null) },
            ) { onChange(settings.copy(homePreview = !settings.homePreview)) }
            SettingItem(
                R.drawable.ic_home, stringResource(R.string.settings_start), stringResource(settings.startScreen.title()), Modifier.row("startScreen"),
            ) { startDialog = true }
            if (startDialog) {
                ChoiceDialog(
                    title = stringResource(R.string.settings_start),
                    options = StartScreen.entries,
                    selected = settings.startScreen,
                    label = { ctx.getString(it.title()) },
                    onSelect = { onChange(settings.copy(startScreen = it)); startDialog = false },
                    onDismiss = { startDialog = false },
                )
            }
            SettingItem(
                R.drawable.ic_history, stringResource(R.string.settings_home_channels), stringResource(R.string.settings_home_channels_summary),
                Modifier.row("homeChannels"), trailing = { Switch(settings.homeChannels, onCheckedChange = null) },
            ) { onChange(settings.copy(homeChannels = !settings.homeChannels)) }
            SettingItem(
                R.drawable.ic_guide, stringResource(R.string.settings_home_guide), stringResource(R.string.settings_home_guide_summary),
                Modifier.row("homeGuide"), trailing = { Switch(settings.homeGuide, onCheckedChange = null) },
            ) { onChange(settings.copy(homeGuide = !settings.homeGuide)) }
            // The rows: on/off by OK; the shared LIVE-013 screen changes their order as one draft.
            val rows = homeRows(settings.homeRows, settings.homeChannels)
            rows.forEachIndexed { i, r ->
                fun save(list: List<HomeRowSetting>) = onChange(settings.copy(homeRows = encodeHomeRows(list)))
                SettingItem(
                    R.drawable.ic_view_list, stringResource(r.row.title()), stringResource(R.string.settings_home_row_summary, i + 1),
                    Modifier.row("home_" + r.row.name),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ToolButton(R.drawable.ic_swap_vert, stringResource(R.string.fav_move)) { homeMoving = r.row }
                            Switch(r.on, onCheckedChange = null)
                        }
                    },
                ) { save(rows.map { if (it.row == r.row) it.copy(on = !it.on) else it }) }
            }
            }
            }

            if (group == SettingsGroup.LIVE) {
            SectionTitle(stringResource(R.string.settings_live))
            SettingItem(
                R.drawable.ic_live_tv, stringResource(R.string.settings_preview), stringResource(R.string.settings_preview_summary),
                Modifier.row("preview"), trailing = { Switch(settings.preview, onCheckedChange = null) },
            ) { onChange(settings.copy(preview = !settings.preview)) }
            if (settings.preview) SettingItem(
                R.drawable.ic_live_tv, stringResource(R.string.settings_preview_sound), stringResource(R.string.settings_preview_sound_summary),
                Modifier.row("previewSound"), trailing = { Switch(settings.previewSound, onCheckedChange = null) },
            ) { onChange(settings.copy(previewSound = !settings.previewSound)) }
            SettingItem(
                R.drawable.ic_history, stringResource(R.string.settings_remember), stringResource(R.string.settings_remember_summary),
                Modifier.row("rememberLive"), trailing = { Switch(settings.rememberLive, onCheckedChange = null) },
            ) { onChange(settings.copy(rememberLive = !settings.rememberLive)) }
            SettingItem(
                R.drawable.ic_guide, stringResource(R.string.settings_open_guide), stringResource(R.string.settings_open_guide_summary),
                Modifier.row("liveGuide"), trailing = { Switch(settings.liveGuide, onCheckedChange = null) },
            ) { onChange(settings.copy(liveGuide = !settings.liveGuide)) }
            }

            if (group == SettingsGroup.LIBRARY) {
            SectionTitle(stringResource(R.string.settings_library))
            SettingItem(
                R.drawable.ic_play_arrow, stringResource(R.string.settings_resume), stringResource(settings.resume.title()),
                Modifier.row("resume"),
            ) { resumeDialog = true }
            if (resumeDialog) {
                ChoiceDialog(
                    title = stringResource(R.string.settings_resume),
                    options = ResumeMode.entries,
                    selected = settings.resume,
                    label = { ctx.getString(it.title()) },
                    onSelect = { onChange(settings.copy(resume = it)); resumeDialog = false },
                    onDismiss = { resumeDialog = false },
                )
            }
            SettingItem(
                R.drawable.ic_skip_next, stringResource(R.string.settings_auto_next), stringResource(R.string.settings_auto_next_summary),
                Modifier.row("autoNext"), trailing = { Switch(settings.autoNext, onCheckedChange = null) },
            ) { onChange(settings.copy(autoNext = !settings.autoNext)) }
            }

            if (group == SettingsGroup.PLAYER) {
            SectionTitle(stringResource(R.string.settings_player))
            SettingItem(
                R.drawable.ic_play_arrow, stringResource(R.string.settings_player_give_up),
                stringResource(R.string.settings_player_give_up_summary, settings.playerGiveUpSeconds), Modifier.row("playerGiveUp"),
            ) { playerGiveUpDialog = true }
            if (playerGiveUpDialog) ChoiceDialog(
                title = stringResource(R.string.settings_player_give_up),
                options = listOf(0, 15, 30, 60), selected = settings.playerGiveUpSeconds,
                label = { if (it == 0) ctx.getString(R.string.settings_player_give_up_summary, 0) else ctx.getString(R.string.settings_player_give_up_summary, it) },
                onSelect = { onChange(settings.copy(playerGiveUpSeconds = it)); playerGiveUpDialog = false },
                onDismiss = { playerGiveUpDialog = false },
            )
            SettingItem(
                R.drawable.ic_refresh, stringResource(R.string.settings_player_reconnect),
                stringResource(R.string.settings_player_reconnect_summary), Modifier.row("playerReconnect"),
            ) { onChange(settings.copy(playerReconnectAttempts = (settings.playerReconnectAttempts + 1).mod(11))) }
            var audioLanguages by remember(settings.playerAudioLanguages) { mutableStateOf(settings.playerAudioLanguages) }
            OutlinedTextField(audioLanguages, { audioLanguages = it; onChange(settings.copy(playerAudioLanguages = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.settings_player_audio_languages)) }, placeholder = { Text(stringResource(R.string.settings_player_languages_hint)) })
            SettingItem(
                R.drawable.ic_play_arrow, stringResource(R.string.settings_player_software_audio), stringResource(R.string.settings_player_software_audio_summary), Modifier.row("playerSoftwareAudio"), trailing = { Switch(settings.playerSoftwareAudio, onCheckedChange = null) },
            ) { onChange(settings.copy(playerSoftwareAudio = !settings.playerSoftwareAudio)) }
            var subtitleLanguages by remember(settings.playerSubtitleLanguages) { mutableStateOf(settings.playerSubtitleLanguages) }
            OutlinedTextField(subtitleLanguages, { subtitleLanguages = it; onChange(settings.copy(playerSubtitleLanguages = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.settings_player_subtitle_languages)) }, placeholder = { Text(stringResource(R.string.settings_player_languages_hint)) })
            SettingItem(
                R.drawable.ic_live_tv, stringResource(R.string.settings_player_match_frame_rate), stringResource(R.string.settings_player_match_frame_rate_summary), Modifier.row("playerMatchFrameRate"), trailing = { Switch(settings.playerMatchFrameRate, onCheckedChange = null) },
            ) { onChange(settings.copy(playerMatchFrameRate = !settings.playerMatchFrameRate)) }
            SettingItem(
                R.drawable.ic_play_arrow, stringResource(R.string.settings_player_audio_only), stringResource(R.string.settings_player_audio_only_summary), Modifier.row("playerAudioOnly"), trailing = { Switch(settings.playerAudioOnly, onCheckedChange = null) },
            ) { onChange(settings.copy(playerAudioOnly = !settings.playerAudioOnly)) }
            SettingItem(
                R.drawable.ic_play_arrow, stringResource(R.string.settings_player_volume), stringResource(R.string.settings_player_volume_summary, settings.playerVolume), Modifier.row("playerVolume"),
            ) { onChange(settings.copy(playerVolume = if (settings.playerVolume >= 150) 50 else settings.playerVolume + 25)) }
            SettingItem(
                R.drawable.ic_play_arrow, stringResource(R.string.settings_player_night_mode), stringResource(R.string.settings_player_night_mode_summary), Modifier.row("playerNightMode"), trailing = { Switch(settings.playerNightMode, onCheckedChange = null) },
            ) { onChange(settings.copy(playerNightMode = !settings.playerNightMode)) }
            SettingItem(
                R.drawable.ic_play_arrow, stringResource(R.string.settings_player_normalize), stringResource(R.string.settings_player_normalize_summary), Modifier.row("playerNormalizeVolume"), trailing = { Switch(settings.playerNormalizeVolume, onCheckedChange = null) },
            ) { onChange(settings.copy(playerNormalizeVolume = !settings.playerNormalizeVolume)) }
            SettingItem(
                R.drawable.ic_info, stringResource(R.string.settings_player_verbose_log), stringResource(R.string.settings_player_verbose_log_summary), Modifier.row("playerVerboseLogging"), trailing = { Switch(settings.playerVerboseLogging, onCheckedChange = null) },
            ) { onChange(settings.copy(playerVerboseLogging = !settings.playerVerboseLogging)) }
            SettingItem(
                R.drawable.ic_info, stringResource(R.string.settings_player_measured_metrics), stringResource(R.string.settings_player_measured_metrics_summary), Modifier.row("playerMeasuredMetrics"), trailing = { Switch(settings.playerMeasuredMetrics, onCheckedChange = null) },
            ) { onChange(settings.copy(playerMeasuredMetrics = !settings.playerMeasuredMetrics)) }
            SettingItem(
                R.drawable.ic_link, stringResource(R.string.settings_player_external_live), stringResource(R.string.settings_player_external_summary), Modifier.row("playerExternalLive"), trailing = { Switch(settings.playerExternalLive, onCheckedChange = null) },
            ) { onChange(settings.copy(playerExternalLive = !settings.playerExternalLive)) }
            SettingItem(
                R.drawable.ic_link, stringResource(R.string.settings_player_external_movies), stringResource(R.string.settings_player_external_summary), Modifier.row("playerExternalMovies"), trailing = { Switch(settings.playerExternalMovies, onCheckedChange = null) },
            ) { onChange(settings.copy(playerExternalMovies = !settings.playerExternalMovies)) }
            SettingItem(
                R.drawable.ic_link, stringResource(R.string.settings_player_external_series), stringResource(R.string.settings_player_external_summary), Modifier.row("playerExternalSeries"), trailing = { Switch(settings.playerExternalSeries, onCheckedChange = null) },
            ) { onChange(settings.copy(playerExternalSeries = !settings.playerExternalSeries)) }
            SettingItem(
                R.drawable.ic_forward_10, stringResource(R.string.settings_player_seek_step), stringResource(R.string.settings_player_seek_step_summary, settings.playerSeekSeconds), Modifier.row("playerSeekSeconds"),
            ) {
                val values = listOf(5, 10, 15, 30, 60)
                onChange(settings.copy(playerSeekSeconds = values[(values.indexOf(settings.playerSeekSeconds) + 1).mod(values.size)]))
            }
            SettingItem(
                R.drawable.ic_refresh, stringResource(R.string.settings_player_min_buffer), stringResource(R.string.settings_player_buffer_summary, settings.playerMinBufferSeconds), Modifier.row("playerMinBuffer"),
            ) { onChange(settings.copy(playerMinBufferSeconds = if (settings.playerMinBufferSeconds >= 30) 5 else settings.playerMinBufferSeconds + 5)) }
            SettingItem(
                R.drawable.ic_refresh, stringResource(R.string.settings_player_max_buffer), stringResource(R.string.settings_player_buffer_summary, settings.playerMaxBufferSeconds), Modifier.row("playerMaxBuffer"),
            ) { onChange(settings.copy(playerMaxBufferSeconds = if (settings.playerMaxBufferSeconds >= 120) 30 else settings.playerMaxBufferSeconds + 30)) }
            SettingItem(
                R.drawable.ic_refresh, stringResource(R.string.settings_player_prebuffer), stringResource(R.string.settings_player_buffer_summary, settings.playerPrebufferSeconds), Modifier.row("playerPrebuffer"),
            ) { onChange(settings.copy(playerPrebufferSeconds = if (settings.playerPrebufferSeconds >= 10) 0 else settings.playerPrebufferSeconds + 2)) }
            SettingItem(
                R.drawable.ic_image, stringResource(R.string.settings_player_max_quality),
                if (settings.playerMaxVideoHeight == 0) stringResource(R.string.settings_player_quality_auto) else "${settings.playerMaxVideoHeight}p",
                Modifier.row("playerMaxVideoHeight"),
            ) {
                val values = listOf(0, 720, 1080, 2160)
                onChange(settings.copy(playerMaxVideoHeight = values[(values.indexOf(settings.playerMaxVideoHeight) + 1).mod(values.size)]))
            }
            SettingItem(
                R.drawable.ic_refresh, stringResource(R.string.settings_player_audio_delay),
                stringResource(R.string.settings_player_audio_delay_summary, settings.playerAudioDelayMs),
                Modifier.row("playerAudioDelay"),
            ) { onChange(settings.copy(playerAudioDelayMs = if (settings.playerAudioDelayMs >= 500) -500 else settings.playerAudioDelayMs + 25)) }
            SettingItem(
                R.drawable.ic_subtitles, stringResource(R.string.settings_subtitle_file_style), null,
                Modifier.row("playerSubtitleEmbeddedStyles"), trailing = { Switch(settings.playerSubtitleEmbeddedStyles, onCheckedChange = null) },
            ) { onChange(settings.copy(playerSubtitleEmbeddedStyles = !settings.playerSubtitleEmbeddedStyles)) }
            SettingItem(R.drawable.ic_subtitles, stringResource(R.string.settings_subtitle_font), stringResource(settings.playerSubtitleFont.title()), Modifier.row("playerSubtitleFont")) {
                val values = SubtitleFont.entries
                onChange(settings.copy(playerSubtitleFont = values[(settings.playerSubtitleFont.ordinal + 1).mod(values.size)]))
            }
            SettingItem(R.drawable.ic_subtitles, stringResource(R.string.settings_subtitle_size), "${settings.playerSubtitleSize}%", Modifier.row("playerSubtitleSize")) {
                onChange(settings.copy(playerSubtitleSize = if (settings.playerSubtitleSize >= 200) 50 else settings.playerSubtitleSize + 25))
            }
            SettingItem(R.drawable.ic_subtitles, stringResource(R.string.settings_subtitle_color), stringResource(settings.playerSubtitleColor.title()), Modifier.row("playerSubtitleColor")) {
                val values = SubtitleColor.entries
                onChange(settings.copy(playerSubtitleColor = values[(settings.playerSubtitleColor.ordinal + 1).mod(values.size)]))
            }
            SettingItem(R.drawable.ic_subtitles, stringResource(R.string.settings_subtitle_background), stringResource(settings.playerSubtitleBackground.title()), Modifier.row("playerSubtitleBackground")) {
                val values = SubtitleBackground.entries
                onChange(settings.copy(playerSubtitleBackground = values[(settings.playerSubtitleBackground.ordinal + 1).mod(values.size)]))
            }
            SettingItem(R.drawable.ic_subtitles, stringResource(R.string.settings_subtitle_position), stringResource(settings.playerSubtitlePosition.title()), Modifier.row("playerSubtitlePosition")) {
                val values = SubtitlePosition.entries
                onChange(settings.copy(playerSubtitlePosition = values[(settings.playerSubtitlePosition.ordinal + 1).mod(values.size)]))
            }
            Box(
                Modifier.fillMaxWidth().height(120.dp).background(Color.Black).padding(8.dp),
                contentAlignment = when (settings.playerSubtitlePosition) {
                    SubtitlePosition.TOP -> Alignment.TopCenter
                    SubtitlePosition.CENTER -> Alignment.Center
                    SubtitlePosition.BOTTOM -> Alignment.BottomCenter
                },
            ) {
                Text(
                    stringResource(R.string.settings_subtitle_example),
                    color = when (settings.playerSubtitleColor) { SubtitleColor.WHITE -> Color.White; SubtitleColor.YELLOW -> Color.Yellow; SubtitleColor.CYAN -> Color.Cyan },
                    fontFamily = when (settings.playerSubtitleFont) { SubtitleFont.SANS -> FontFamily.SansSerif; SubtitleFont.SERIF -> FontFamily.Serif; SubtitleFont.MONO -> FontFamily.Monospace },
                    fontSize = (20 * settings.playerSubtitleSize / 100f).sp,
                    modifier = Modifier.background(when (settings.playerSubtitleBackground) { SubtitleBackground.NONE -> Color.Transparent; SubtitleBackground.BLACK -> Color.Black; SubtitleBackground.TRANSLUCENT -> Color.Black.copy(alpha = .6f) }).padding(4.dp),
                )
            }
            listOf(
                Triple(R.string.settings_player_reset_engine, playerCounts.engine, listOf(PlayerMemory.Kind.ENGINE) to false),
                Triple(R.string.settings_player_reset_live_engine, playerCounts.liveEngine, listOf(PlayerMemory.Kind.ENGINE) to true),
                Triple(R.string.settings_player_reset_live_format, playerCounts.format, listOf(PlayerMemory.Kind.FORMAT) to true),
                Triple(R.string.settings_player_reset_scale, playerCounts.scale, listOf(PlayerMemory.Kind.SCALE) to false),
                Triple(R.string.settings_player_reset_volume, playerCounts.volume, listOf(PlayerMemory.Kind.VOLUME) to false),
                Triple(R.string.settings_player_reset_audio_delay, playerCounts.audioDelay, listOf(PlayerMemory.Kind.AUDIO_DELAY) to false),
                Triple(R.string.settings_player_reset_audio_tracks, playerCounts.audioTrack, listOf(PlayerMemory.Kind.AUDIO_TRACK) to false),
                Triple(R.string.settings_player_reset_subtitle_tracks, playerCounts.subtitleTrack, listOf(PlayerMemory.Kind.SUBTITLE_TRACK) to false),
            ).forEach { (title, count, action) ->
                SettingItem(
                    R.drawable.ic_refresh, stringResource(title), stringResource(R.string.settings_player_reset_count, count),
                    Modifier.row("playerReset$title"),
                ) { playerReset = action }
            }
            }

            if (group == SettingsGroup.LOGOS) {
            SectionTitle(stringResource(R.string.settings_logos))
            SettingItem(
                R.drawable.ic_image,
                stringResource(R.string.settings_auto_logos),
                stringResource(R.string.settings_auto_logos_summary),
                Modifier.row("autoLogos"),
                trailing = { Switch(settings.autoLogos, onCheckedChange = null) },
            ) { onChange(settings.copy(autoLogos = !settings.autoLogos)) }
            OutlinedTextField(
                logoSource, { logoSource = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(stringResource(R.string.settings_logo_source)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_link), contentDescription = null) },
                trailingIcon = if (logoSource != Defaults.LOGO_SOURCE) {
                    { IconButton(onClick = resetLogoSource) { Icon(painterResource(R.drawable.ic_restart_alt), contentDescription = stringResource(R.string.by_default)) } }
                } else null,
                supportingText = { Text(logoStatus) },
                singleLine = true,
                enabled = settings.autoLogos,
                shape = RoundedCornerShape(12.dp),
            )
            if (settings.autoLogos && logoSource.trim() != settings.logoSource) {
                FilledTonalButton(
                    onClick = { onChange(settings.copy(logoSource = logoSource.trim())) },
                    modifier = Modifier.focusRing(CircleShape),
                ) { IconLabel(R.drawable.ic_check, stringResource(R.string.apply)) }
            }
            }

            if (group == SettingsGroup.INTERFACE) {
            SectionTitle(stringResource(R.string.settings_interface))
            SettingItem(
                R.drawable.ic_language, stringResource(R.string.settings_language), languageName(settings.language), Modifier.row("language"),
            ) { languageDialog = true }
            if (languageDialog) {
                ChoiceDialog(
                    title = stringResource(R.string.settings_language),
                    options = LANGUAGES,
                    selected = settings.language.takeIf { it in LANGUAGES } ?: "",
                    label = { languageName(ctx, it) },
                    onSelect = { onChange(settings.copy(language = it)); languageDialog = false },
                    onDismiss = { languageDialog = false },
                )
            }
            }

            // BAK-011
            if (group == SettingsGroup.FILE) {
            SectionTitle(stringResource(R.string.settings_file))
            SettingItem(
                R.drawable.ic_save, stringResource(R.string.settings_file_save), stringResource(R.string.settings_file_save_summary),
                Modifier.row("saveFile"), onClick = onSaveFile,
            )
            SettingItem(
                R.drawable.ic_folder_open, stringResource(R.string.settings_file_load), stringResource(R.string.settings_file_load_summary),
                Modifier.row("loadFile"), onClick = onLoadFile,
            )
            }

            if (group == SettingsGroup.ABOUT) {
            SectionTitle(stringResource(R.string.settings_about))
            SettingItem(R.drawable.ic_info, stringResource(R.string.settings_version), versionLabel(ctx), Modifier.row("version")) {}
            SettingItem(
                R.drawable.ic_refresh, stringResource(R.string.update_check), updateStatus.ifEmpty { null }, Modifier.row("update"),
                trailing = if (updateChecking) { { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) } } else null,
                onClick = onCheckUpdate,
            )
            SettingItem(R.drawable.ic_person, stringResource(R.string.settings_author), Defaults.AUTHOR, Modifier.row("author")) {}
            SettingItem(R.drawable.ic_code, stringResource(R.string.settings_github), Defaults.GITHUB_URL, Modifier.row("github")) {
                openUrl(ctx, Defaults.GITHUB_URL)
            }
            SettingItem(R.drawable.ic_history, stringResource(R.string.whats_new), stringResource(R.string.settings_whats_new_summary), Modifier.row("changelog")) {
                subpage = R.string.whats_new to "CHANGELOG.md"
            }
            SettingItem(
                R.drawable.ic_info, stringResource(R.string.settings_licenses), stringResource(R.string.settings_licenses_summary), Modifier.row("licenses"),
            ) { subpage = R.string.settings_licenses to "LICENSES.md" }
            SettingItem(
                R.drawable.ic_save, stringResource(R.string.settings_playback_log), stringResource(R.string.settings_playback_log_summary), Modifier.row("playbackLog"),
            ) {
                val file = PlaybackLog(ctx).exportToDownloads()
                Toast.makeText(ctx, if (file == null) R.string.settings_playback_log_failed else R.string.settings_playback_log_saved, Toast.LENGTH_LONG).show()
            }
            }
        }
    }
    playerReset?.let { (kinds, liveOnly) ->
        AlertDialog(
            onDismissRequest = { playerReset = null },
            title = { Text(stringResource(R.string.settings_player_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    kinds.forEach { playerMemory.reset(it, liveOnly) }
                    playerCounts = playerMemory.counts()
                    playerReset = null
                }) { Text(stringResource(R.string.settings_player_reset_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { playerReset = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    availableUpdate?.let { release ->
        AlertDialog(
            onDismissRequest = onDismissUpdate,
            title = { Text(stringResource(R.string.update_available_title, release.version)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(release.title, style = MaterialTheme.typography.titleMedium)
                    Text(release.notes.ifBlank { stringResource(R.string.update_no_notes) })
                }
            },
            confirmButton = {
                TextButton(onClick = { onInstallUpdate(release) }, enabled = !updateInstalling) {
                    Text(stringResource(if (updateInstalling) R.string.update_downloading else R.string.update_install))
                }
            },
            dismissButton = { TextButton(onClick = onDismissUpdate) { Text(stringResource(R.string.later)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsGroupList(onOpen: (SettingsGroup) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopAppBar(
            title = { Text(stringResource(R.string.section_settings)) },
            navigationIcon = { BackButton(onClose) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (group in SettingsGroup.entries) {
                SettingItem(
                    group.icon,
                    stringResource(group.title),
                    null,
                    onClick = { onOpen(group) },
                )
            }
        }
    }
}

@Composable
private fun languageName(tag: String): String = languageName(LocalContext.current, tag)

private fun languageName(ctx: Context, tag: String): String = when (tag) {
    "ru" -> ctx.getString(R.string.language_ru)
    "en" -> ctx.getString(R.string.language_en)
    else -> ctx.getString(R.string.language_system)
}

/** "0.1 · build 1 · debug" — from the installed package, so it always matches build.gradle.kts. */
private fun versionLabel(ctx: Context): String {
    @Suppress("DEPRECATION") // getPackageInfo(String, PackageInfoFlags) is API 33+
    val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    @Suppress("DEPRECATION") // longVersionCode is API 28+
    val code = info.versionCode
    val debug = ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    return ctx.getString(R.string.settings_version_value, info.versionName, code) + if (debug) " · debug" else ""
}

/** Without a browser the repository URL remains visible in the settings row. */
private fun openUrl(ctx: Context, url: String) = try {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
} catch (_: ActivityNotFoundException) {
}

/**
 * A text page from assets (CHANGELOG.md, LICENSES.md): "## " headings, "- " items, other lines are paragraphs
 * (consecutive lines joined, a blank line ends one); "# title" duplicates the app bar and is skipped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocScreen(title: String, asset: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val blocks = remember(asset) { ctx.assets.open(asset).bufferedReader().use { docBlocks(it.readLines()) } }
    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { BackButton(onClose) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                // Focusable so the remote can scroll the text with the D-pad.
                .initialFocus()
                .focusable()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // UI-022: the change log and the licences are kept in Russian only.
            stringResource(R.string.doc_russian_only).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (b in blocks) when {
                b.startsWith("## ") -> Text(
                    b.removePrefix("## "),
                    Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                b.startsWith("- ") -> Text("•  " + b.removePrefix("- "), style = MaterialTheme.typography.bodyMedium)
                else -> Text(b, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun docBlocks(lines: List<String>): List<String> {
    val out = mutableListOf<String>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotEmpty()) out += para.toString()
        para.clear()
    }
    for (raw in lines) {
        val line = raw.trim()
        when {
            line.isEmpty() -> flush()
            line.startsWith("# ") -> flush()
            line.startsWith("## ") || line.startsWith("- ") -> { flush(); out += line }
            else -> para.append(if (para.isEmpty()) "" else " ").append(line)
        }
    }
    flush()
    return out
}

@Composable
private fun SectionTitle(text: String) = Text(
    text,
    Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
)
