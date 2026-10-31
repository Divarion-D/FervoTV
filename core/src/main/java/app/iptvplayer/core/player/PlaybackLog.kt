package app.iptvplayer.core.player

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Environment

/** Small persistent playback log for PLR-025. It deliberately stores no credentials. */
class PlaybackLog(private val context: Context) {
    private val file = File(context.filesDir, "playback-errors.log")

    fun record(url: String?, event: String) {
        val safe = url.orEmpty()
            .replace(Regex("(?i)(https?://)([^/@?]+):([^/@?]+)@"), "$1***:***@")
            .replace(Regex("(?i)(password|pass|token|user)=[^&\\s]+"), "$1=***")
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
