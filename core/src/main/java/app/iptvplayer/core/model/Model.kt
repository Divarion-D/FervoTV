package app.iptvplayer.core.model

import app.iptvplayer.core.Defaults

data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String? = null,
    val tvgId: String? = null,
)

sealed interface Source {
    /** http(s):// playlist URL or content:// URI of a local file. */
    data class M3u(val url: String) : Source

    /** [hls] = request .m3u8 streams instead of .ts (for servers that block TS). */
    data class Xtream(val server: String, val user: String, val pass: String, val hls: Boolean = false) : Source
}

/** User settings (Настройки). Defaults live in [Defaults]. */
data class Settings(
    val autoLogos: Boolean = Defaults.AUTO_LOGOS,
    val logoSource: String = Defaults.LOGO_SOURCE,
    val playlistRefresh: PlaylistRefresh = Defaults.PLAYLIST_REFRESH,
)

/** When to re-download the playlist. The cached copy always opens first; the refresh runs in the background. */
enum class PlaylistRefresh(val label: String, private val intervalMs: Long?) {
    ON_START("При каждом запуске", 0L),
    HOURS_6("Каждые 6 часов", 6 * HOUR_MS),
    DAILY("Раз в день", 24 * HOUR_MS),
    WEEKLY("Раз в неделю", 7 * 24 * HOUR_MS),
    MANUAL("Только вручную", null);

    /** Is a playlist cached at [savedAt] due for a refresh? */
    fun isDue(savedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
        intervalMs != null && now - savedAt >= intervalMs
}

private const val HOUR_MS = 3_600_000L

const val NO_GROUP = "Без категории"
