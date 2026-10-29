package app.iptvplayer.core

import app.iptvplayer.core.model.ChannelSort
import app.iptvplayer.core.model.PlaylistRefresh

/**
 * All default values and tunables in one place.
 * The "Settings" block is what the user can change in Настройки (see [Settings]); the rest are code-level knobs.
 */
object Defaults {
    // --- Settings (user-changeable) ---
    /** Look up missing/broken channel logos in [LOGO_SOURCE]. */
    const val AUTO_LOGOS = true

    /** iptv-org API base: must serve channels.json and logos.json (https://github.com/iptv-org/api). */
    const val LOGO_SOURCE = "https://iptv-org.github.io/api"

    /** The cached playlist opens instantly either way; this only decides when it's re-downloaded in the background. */
    val PLAYLIST_REFRESH = PlaylistRefresh.ON_START

    /** N for "every N days" and the values offered for it. */
    const val PLAYLIST_REFRESH_DAYS = 3
    val PLAYLIST_REFRESH_DAYS_OPTIONS = listOf(2, 3, 5, 7, 10, 14, 30)

    /** "Дней программы" (EPG-003): days of guide downloaded, kept and scrollable; the user picks from [EPG_DAYS_RANGE]. */
    const val EPG_DAYS = 7
    val EPG_DAYS_RANGE = 1..14

    /** Global guide time shift, minutes (EPG-007); changed in [EPG_SHIFT_STEP] steps up to ±[EPG_SHIFT_MAX]. */
    const val EPG_SHIFT = 0
    const val EPG_SHIFT_STEP = 15
    const val EPG_SHIFT_MAX = 14 * 60

    /** How often a new guide source is re-downloaded (each source has its own schedule). */
    val EPG_REFRESH = PlaylistRefresh.DAILY

    /** Order of channels in the live section (LIVE-016). */
    val CHANNEL_SORT = ChannelSort.PROVIDER

    /** "Превью в окне" (LIVE-006): the focused channel plays next to the list; off = the window shows its logo. */
    const val PREVIEW = true

    /** "Звук превью": off, the sound comes with full screen. */
    const val PREVIEW_SOUND = false

    /** "Запоминать категорию и канал" (SET-011, LIVE-017): the live section opens where it was left. */
    const val REMEMBER_LIVE = true

    /** LIVE-022: the live section opens as the list (off) or the programme grid. */
    const val LIVE_GUIDE = false

    /** EPG-010: the grid lists channels without a programme too. */
    const val GUIDE_EMPTY = true

    /** UI-022: interface language, "" = the system's (English when the system's isn't translated). */
    const val LANGUAGE = ""

    /** EPG-008 (owner's decision, 33-design §10): the grid's scale, dp per hour. */
    const val GUIDE_DP_PER_HOUR = 150

    /** EPG-010 "Поиск" in the grid: programmes shown at most. */
    const val GUIDE_SEARCH_LIMIT = 30

    // --- About ---
    const val AUTHOR = "Divarion-D"
    const val GITHUB_URL = "https://github.com/Divarion-D/FervoTV"

    // --- Network ---
    const val USER_AGENT = "FervoTV/0.1 (Android)"
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 60_000
    const val MAX_REDIRECTS = 5

    /** Playback buffering with no progress this long is an error (with retry), not an endless spinner. */
    const val BUFFER_STALL_TIMEOUT_MS = 20_000L

    /** How long the downloaded logo index is used before re-downloading. */
    const val LOGO_INDEX_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** Logo URLs/hosts that answered 4xx are skipped for this long, then tried again. */
    const val BROKEN_LOGO_RETRY_MS = 7L * 24 * 60 * 60 * 1000

    /** A host with this many 4xx logo answers and no successful one is skipped as a whole. */
    const val BROKEN_LOGO_HOST_FAILS = 3

    // --- Database ---
    /** Channels written per transaction while importing a source: bounds memory, keeps readers unblocked. */
    const val IMPORT_BATCH_SIZE = 1_000

    /** Channels loaded per page of the grid (Paging 3); the first load is 3 pages. */
    const val CHANNEL_PAGE_SIZE = 60

    /** Guide programmes written per transaction while importing XMLTV. */
    const val EPG_BATCH_SIZE = 2_000

    /**
     * Past programmes kept for channels without archive: the grid opens with about two hours of the past and the
     * player shows the previous programme. Channels with archive keep their archive depth.
     */
    const val EPG_PAST_MS = 6 * 3_600_000L

    /** Watched channels remembered for "Недавние" (LIVE-010, LIVE-020). */
    const val RECENT_CHANNELS = 30

    /** LIVE-010: a channel goes to "Недавние" after this long playing without a break (full screen or the window). */
    const val RECENT_MIN_WATCH_MS = 60_000L

    // --- Guide matching by name (EPG-005), Dice similarity of letter pairs, 0..1 ---
    /** Bound right away when at least this similar... */
    const val EPG_MATCH_SURE = 0.85

    /** ...and this much more similar than the second best. */
    const val EPG_MATCH_MARGIN = 0.1

    /** Below this a candidate isn't offered for review. */
    const val EPG_MATCH_MIN = 0.5

    /** Candidates shown per channel in the review list. */
    const val EPG_MATCH_SUGGESTIONS = 5

    // --- Xtream short EPG (EPG-002) ---
    /** Programmes asked per request (current + next ones). */
    const val SHORT_EPG_LIMIT = 10

    /** The stored short EPG of a channel is asked again when it has less than this ahead. */
    const val SHORT_EPG_AHEAD_MS = 3_600_000L

    // --- UI / input ---
    const val SEARCH_DEBOUNCE_MS = 250L
    const val PLAYER_INFO_HIDE_MS = 4_000L
    const val SWIPE_THRESHOLD_DP = 80

    /** From this many categories the category list gets its own search field. */
    const val CATEGORY_SEARCH_MIN = 20

    /** The focused channel starts in the preview window after this pause, so fast scrolling doesn't open every stream. */
    const val PREVIEW_DELAY_MS = 600L

    /** Number dialing (LIVE-009): switch this long after the last digit; at most this many digits. */
    const val NUMBER_DIAL_MS = 2_000L
    const val NUMBER_DIAL_DIGITS = 5

    /** A second Back within this time leaves the app (UI-007). */
    const val EXIT_CONFIRM_MS = 2_000L

    /** CH+/CH− in a long list move by this many rows (LIVE-021). */
    const val LIST_PAGE = 10

    /** Toasts and the result of a background task stay this long (UI-015). */
    const val TOAST_MS = 4_000L

    /** Hours of the channel's schedule next to the preview (LIVE-007). */
    const val SCHEDULE_HOURS = 24
}
