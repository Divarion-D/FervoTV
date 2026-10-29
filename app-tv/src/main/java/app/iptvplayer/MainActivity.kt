package app.iptvplayer

import android.os.Bundle
import android.widget.Toast
import app.iptvplayer.core.Repository
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.player.ExoEngine
import app.iptvplayer.core.player.PlayerSession
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Context
import kotlinx.coroutines.delay
import app.iptvplayer.ui.DocScreen
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import app.iptvplayer.ui.IconLabel
import app.iptvplayer.ui.focusRing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.iptvplayer.ui.AppTheme
import app.iptvplayer.ui.BgTask
import app.iptvplayer.ui.LiveScreen
import app.iptvplayer.ui.PhoneNav
import app.iptvplayer.ui.Section
import app.iptvplayer.ui.isWideLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.saveable.rememberSaveable
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.net.onlineFlow
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.first
import app.iptvplayer.ui.SettingsScreen
import app.iptvplayer.ui.SetupScreen
import kotlin.coroutines.cancellation.CancellationException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Always-dark UI: light system bar icons regardless of the system theme. Screens pad themselves with
        // safeDrawingPadding; the player stays full-bleed.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val repo = Repository(applicationContext)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) { App(repo) }
            }
        }
        // The root Surface paints the whole screen anyway: a window background under it is one more full-screen fill per frame.
        window.setBackgroundDrawable(null)
    }
}

