package app.iptvplayer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class Repository(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    private val settingsPrefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var source: Source?
        get() = when (prefs.getString("type", null)) {
            "m3u" -> Source.M3u(prefs.getString("url", "")!!)
            "xtream" -> Source.Xtream(
                prefs.getString("server", "")!!,
                prefs.getString("user", "")!!,
                prefs.getString("pass", "")!!,
                prefs.getBoolean("hls", false),
            )
            else -> null
        }
        set(v) {
            val e = prefs.edit().clear()
            when (v) {
                is Source.M3u -> e.putString("type", "m3u").putString("url", v.url)
                is Source.Xtream -> e.putString("type", "xtream").putString("server", v.server)
                    .putString("user", v.user).putString("pass", v.pass).putBoolean("hls", v.hls)
                null -> {}
            }
            e.apply()
        }

    var settings: Settings
        get() = Settings(
            autoLogos = settingsPrefs.getBoolean("autoLogos", Defaults.AUTO_LOGOS),
            logoSource = settingsPrefs.getString("logoSource", null) ?: Defaults.LOGO_SOURCE,
            playlistRefresh = settingsPrefs.getString("playlistRefresh", null)
                ?.let { name -> PlaylistRefresh.entries.find { it.name == name } }
                ?: Defaults.PLAYLIST_REFRESH,
        )
        set(v) {
            settingsPrefs.edit()
                .putBoolean("autoLogos", v.autoLogos)
                .putString("logoSource", v.logoSource)
                .putString("playlistRefresh", v.playlistRefresh.name)
                .apply()
        }

    // filesDir, not cacheDir: the system may wipe cacheDir, and this copy is what works offline.
    private val playlistFile = File(ctx.filesDir, "playlist.tsv")

    /** The last successfully loaded playlist of [s], or null if there is none for this source. */
    suspend fun cachedPlaylist(s: Source): CachedPlaylist? = withContext(Dispatchers.IO) {
        runCatching { if (playlistFile.exists()) decodePlaylist(s.cacheKey(), playlistFile.readText()) else null }.getOrNull()
    }

    /** Downloads (or reads) the playlist and replaces the cached copy. */
    suspend fun load(s: Source): List<Channel> = withContext(Dispatchers.IO) {
        val list = when (s) {
            is Source.M3u -> parseM3u(
                if (s.url.startsWith("content://")) {
                    ctx.contentResolver.openInputStream(Uri.parse(s.url))
                        ?.bufferedReader()?.use { it.readText() }
                        ?: throw IOException("Не удалось открыть файл")
                } else httpGet(s.url),
                baseUrl = s.url,
            )
            is Source.Xtream -> loadXtream(s)
        }
        // Almost always a broken/blocked response rather than a real empty playlist: don't let it replace a good cache.
        if (list.isEmpty()) throw IOException("Плейлист пуст")
        val tmp = File(playlistFile.path + ".tmp")
        tmp.writeText(encodePlaylist(s.cacheKey(), System.currentTimeMillis(), list))
        tmp.renameTo(playlistFile)
        list
    }

    suspend fun logoIndex(source: String, channels: List<Channel>): LogoIndex = LogoIndex.load(ctx, source, channels)
}

fun httpGet(url: String): String = httpStream(url) { it.bufferedReader().readText() }

/** GET and read the body. Follows redirects manually because HttpURLConnection won't cross http<->https. */
fun <T> httpStream(url: String, read: (InputStream) -> T): T {
    var u = URL(url)
    repeat(Defaults.MAX_REDIRECTS) {
        val c = u.openConnection() as HttpURLConnection
        c.connectTimeout = Defaults.CONNECT_TIMEOUT_MS
        c.readTimeout = Defaults.READ_TIMEOUT_MS
        c.setRequestProperty("User-Agent", Defaults.USER_AGENT)
        try {
            val code = c.responseCode
            if (code in 300..399) {
                u = URL(u, c.getHeaderField("Location") ?: throw IOException("HTTP $code без Location"))
                return@repeat
            }
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.use(read)
        } finally {
            c.disconnect()
        }
    }
    throw IOException("Слишком много перенаправлений")
}
