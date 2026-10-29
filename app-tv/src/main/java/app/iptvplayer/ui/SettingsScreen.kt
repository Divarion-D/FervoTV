package app.iptvplayer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import app.iptvplayer.R
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.model.PlaylistRefresh
import app.iptvplayer.core.model.Settings
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
import androidx.compose.ui.text.style.TextAlign

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
    onSaveFile: () -> Unit,
    onLoadFile: () -> Unit,
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
    var qrUrl by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val rows = remember { mutableMapOf<String, FocusRequester>() }
    fun Modifier.row(key: String) =
        focusRequester(rows.getOrPut(key) { FocusRequester() }).onFocusChanged { if (it.isFocused) lastRow = key }
    // TV: focus on the first row when opened, on the row we left from when back.
    val tv = remember { ctx.isTv() }
    LaunchedEffect(refreshDialog, daysDialog, languageDialog, qrUrl) {
        if (!tv || refreshDialog || daysDialog || languageDialog || qrUrl != null) return@LaunchedEffect
        withFrameNanos {}
        runCatching { rows[lastRow]?.requestFocus() }
    }
    // Edited locally and applied by button, so the logo index isn't rebuilt on every keystroke.
    var logoSource by remember(settings.logoSource) { mutableStateOf(settings.logoSource) }
    val resetLogoSource = {
        logoSource = Defaults.LOGO_SOURCE
        onChange(settings.copy(logoSource = Defaults.LOGO_SOURCE))
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopAppBar(
            title = { Text(stringResource(R.string.section_settings)) },
            navigationIcon = { BackButton(onClose) },
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

            // BAK-011
            SectionTitle(stringResource(R.string.settings_file))
            SettingItem(
                R.drawable.ic_save, stringResource(R.string.settings_file_save), stringResource(R.string.settings_file_save_summary),
                Modifier.row("saveFile"), onClick = onSaveFile,
            )
            SettingItem(
                R.drawable.ic_folder_open, stringResource(R.string.settings_file_load), stringResource(R.string.settings_file_load_summary),
                Modifier.row("loadFile"), onClick = onLoadFile,
            )

            SectionTitle(stringResource(R.string.settings_about))
            SettingItem(R.drawable.ic_info, stringResource(R.string.settings_version), versionLabel(ctx), Modifier.row("version")) {}
            SettingItem(R.drawable.ic_person, stringResource(R.string.settings_author), Defaults.AUTHOR, Modifier.row("author")) {}
            SettingItem(R.drawable.ic_code, stringResource(R.string.settings_github), Defaults.GITHUB_URL, Modifier.row("github")) {
                if (!openUrl(ctx, Defaults.GITHUB_URL)) qrUrl = Defaults.GITHUB_URL
            }
            SettingItem(R.drawable.ic_history, stringResource(R.string.whats_new), stringResource(R.string.settings_whats_new_summary), Modifier.row("changelog")) {
                subpage = R.string.whats_new to "CHANGELOG.md"
            }
            SettingItem(
                R.drawable.ic_info, stringResource(R.string.settings_licenses), stringResource(R.string.settings_licenses_summary), Modifier.row("licenses"),
            ) { subpage = R.string.settings_licenses to "LICENSES.md" }
        }
    }
    qrUrl?.let { url -> QrDialog(url, onDismiss = { qrUrl = null }) }
}

@Composable
private fun languageName(tag: String): String = languageName(LocalContext.current, tag)

private fun languageName(ctx: Context, tag: String): String = when (tag) {
    "ru" -> ctx.getString(R.string.language_ru)
    "en" -> ctx.getString(R.string.language_en)
    else -> ctx.getString(R.string.language_system)
}

/** No browser (common on TV boxes): the link as a QR code for the phone, and as text. */
@Composable
private fun QrDialog(url: String, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.qr_title)) },
    text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            QrCode(url, Modifier.size(220.dp))
            Text(url, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }
    },
    confirmButton = {
        TextButton(onClick = onDismiss, modifier = Modifier.initialFocus().focusRing(RoundedCornerShape(50))) { Text(stringResource(R.string.close)) }
    },
)

/** "0.1 · build 1 · debug" — from the installed package, so it always matches build.gradle.kts. */
private fun versionLabel(ctx: Context): String {
    @Suppress("DEPRECATION") // getPackageInfo(String, PackageInfoFlags) is API 33+
    val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    @Suppress("DEPRECATION") // longVersionCode is API 28+
    val code = info.versionCode
    val debug = ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    return ctx.getString(R.string.settings_version_value, info.versionName, code) + if (debug) " · debug" else ""
}

/** False when there is no browser (TV boxes often have none). */
private fun openUrl(ctx: Context, url: String): Boolean = try {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    true
} catch (e: ActivityNotFoundException) {
    false
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
