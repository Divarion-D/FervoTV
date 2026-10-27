package app.iptvplayer

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
)

const val NO_GROUP = "Без категории"