@Composable
private fun App(repo: Repository) {
    var source by remember { mutableStateOf(repo.source) }
    var editing by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) } // the source's channels are saved in the database
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(repo.settings) }
    var settingsOpen by remember { mutableStateOf(false) }
    var logoStatus by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    var playlistStatus by remember { mutableStateOf("") }
    var refreshRequest by remember { mutableIntStateOf(0) }
    var task by remember { mutableStateOf<BgTask?>(null) } // what runs in the background (UI-015)
    val ctx = LocalContext.current
    val onlineFlow = remember { onlineFlow(ctx.applicationContext) }
    val online by onlineFlow.collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    var logoJob by remember { mutableStateOf<Job?>(null) }
    var lastAttempt by remember { mutableLongStateOf(0L) } // last playlist refresh tried in this run
    // Screens keep their state (filters, scroll, focused row) while another screen is open on top of them.
    val screens = rememberSaveableStateHolder()
    // «Эфир» as the list or the programme grid (LIVE-022: the setting picks how it opens).
    var guide by rememberSaveable { mutableStateOf(repo.settings.liveGuide) }
    val wide = isWideLayout()

    // One playback session above the screens (spec 01 §6.3); released with the activity.
    val player = remember { PlayerSession { ExoEngine(ctx.applicationContext) } }
    DisposableEffect(player) { onDispose { player.release() } }

    // After an update: offer "Что нового" once.
    val version = remember { installedVersion(ctx) }
    var changelogOffer by remember { mutableStateOf(false) }
    var changelogOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { changelogOffer = repo.offerChangelog(version.first, version.second) }

    // Channels are shown right away; logos found in the index are stored next to them and fill in when ready.
    suspend fun loadLogos(s: Source) {
        val on = settings.autoLogos
        logoStatus = if (on) "Подбор логотипов…" else "Автоподгрузка выключена"
        if (on) task = BgTask(ctx.getString(R.string.task_logos))
        try {
            // Off: clears the stored matches.
            val (found, total) = repo.matchLogos(s, settings.logoSource.takeIf { on })
            if (on) {
                logoStatus = "Логотипы найдены для $found из $total каналов"
                task = BgTask(ctx.getString(R.string.task_logos_done, found, total), ok = true)
            }
        } catch (e: CancellationException) {
            if (task?.ok == null) task = null
            throw e
        } catch (e: Exception) {
            logoStatus = "Не удалось загрузить базу логотипов: ${e.message ?: e}"
            task = BgTask(ctx.getString(R.string.task_logos_failed), ok = false)
        }
    }

    // One logo job at a time: a newer import cancels matching for the older one.
    fun startLogos(s: Source) {
        logoJob?.cancel()
        logoJob = scope.launch { loadLogos(s) }
    }

    /**
     * Downloads the playlist into the database. With saved channels on screen a failure only reports (they stay
     * usable offline); without them it shows the error screen.
     */
    suspend fun refresh(s: Source) {
        refreshing = true
        lastAttempt = System.currentTimeMillis()
        playlistStatus = "Обновление…"
        if (ready) task = BgTask(ctx.getString(R.string.task_playlist))
        try {
            repo.load(s)
        } catch (e: CancellationException) {
            if (task?.ok == null) task = null
            throw e
        } catch (e: Exception) {
            val msg = errorText(e)
            if (!ready) {
                error = msg
            } else {
                playlistStatus = "Не удалось обновить: $msg"
                task = BgTask(ctx.getString(R.string.task_playlist_failed), ok = false)
                Toast.makeText(ctx, "Не удалось обновить плейлист — показан сохранённый список", Toast.LENGTH_LONG).show()
            }
            return
        } finally {
            refreshing = false
        }
        if (ready) task = BgTask(ctx.getString(R.string.task_playlist_done), ok = true)
        playlistStatus = "Обновлён ${formatTime(System.currentTimeMillis())}"
        ready = true
        startLogos(s)
    }

    // Logo settings changed: re-match against the saved channels.
    LaunchedEffect(settings.autoLogos, settings.logoSource) {
        if (ready) source?.let { startLogos(it) }
    }

    // Saved channels first, network second: the app opens instantly and still works without internet.
    // Outside the branches below so opening/cancelling the source editor doesn't reload the playlist.
    LaunchedEffect(source, attempt) {
        val s = source ?: return@LaunchedEffect
        ready = false
        error = null
        lastAttempt = 0L
        val savedAt = repo.savedAt(s)
        if (savedAt == null) {
            refresh(s)
            return@LaunchedEffect
        }
        ready = true
        playlistStatus = "Сохранён ${formatTime(savedAt)}"
        // Logo matching starts right away, in parallel with the first frame of the grid.
        startLogos(s)
    }

    // Background refresh on schedule (SRC-005), checked again for as long as the app runs: a TV app can stay open for days.
    LaunchedEffect(source, ready, settings.playlistRefresh, settings.refreshDays) {
        val s = source ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        while (true) {
            val savedAt = repo.savedAt(s) ?: return@LaunchedEffect
            val wait = settings.playlistRefresh.waitMs(savedAt, lastAttempt, System.currentTimeMillis(), settings.refreshDays)
                ?: return@LaunchedEffect
            if (wait > 0) delay(wait) else {
                onlineFlow.first { it } // no network: the refresh waits for it and then runs by itself (UI-016)
                refresh(s)
            }
        }
    }

    // Guide sources on their schedule (EPG-003), new ones right away; without network it waits for it.
    val epgIds by remember { repo.epgSources() }.collectAsState(initial = emptyList())
    val epgKey = epgIds.map { it.id }
    LaunchedEffect(ready, epgKey) {
        if (!ready || epgKey.isEmpty()) return@LaunchedEffect
        while (true) {
            val wait = repo.epgWaitMs() ?: return@LaunchedEffect
            if (wait > 0) {
                delay(wait)
                continue
            }
            onlineFlow.first { it }
            task = BgTask(ctx.getString(R.string.task_epg))
            val failed = try {
                repo.refreshDueEpg().isNotEmpty()
            } catch (e: CancellationException) {
                if (task?.ok == null) task = null
                throw e
            }
            task = BgTask(ctx.getString(if (failed) R.string.task_epg_failed else R.string.task_epg_done), ok = !failed)
        }
    }

    // A task's result stays for a few seconds (UI-015).
    LaunchedEffect(task) {
        if (task?.ok != null) {
            delay(Defaults.TOAST_MS)
            task = null
        }
    }

    // "Обновить плейлист сейчас" in Настройки.
    LaunchedEffect(refreshRequest) {
        if (refreshRequest > 0) source?.let { refresh(it) }
    }

    val err = error
    val src = source
    when {
        changelogOpen -> DocScreen("Что нового", "CHANGELOG.md", onClose = { changelogOpen = false })
        src == null || editing -> SetupScreen(
            initial = src,
            onCancel = { editing = false },
            onDone = {
                repo.source = it; source = it; editing = false; settingsOpen = false
                // Another playlist: its screens start from scratch.
                screens.removeState("settings")
                screens.removeState("channels")
            },
        )
        settingsOpen -> screens.SaveableStateProvider("settings") {
            val close = { settingsOpen = false; screens.removeState("settings") }
            val content: @Composable () -> Unit = {
                SettingsScreen(
                    settings, src.label(), logoStatus, playlistStatus, refreshing,
                    onChange = { repo.settings = it; settings = it },
                    onEditSource = { editing = true },
                    onRefreshNow = { refreshRequest++ },
                    onClose = close,
                )
            }
            // Phone: Настройки are the «Ещё» tab, over the same bottom navigation.
            if (wide) content() else Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) { content() }
                PhoneNav(Section.More) { s ->
                    guide = s == Section.Guide
                    close()
                }
            }
        }
        err != null -> Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painterResource(R.drawable.ic_error), contentDescription = null,
                modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error,
            )
            Text("Не удалось загрузить плейлист", style = MaterialTheme.typography.titleLarge)
            Text(err, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { attempt++ }, Modifier.initialFocus().focusRing(CircleShape)) {
                    IconLabel(R.drawable.ic_refresh, "Повторить")
                }
                OutlinedButton(onClick = { editing = true }, Modifier.focusRing(CircleShape)) {
                    IconLabel(R.drawable.ic_playlist_play, "Источник")
                }
            }
        }
        !ready -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text("Загрузка плейлиста…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            // No cache yet (first start) and a slow/dead source: let the user switch the playlist instead of waiting.
            OutlinedButton(onClick = { settingsOpen = true }, Modifier.initialFocus().focusRing(CircleShape)) {
                IconLabel(R.drawable.ic_settings, "Настройки")
            }
        }
        else -> screens.SaveableStateProvider("channels") {
            LiveScreen(
                repo, src, player, settings, online, task,
                onSettings = { repo.settings = it; settings = it },
                onOpenSettings = { settingsOpen = true },
                guide = guide,
                onGuide = { guide = it },
            )
        }
    }

    if (changelogOffer) {
        val shape = RoundedCornerShape(50)
        AlertDialog(
            onDismissRequest = { changelogOffer = false },
            icon = { Icon(painterResource(R.drawable.ic_history), contentDescription = null) },
            title = { Text("Fervo TV обновлён") },
            text = { Text("Версия ${version.first}. Посмотреть, что изменилось?") },
            confirmButton = {
                TextButton(
                    onClick = { changelogOffer = false; changelogOpen = true },
                    Modifier.initialFocus().focusRing(shape),
                ) { Text("Что нового") }
            },
            dismissButton = {
                TextButton(onClick = { changelogOffer = false }, Modifier.focusRing(shape)) { Text("Позже") }
            },
        )
    }
}

/** (versionName, was updated rather than freshly installed). */
private fun installedVersion(ctx: Context): Pair<String, Boolean> {
    @Suppress("DEPRECATION") // getPackageInfo(String, PackageInfoFlags) is API 33+
    val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    return info.versionName.orEmpty() to (info.lastUpdateTime != info.firstInstallTime)
}

/** Human-readable reason instead of raw Java exception text. */
private fun errorText(e: Exception): String = when (e) {
    is UnknownHostException -> "Нет подключения к интернету или сервер не найден"
    is SocketTimeoutException -> "Сервер не отвечает"
    is ConnectException -> "Не удалось подключиться к серверу"
    else -> e.message ?: e.toString()
}

private fun formatTime(ms: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ms))

private fun Source.label(): String = when (this) {
    is Source.M3u -> if (url.startsWith("content://")) "Локальный файл" else url
    is Source.Xtream -> "Xtream Codes · $server"
}
