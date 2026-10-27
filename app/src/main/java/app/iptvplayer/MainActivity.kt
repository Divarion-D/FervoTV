package app.iptvplayer

import android.os.Bundle
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

    // Logo settings changed: re-match against the already loaded channels.
    LaunchedEffect(settings.autoLogos, settings.logoSource) {
        channels?.let { loadLogos(it) }
    }

    // Outside the branches below so opening/cancelling the source editor doesn't reload the playlist.
    LaunchedEffect(source, attempt) {
        val s = source ?: return@LaunchedEffect
        channels = null
        error = null
        val list = try {
            repo.load(s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            return@LaunchedEffect
        }
        channels = list
        // Straight away, in parallel with the first frame of the grid — not in an effect that waits for it (~0.8 s).
        loadLogos(list)
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
            settings, src.label(), logoStatus,
            onChange = { repo.settings = it; settings = it },
            onEditSource = { editing = true },
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
        }
        else -> ChannelsScreen(list, logos, onOpenSettings = { settingsOpen = true })
    }
}

private fun Source.label(): String = when (this) {
    is Source.M3u -> if (url.startsWith("content://")) "Локальный файл" else url
    is Source.Xtream -> "Xtream Codes · $server"
}
