package app.iptvplayer.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.logos.Backdrop
import app.iptvplayer.core.logos.pickBackdrop
import app.iptvplayer.core.model.Channel
import coil.compose.AsyncImage
import coil.network.HttpException
import coil.request.ImageRequest

private val backdrops = LruCache<String, Backdrop>(512)

/**
 * Remembers logo URLs — and whole hosts — that answer 4xx, so tiles go straight to the next candidate
 * instead of waiting for the same error again. Persisted with a timestamp, retried after [Defaults.BROKEN_LOGO_RETRY_MS].
 * Network errors (timeouts, no connection) are not remembered. Called on the main thread only.
 */
private object BrokenLogos {
    private const val HOST = "host:"
    private lateinit var prefs: SharedPreferences
    private val hostFails = HashMap<String, Int>()
    private val hostLoaded = HashSet<String>()

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("broken_logos", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val expired = prefs.all.filter { (_, t) -> t !is Long || now - t > Defaults.BROKEN_LOGO_RETRY_MS }.keys
        if (expired.isNotEmpty()) prefs.edit().apply { expired.forEach(::remove) }.apply()
    }

    fun isBroken(url: String) = prefs.contains(url) || prefs.contains(HOST + host(url))

    fun loaded(url: String) {
        hostLoaded += host(url)
    }

    fun failed(url: String, error: Throwable) {
        if ((error as? HttpException)?.response?.code !in 400..499) return
        val now = System.currentTimeMillis()
        val edit = prefs.edit().putLong(url, now)
        val h = host(url)
        val fails = (hostFails[h] ?: 0) + 1
        hostFails[h] = fails
        if (h !in hostLoaded && fails >= Defaults.BROKEN_LOGO_HOST_FAILS) edit.putLong(HOST + h, now)
        edit.apply()
    }

    private fun host(url: String) = Uri.parse(url).host.orEmpty()
}

/**
 * Logo with an adaptive backdrop. Tries the playlist logo, then the auto-found one, then shows initials
 * on a brand gradient.
 */
@Composable
fun ChannelLogo(ch: Channel, autoLogo: String?, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    BrokenLogos.init(LocalContext.current)
    val candidates = remember(ch.logo, autoLogo) {
        listOfNotNull(ch.logo, autoLogo).distinct().filterNot(BrokenLogos::isBroken)
    }
    var attempt by remember(candidates) { mutableIntStateOf(0) }
    val url = candidates.getOrNull(attempt)
    var backdrop by remember(url) { mutableStateOf(url?.let { backdrops.get(it) }) }

    // One diagonal gradient style for every tile: tinted by the logo's colour, brand orange otherwise
    // (no logo, still loading, or a logo without a dominant colour).
    fun tinted(tint: Color) = Brush.linearGradient(listOf(lerp(c.surfaceContainerHigh, tint, 0.35f), c.surfaceContainerLow))
    val brush = when (val b = backdrop) {
        null -> tinted(c.primary)
        is Backdrop.Solid -> SolidColor(Color(b.color))
        Backdrop.Light -> Brush.linearGradient(listOf(Color(0xFFF6F1EC), Color(0xFFD6CCC3)))
        is Backdrop.Tinted -> tinted(b.accent?.let { Color(it) } ?: c.primary)
    }

    Box(modifier.background(brush), contentAlignment = Alignment.Center) {
        if (url == null) {
            Text(
                initials(ch.name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = c.primary,
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .allowHardware(false) // software bitmap so the pixels can be read for the backdrop
                    .setHeader("User-Agent", Defaults.USER_AGENT) // e.g. Wikimedia answers 403 to okhttp's default UA
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(10.dp),
                contentScale = ContentScale.Fit,
                onSuccess = {
                    BrokenLogos.loaded(url)
                    if (backdrop == null) {
                        val b = analyze(it.result.drawable)
                        backdrops.put(url, b)
                        backdrop = b
                    }
                },
                onError = {
                    BrokenLogos.failed(url, it.result.throwable)
                    attempt++
                },
            )
        }
    }
}

/**
 * Downscales to 24×24 and lets [pickBackdrop] decide.
 * ponytail: runs on the main thread in onSuccess (576 pixels, cached per URL); move into a Coil Transformation if it ever shows up in profiles.
 */
private fun analyze(d: Drawable): Backdrop {
    val src = (d as? BitmapDrawable)?.bitmap ?: return Backdrop.Tinted(null)
    val n = 24
    val pixels = IntArray(n * n)
    Bitmap.createScaledBitmap(src, n, n, true).getPixels(pixels, 0, n, 0, 0, n, n)
    return pickBackdrop(pixels, n)
}

private fun initials(name: String): String =
    name.split(' ', '-', '_', '.')
        .filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
        .take(2)
        .joinToString("") { it.take(1) }
        .uppercase()
        .ifEmpty { "TV" }
