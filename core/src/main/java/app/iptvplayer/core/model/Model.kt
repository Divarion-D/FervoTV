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
    /**
     * Stable identity that user data (favorites, edits, EPG binding, history) is attached to: rows are recreated by
     * every import, this is not. M3U: the stream URL; Xtream: server + user + stream_id (not the password or
     * the TS/HLS choice, which are part of the URL).
     */
    val key: String = url,
    /** tvg-chno / the provider's number; null = the position in the source (see [ChannelItem.number]). */
    val number: Int? = null,
    /** Archive (catch-up) depth in days, 0 = no archive. */
    val archiveDays: Int = 0,
    /** Optional per-stream HTTP headers from M3U EXT-X/EXTVLCOPT metadata. */
    val userAgent: String? = null,
    val referer: String? = null,
    val drmLicenseUrl: String? = null,
    val drmScheme: String? = null,
)

/** One programme of the guide; times are epoch ms with the guide's time shift already applied. */
data class Programme(val start: Long, val stop: Long, val title: String, val description: String? = null) {
    /** 0..1 at [now]. */
    fun progress(now: Long): Float = ((now - start).toFloat() / (stop - start).coerceAtLeast(1)).coerceIn(0f, 1f)

    /** Whole minutes left at [now], rounded up ("ещё 24 мин"). */
    fun minutesLeft(now: Long): Long = ((stop - now).coerceAtLeast(0) + 59_999) / 60_000
}

/**
 * A channel as the live screens show it: user edits applied ([Channel.name] is the user's name if renamed,
 * [Channel.logo] the user's logo if set), with the guide for the moment it was loaded.
 */
data class ChannelItem(
    val channel: Channel,
    /** tvg-chno / provider number, else the position in the source from 1 (LIVE-009). */
    val number: Int,
    val favorite: Boolean,
    /** Hidden itself or by its category (only listed when the filter asks for hidden ones). */
    val hidden: Boolean,
    val now: Programme? = null,
    val next: Programme? = null,
)

/** Previous, current and next programme of one channel (player info bar). */
data class Airing(val previous: Programme?, val now: Programme?, val next: Programme?)

/** One row of the programme grid: the channel and its programmes overlapping the requested time range, in order. */
data class GuideRow(val item: ChannelItem, val programmes: List<Programme>)

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
    /** "Дней программы" (EPG-003): how many days of guide are downloaded, kept and scrollable. */
    val epgDays: Int = Defaults.EPG_DAYS,
    /** Global guide time shift in minutes (EPG-007); a source's or a channel's own shift wins over it. */
    val epgShift: Int = Defaults.EPG_SHIFT,
    /** Order of channels in the live section (LIVE-016). */
    val channelSort: ChannelSort = Defaults.CHANNEL_SORT,
    /** LIVE-006: the focused channel plays in a window next to the list, with or without sound. */
    val preview: Boolean = Defaults.PREVIEW,
    val previewSound: Boolean = Defaults.PREVIEW_SOUND,
    /** SET-011: the live section opens on the last list and channel; off = on the first list. */
    val rememberLive: Boolean = Defaults.REMEMBER_LIVE,
    /** Rows or tiles in the live section (LIVE-005); null = by screen: rows on a wide one, tiles on a phone. */
    val liveTiles: Boolean? = null,
    /** LIVE-022: the live section opens as the programme grid instead of the list. */
    val liveGuide: Boolean = Defaults.LIVE_GUIDE,
    /** EPG-010: the grid lists channels without a programme too. */
    val guideEmpty: Boolean = Defaults.GUIDE_EMPTY,
    /** UI-022: the interface language as a BCP 47 tag ("ru", "en"); empty = the system's. Not the metadata language. */
    val language: String = Defaults.LANGUAGE,
    /** VOD-010: what a started film or episode does when played again. */
    val resume: ResumeMode = Defaults.RESUME,
    /** VOD-009: the next episode starts by itself (also catch-up, CU-006). */
    val autoNext: Boolean = Defaults.AUTO_NEXT,
    /** VOD-003: catalogue order of «Фильмы» and «Сериалы», each remembered. */
    val movieSort: VodSort = Defaults.VOD_SORT,
    val seriesSort: VodSort = Defaults.VOD_SORT,
    /** VOD-002: the catalogue as a list instead of the poster grid, per section. */
    val movieList: Boolean = false,
    val seriesList: Boolean = false,
    /** HOME-001: the screen the app opens on. */
    val startScreen: StartScreen = Defaults.START_SCREEN,
    /** HOME-005: the home rows in the user's order with on/off ([app.iptvplayer.core.home.homeRows]); empty = default. */
    val homeRows: String = "",
    /** HOME-002/005: recent channels are part of «Продолжить просмотр». */
    val homeChannels: Boolean = Defaults.HOME_CHANNELS,
    /** HOME-003: favourite channels on the home screen as a mini-guide (what's on now) instead of cards. */
    val homeGuide: Boolean = Defaults.HOME_GUIDE,
    /** HOME-001: hide the home section and open «Эфир» instead. */
    val homeVisible: Boolean = Defaults.HOME_VISIBLE,
    /** HOME-002: muted preview of the focused Continue card. */
    val homePreview: Boolean = Defaults.HOME_PREVIEW,
    /** PLR-005: total retry window for a live stream, seconds; 0 = never give up. */
    val playerGiveUpSeconds: Int = Defaults.PLAYER_GIVE_UP_SECONDS,
    /** PLR-006: maximum automatic reconnect attempts during the retry window. */
    val playerReconnectAttempts: Int = Defaults.PLAYER_RECONNECT_ATTEMPTS,
    /** PLR-013: comma-separated preferred audio language tags. */
    val playerAudioLanguages: String = "",
    /** PLR-013: comma-separated preferred subtitle language tags. */
    val playerSubtitleLanguages: String = "",
    /** PLR-023: match display frame rate when the platform allows it. */
    val playerMatchFrameRate: Boolean = false,
    /** PLR-027: start playback with the picture hidden. */
    val playerAudioOnly: Boolean = false,
    /** PLR-015: default output volume, 0..150. */
    val playerVolume: Int = Defaults.PLAYER_VOLUME,
    /** PLR-021: user-selectable buffer bounds in seconds. */
    val playerMinBufferSeconds: Int = Defaults.PLAYER_MIN_BUFFER_MS / 1000,
    val playerMaxBufferSeconds: Int = Defaults.PLAYER_MAX_BUFFER_MS / 1000,
    val playerPrebufferSeconds: Int = Defaults.PLAYER_BUFFER_FOR_PLAYBACK_MS / 1000,
)

/** HOME-001: where the app opens. The apps name them. */
enum class StartScreen { HOME, LIVE }

/** LIVE-016. [PROVIDER] is the provider's order with the user's manual order (LIVE-013) on top of it. The apps name them. */
enum class ChannelSort { PROVIDER, NUMBER, NAME, ARCHIVE_FIRST, FAVORITES_FIRST }

/** When to re-download the playlist. The cached copy always opens first; the refresh runs in the background. */
enum class PlaylistRefresh {
    ON_START, HOURS_6, DAILY, WEEKLY, EVERY_N_DAYS, MANUAL;

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

private const val HOUR_MS = 3_600_000L

/**
 * The category of channels the provider gave none. A data value, not a title: user edits of this category are keyed
 * by it, so it never changes; the apps show their own name for it (UI-022).
 */
const val NO_GROUP = "Без категории"
