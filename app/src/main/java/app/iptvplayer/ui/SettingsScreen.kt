package app.iptvplayer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import app.iptvplayer.Defaults
import app.iptvplayer.PlaylistRefresh
import app.iptvplayer.R
import app.iptvplayer.Settings
import app.iptvplayer.initialFocus

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
    onClose: () -> Unit,
) {
    var changelogOpen by remember { mutableStateOf(false) }
    var refreshDialog by remember { mutableStateOf(false) }
    if (changelogOpen) {
        ChangelogScreen(onClose = { changelogOpen = false })
        return
    }
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    // Edited locally and applied by button, so the logo index isn't rebuilt on every keystroke.
    var logoSource by remember(settings.logoSource) { mutableStateOf(settings.logoSource) }
    val resetLogoSource = {
        logoSource = Defaults.LOGO_SOURCE
        onChange(settings.copy(logoSource = Defaults.LOGO_SOURCE))
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopAppBar(
            title = { Text("Настройки") },
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
            SectionTitle("Плейлист")
            SettingItem(
                R.drawable.ic_refresh, "Обновить плейлист сейчас", playlistStatus.ifEmpty { null },
                Modifier.initialFocus(),
                trailing = if (refreshing) {
                    { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                } else null,
            ) { if (!refreshing) onRefreshNow() }
            SettingItem(R.drawable.ic_playlist_play, "Источник плейлиста", sourceLabel, onClick = onEditSource)
            SettingItem(
                R.drawable.ic_schedule, "Автообновление",
                "${settings.playlistRefresh.label} · сохранённый список открывается сразу, без ожидания сети",
            ) { refreshDialog = true }
            if (refreshDialog) {
                ChoiceDialog(
                    title = "Обновлять плейлист",
                    options = PlaylistRefresh.entries,
                    selected = settings.playlistRefresh,
                    label = { it.label },
                    onSelect = { onChange(settings.copy(playlistRefresh = it)); refreshDialog = false },
                    onDismiss = { refreshDialog = false },
                )
            }

            SectionTitle("Логотипы каналов")
            SettingItem(
                R.drawable.ic_image,
                "Автоподгрузка логотипов",
                "Если в плейлисте нет логотипа или он не открывается — искать по названию канала в базе",
                trailing = { Switch(settings.autoLogos, onCheckedChange = null) },
            ) { onChange(settings.copy(autoLogos = !settings.autoLogos)) }
            OutlinedTextField(
                logoSource, { logoSource = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("Источник логотипов (iptv-org API)") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_link), contentDescription = null) },
                trailingIcon = if (logoSource != Defaults.LOGO_SOURCE) {
                    { IconButton(onClick = resetLogoSource) { Icon(painterResource(R.drawable.ic_restart_alt), contentDescription = "По умолчанию") } }
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
                ) { IconLabel(R.drawable.ic_check, "Применить") }
            }

            SectionTitle("О приложении")
            SettingItem(R.drawable.ic_info, "Версия", remember { versionLabel(ctx) }) {}
            SettingItem(R.drawable.ic_person, "Автор", Defaults.AUTHOR) {}
            SettingItem(R.drawable.ic_code, "Исходный код на GitHub", Defaults.GITHUB_URL) { openUrl(ctx, Defaults.GITHUB_URL) }
            SettingItem(R.drawable.ic_history, "Что нового", "Список изменений по версиям") { changelogOpen = true }
        }
    }
}

/** "0.1 · сборка 1 · debug" — from the installed package, so it always matches build.gradle.kts. */
private fun versionLabel(ctx: Context): String {
    @Suppress("DEPRECATION") // getPackageInfo(String, PackageInfoFlags) is API 33+
    val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    @Suppress("DEPRECATION") // longVersionCode is API 28+
    val code = info.versionCode
    val debug = ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    return "${info.versionName} · сборка $code" + if (debug) " · debug" else ""
}

/** TV boxes often have no browser: then show the link so it can be typed elsewhere. */
private fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "Нет браузера. Откройте вручную: $url", Toast.LENGTH_LONG).show()
    }
}

/** Shows assets/CHANGELOG.md: "## version — date" headings and "- item" lines. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangelogScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val lines = remember { ctx.assets.open("CHANGELOG.md").bufferedReader().use { it.readLines() } }
    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopAppBar(
            title = { Text("Что нового") },
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
            for (line in lines) when {
                line.startsWith("## ") -> Text(
                    line.removePrefix("## "),
                    Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                line.startsWith("- ") -> Text("•  " + line.removePrefix("- "), style = MaterialTheme.typography.bodyMedium)
                else -> {} // "# title" duplicates the app bar; blank lines are spacing
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(
    text,
    Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
)
