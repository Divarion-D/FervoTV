package app.iptvplayer.core.player

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Environment
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

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

    fun recordCrash(error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            .replace(Regex("https?://[^\\s]+")) { redactStreamUrl(it.value) }
            .take(32_768)
        record(null, "crash\n$stack")
    }

    fun export(target: File): Boolean = runCatching {
        target.parentFile?.mkdirs()
        target.writeText(read())
        true
    }.getOrDefault(false)

    /** Android 10+ publishes to shared Downloads without broad storage permission; older devices use app-scoped Downloads. */
    fun exportToDownloads(): Uri? {
        if (Build.VERSION.SDK_INT < 29) {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
            val target = File(dir, "fervotv-playback-errors.log")
            return target.takeIf { export(it) }?.let(Uri::fromFile)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "fervotv-playback-errors.log")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return null
        return runCatching {
            resolver.openOutputStream(uri, "w")!!.bufferedWriter().use { it.write(read()) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        }.getOrElse { resolver.delete(uri, null, null); null }
    }
}

object CrashLogger {
    private val installed = AtomicBoolean()

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val log = PlaybackLog(context.applicationContext)
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            log.recordCrash(error)
            previous?.uncaughtException(thread, error)
        }
    }
}
