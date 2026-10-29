package app.iptvplayer.core.net

import app.iptvplayer.core.CoreException
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.ErrorCode
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Non-2xx answer; [code] lets callers tell "access denied" from other failures. */
class HttpException(val code: Int) : IOException("HTTP $code")

fun httpGet(url: String): String = httpStream(url) { it.bufferedReader().readText() }

/**
 * GET and read the body. Follows redirects manually because HttpURLConnection won't cross http<->https.
 * [userAgent]: an EPG source can have its own (EPG-001).
 */
fun <T> httpStream(url: String, userAgent: String = Defaults.USER_AGENT, read: (InputStream) -> T): T {
    var u = URL(url)
    repeat(Defaults.MAX_REDIRECTS) {
        val c = u.openConnection() as HttpURLConnection
        c.connectTimeout = Defaults.CONNECT_TIMEOUT_MS
        c.readTimeout = Defaults.READ_TIMEOUT_MS
        c.setRequestProperty("User-Agent", userAgent)
        try {
            val code = c.responseCode
            if (code in 300..399) {
                u = URL(u, c.getHeaderField("Location") ?: throw CoreException(ErrorCode.BAD_REDIRECT, code.toString()))
                return@repeat
            }
            if (code !in 200..299) throw HttpException(code)
            return c.inputStream.use(read)
        } finally {
            c.disconnect()
        }
    }
    throw CoreException(ErrorCode.TOO_MANY_REDIRECTS)
}
