package app.iptvplayer.core

import android.content.Context
import android.net.Uri
import app.iptvplayer.core.logos.LogoIndex
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.PlaylistRefresh
import app.iptvplayer.core.model.Settings
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.net.httpGet
import app.iptvplayer.core.source.CachedPlaylist
import app.iptvplayer.core.source.cacheKey
import app.iptvplayer.core.source.decodePlaylist
import app.iptvplayer.core.source.encodePlaylist
import app.iptvplayer.core.source.m3u.parseM3u
import app.iptvplayer.core.source.xtream.loadXtream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Stores the source and settings, loads the playlist and keeps its cached copy. The one entry point for the apps. */
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
