package app.iptvplayer.core

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

    // --- UI / input ---
    const val SEARCH_DEBOUNCE_MS = 250L
    const val PLAYER_INFO_HIDE_MS = 4_000L
    const val SWIPE_THRESHOLD_DP = 80

    /** From this many categories the category list gets its own search field. */
    const val CATEGORY_SEARCH_MIN = 20
}
