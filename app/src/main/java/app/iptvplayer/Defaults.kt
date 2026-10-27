package app.iptvplayer

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

    // --- About ---
    const val AUTHOR = "Divarion-D"
    const val GITHUB_URL = "https://github.com/Divarion-D/FervoTV"

    // --- Network ---
    const val USER_AGENT = "FervoTV/0.1 (Android)"
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 60_000
    const val MAX_REDIRECTS = 5

    /** How long the downloaded logo index is used before re-downloading. */
    const val LOGO_INDEX_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** Logo URLs/hosts that answered 4xx are skipped for this long, then tried again. */
    const val BROKEN_LOGO_RETRY_MS = 7L * 24 * 60 * 60 * 1000

    /** A host with this many 4xx logo answers and no successful one is skipped as a whole. */
    const val BROKEN_LOGO_HOST_FAILS = 3

    // --- UI / input ---
    const val SEARCH_DEBOUNCE_MS = 250L
    const val PLAYER_INFO_HIDE_MS = 4_000L
    const val SWIPE_THRESHOLD_DP = 80
}
