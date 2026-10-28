package app.iptvplayer.core.model

import app.iptvplayer.core.Defaults

data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String? = null,
    val tvgId: String? = null,
    /** Logo found by [app.iptvplayer.core.logos.LogoIndex]; stored next to the channel in the database. */
    val autoLogo: String? = null,
    /** Database row id; 0 for a channel that isn't stored. */
    val id: Long = 0,
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
    /** N for [PlaylistRefresh.EVERY_N_DAYS]. */
    val refreshDays: Int = Defaults.PLAYLIST_REFRESH_DAYS,
)

/** When to re-download the playlist. The cached copy always opens first; the refresh runs in the background. */
enum class PlaylistRefresh(private val title: String) {
    ON_START("При каждом запуске"),
    HOURS_6("Каждые 6 часов"),
    DAILY("Раз в день"),
    WEEKLY("Раз в неделю"),
    EVERY_N_DAYS("Каждые N дней"),
    MANUAL("Только вручную");

    /** "Каждые 3 дня" for [EVERY_N_DAYS], the plain title otherwise. */
    fun label(days: Int): String =
        if (this == EVERY_N_DAYS) "Каждые $days ${plural(days, "день", "дня", "дней")}" else title

    /**
     * How long to wait before the next refresh of a playlist saved at [savedAt]; 0 or less = now, null = not by time.
     * [lastAttempt] is the last refresh tried in this run (0 = none yet): a failed one waits a full interval too,
     * and "on start" means once per run.
     */
    fun waitMs(savedAt: Long, lastAttempt: Long, now: Long, days: Int): Long? {
        val interval = when (this) {
            ON_START -> return if (lastAttempt == 0L) 0 else null
            HOURS_6 -> 6 * HOUR_MS
            DAILY -> 24 * HOUR_MS
            WEEKLY -> 7 * 24 * HOUR_MS
            EVERY_N_DAYS -> days * 24 * HOUR_MS
            MANUAL -> return null
        }
        return interval - (now - maxOf(savedAt, lastAttempt))
    }
}

/** Russian plural: 1 день, 2 дня, 5 дней, 21 день. */
fun plural(n: Int, one: String, few: String, many: String): String = when {
    n % 100 in 11..14 -> many
    n % 10 == 1 -> one
    n % 10 in 2..4 -> few
    else -> many
}

private const val HOUR_MS = 3_600_000L

const val NO_GROUP = "Без категории"
