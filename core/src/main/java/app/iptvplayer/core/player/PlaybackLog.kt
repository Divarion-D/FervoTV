package app.iptvplayer.core.player

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Environment
import java.net.URI

/** Provider paths can contain credentials (Xtream); hide the whole path rather than guessing their position. */
fun redactStreamUrl(url: String): String = runCatching {
    val uri = URI(url)
    require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true))
    require(!uri.host.isNullOrBlank())
    val origin = URI(uri.scheme, null, uri.host, uri.port, null, null, null).toASCIIString()
    val path = if (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") uri.rawPath.orEmpty() else "/***"
    val query = uri.rawQuery?.split('&')?.joinToString("&", prefix = "?") { parameter ->
        val name = parameter.substringBefore('=')
        if ('=' in parameter && name.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,63}"))) "$name=***" else "***"
    }.orEmpty()
    origin + path + query
}.getOrDefault("***")

/** Small persistent playback log for PLR-025. It deliberately stores no credentials. */
class PlaybackLog(private val context: Context) {
    private val file = File(context.filesDir, "playback-errors.log")

    fun record(url: String?, event: String) {
        val safe = url?.let(::redactStreamUrl).orEmpty()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText("$stamp\t$event\t$safe\n")
        }
    }

    fun read(): String = runCatching { file.readText() }.getOrDefault("")

    fun export(target: File): Boolean = runCatching {
        target.parentFile?.mkdirs()
        target.writeText(read())
        true
    }.getOrDefault(false)

    /** PLR-025: app-scoped Downloads is writable on every supported Android version without broad storage access. */
    fun exportToDownloads(): File? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val target = File(dir, "fervotv-playback-errors.log")
        return target.takeIf { export(it) }
    }
}
