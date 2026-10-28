package app.iptvplayer.core.net

import app.iptvplayer.core.Defaults
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

fun httpGet(url: String): String = httpStream(url) { it.bufferedReader().readText() }

/** GET and read the body. Follows redirects manually because HttpURLConnection won't cross http<->https. */
fun <T> httpStream(url: String, read: (InputStream) -> T): T {
    var u = URL(url)
    repeat(Defaults.MAX_REDIRECTS) {
        val c = u.openConnection() as HttpURLConnection
        c.connectTimeout = Defaults.CONNECT_TIMEOUT_MS
        c.readTimeout = Defaults.READ_TIMEOUT_MS
        c.setRequestProperty("User-Agent", Defaults.USER_AGENT)
        try {
            val code = c.responseCode
            if (code in 300..399) {
                u = URL(u, c.getHeaderField("Location") ?: throw IOException("HTTP $code без Location"))
                return@repeat
            }
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.use(read)
        } finally {
            c.disconnect()
        }
    }
    throw IOException("Слишком много перенаправлений")
}
