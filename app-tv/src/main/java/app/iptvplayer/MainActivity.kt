package app.iptvplayer

import android.os.Bundle
import android.widget.Toast
import app.iptvplayer.core.Repository
import app.iptvplayer.core.logos.LogoIndex
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.Source
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
import app.iptvplayer.ui.ChannelsScreen
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
    }
}

@Composable
private fun App(repo: Repository) {
    var source by remember { mutableStateOf(repo.source) }
    var editing by remember { mutableStateOf(false) }
    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(repo.settings) }
    var settingsOpen by remember { mutableStateOf(false) }
    var logos by remember { mutableStateOf<LogoIndex?>(null) }
    var logoStatus by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    var playlistStatus by remember { mutableStateOf("") }
    var refreshRequest by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var logoJob by remember { mutableStateOf<Job?>(null) }

    // Channels are shown right away; logos from the index fill in when it's ready.
    suspend fun loadLogos(list: List<Channel>) {
        logos = null
        if (!settings.autoLogos) {
            logoStatus = "Автоподгрузка выключена"
            return
        }
        logoStatus = "Подбор логотипов…"
        try {
            val index = repo.logoIndex(settings.logoSource, list)
            logos = index
            logoStatus = "Логотипы найдены для ${list.count { index.find(it) != null }} из ${list.size} каналов"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logoStatus = "Не удалось загрузить базу логотипов: ${e.message ?: e}"
        }
    }

    // One logo job at a time: a newer channel list cancels matching for the older one.
    fun startLogos(list: List<Channel>) {
        logoJob?.cancel()
        logoJob = scope.launch { loadLogos(list) }
    }

    /**
     * Downloads the playlist. With a cached list on screen a failure only reports (the cache stays usable offline);
     * without one it shows the error screen.
     */
    suspend fun refresh(s: Source) {
        refreshing = true
        playlistStatus = "Обновление…"
        val list = try {
            repo.load(s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = errorText(e)
            if (channels == null) {
                error = msg
            } else {
                playlistStatus = "Не удалось обновить: $msg"
                Toast.makeText(ctx, "Не удалось обновить плейлист — показан сохранённый список", Toast.LENGTH_LONG).show()
            }
            return
        } finally {
            refreshing = false
        }
        playlistStatus = "Обновлён ${formatTime(System.currentTimeMillis())}"
        if (list != channels) {
            channels = list
            startLogos(list)
        }
    }

    // Logo settings changed: re-match against the already loaded channels.
    LaunchedEffect(settings.autoLogos, settings.logoSource) {
        channels?.let { startLogos(it) }
    }

    // Cache first, network second: the app opens instantly and still works without internet.
    // Outside the branches below so opening/cancelling the source editor doesn't reload the playlist.
    LaunchedEffect(source, attempt) {
        val s = source ?: return@LaunchedEffect
        channels = null
        error = null
        val cached = repo.cachedPlaylist(s)
        if (cached == null) {
            refresh(s)
            return@LaunchedEffect
        }
        channels = cached.channels
        playlistStatus = "Сохранён ${formatTime(cached.savedAt)}"
        // Logo matching starts right away, in parallel with the first frame of the grid.
        startLogos(cached.channels)
        if (settings.playlistRefresh.isDue(cached.savedAt)) refresh(s)
    }

    // "Обновить плейлист сейчас" in Настройки.
    LaunchedEffect(refreshRequest) {
        if (refreshRequest > 0) source?.let { refresh(it) }
    }

    val list = channels
    val err = error
    val src = source
    when {
        src == null || editing -> SetupScreen(
            initial = src,
            onCancel = { editing = false },
            onDone = { repo.source = it; source = it; editing = false; settingsOpen = false },
        )
        settingsOpen -> SettingsScreen(
            settings, src.label(), logoStatus, playlistStatus, refreshing,
            onChange = { repo.settings = it; settings = it },
            onEditSource = { editing = true },
            onRefreshNow = { refreshRequest++ },
            onClose = { settingsOpen = false },
        )
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
        list == null -> Column(
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
        else -> ChannelsScreen(list, logos, onOpenSettings = { settingsOpen = true })
    }
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
