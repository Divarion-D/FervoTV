package app.iptvplayer.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.logos.Backdrop
import app.iptvplayer.core.logos.pickBackdrop
import app.iptvplayer.core.model.Channel
import coil.compose.AsyncImage
import coil.network.HttpException
import coil.request.ImageRequest
import coil.size.Size
import coil.size.pxOrElse
import coil.transform.Transformation

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
 * Logo baked with its backdrop into one picture (33-design §5). Tries the playlist logo, then the auto-found one
 * ([Channel.autoLogo]). Until a logo is there (or if there is none) the cell is one solid fill with initials; once
 * loaded it is exactly one image and nothing under it: the backdrop, the logo and the rounded corners ([top] /
 * [bottom] radius) are drawn into the bitmap off the main thread, so nothing is clipped or layered in a list.
 */
@Composable
fun ChannelLogo(ch: Channel, modifier: Modifier = Modifier, top: Dp = 8.dp, bottom: Dp = top) {
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    BrokenLogos.init(ctx)
    val candidates = remember(ch.logo, ch.autoLogo) {
        listOfNotNull(ch.logo, ch.autoLogo).distinct().filterNot(BrokenLogos::isBroken)
    }
    var attempt by remember(candidates) { mutableIntStateOf(0) }
    val url = candidates.getOrNull(attempt)
    var loaded by remember(url) { mutableStateOf(false) }
    val density = LocalDensity.current
    val bake = remember(top, bottom, density) {
        with(density) { Bake(top.toPx(), bottom.toPx(), c.surfaceContainerHigh.toArgb(), c.surfaceContainerLow.toArgb(), c.primary.toArgb()) }
    }
    val shape = RoundedCornerShape(top, top, bottom, bottom)

    Box(
        if (loaded) modifier else modifier.background(c.surfaceContainerHighest, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) Text(initials(ch.name), style = TvType.titleSmall, color = c.primary)
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx)
                    .data(url)
                    .transformations(bake)
                    .setHeader("User-Agent", Defaults.USER_AGENT) // e.g. Wikimedia answers 403 to okhttp's default UA
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                onSuccess = {
                    BrokenLogos.loaded(url)
                    loaded = true
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
 * Backdrop + logo + rounded corners in one bitmap of the cell's size (Coil runs transformations off the main
 * thread and caches the result by URL, size and [cacheKey]). The backdrop comes from the logo's pixels
 * ([pickBackdrop]): its own edge colour, light for a dark logo, tinted by its colour otherwise. Colours are the dark
 * scheme's (the TV is always dark); the light theme will add its own key.
 */
private class Bake(
    private val top: Float,
    private val bottom: Float,
    private val high: Int,
    private val low: Int,
    private val primary: Int,
) : Transformation {
    override val cacheKey = "bake:$top:$bottom:dark"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = size.width.pxOrElse { input.width }
        val h = size.height.pxOrElse { input.height }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fun gradient(from: Int, to: Int) = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), from, to, Shader.TileMode.CLAMP)
        fun tinted(tint: Int) = gradient(ColorUtils.blendARGB(high, tint, 0.35f), low)
        when (val b = analyze(input)) {
            is Backdrop.Solid -> fill.color = b.color
            Backdrop.Light -> fill.shader = gradient(0xFFF6F1EC.toInt(), 0xFFD6CCC3.toInt())
            is Backdrop.Tinted -> fill.shader = tinted(b.accent ?: primary)
        }
        val r = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
        canvas.drawPath(Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, Path.Direction.CW) }, fill)
        // The logo fits inside a 10 % margin.
        val bw = w * 0.8f
        val bh = h * 0.8f
        val scale = minOf(bw / input.width, bh / input.height)
        val dw = input.width * scale
        val dh = input.height * scale
        val dst = RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
        canvas.drawBitmap(input, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        // Its pixels are never read again: a hardware bitmap stays on the GPU, so scrolling doesn't upload it again.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return out
        return out.copy(Bitmap.Config.HARDWARE, false)?.also { out.recycle() } ?: out
    }
}

/** Downscales to 24×24 and lets [pickBackdrop] decide (off the main thread, inside [Bake]). */
private fun analyze(src: Bitmap): Backdrop {
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
