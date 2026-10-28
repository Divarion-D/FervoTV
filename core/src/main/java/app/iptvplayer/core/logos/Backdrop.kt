package app.iptvplayer.core.logos

/**
 * Logo backdrop picked from the logo's own pixels, because one fixed background fails half the logos
 * (black text vanishes on dark, white on light). Colours are ARGB ints; the apps draw them.
 */
sealed interface Backdrop {
    /** Opaque logo (has its own background): continue its edge colour so it fills the tile. */
    data class Solid(val color: Int) : Backdrop

    /** Dark artwork on transparency: needs a light plate. */
    data object Light : Backdrop

    /** Light or colourful artwork on transparency: the tile gradient tinted with the logo's main colour. */
    data class Tinted(val accent: Int?) : Backdrop
}

/**
 * [pixels] = ARGB of the logo downscaled to [n]×[n], row by row. Looks at opacity, brightness,
 * share of near-black pixels, edge colour and the most saturated colours.
 */
fun pickBackdrop(pixels: IntArray, n: Int): Backdrop {
    var opaque = 0
    var lum = 0.0
    var dark = 0
    var edgeR = 0; var edgeG = 0; var edgeB = 0; var edgeN = 0
    var accR = 0f; var accG = 0f; var accB = 0f; var accW = 0f
    for (y in 0 until n) for (x in 0 until n) {
        val p = pixels[y * n + x]
        if (p ushr 24 < 128) continue
        val r = p shr 16 and 0xFF; val g = p shr 8 and 0xFF; val b = p and 0xFF
        opaque++
        val l = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
        lum += l
        if (l < 0.18) dark++
        if (x == 0 || y == 0 || x == n - 1 || y == n - 1) {
            edgeR += r; edgeG += g; edgeB += b; edgeN++
        }
        // HSV saturation and value, as android.graphics.Color.colorToHSV computes them.
        val max = maxOf(r, g, b)
        val sat = if (max == 0) 0f else (max - minOf(r, g, b)) / max.toFloat()
        if (sat > 0.35f && max / 255f > 0.3f) {
            accR += r * sat; accG += g * sat; accB += b * sat; accW += sat
        }
    }
    return when {
        opaque == 0 -> Backdrop.Tinted(null)
        opaque > n * n * 0.9 && edgeN > 0 -> Backdrop.Solid(rgb(edgeR / edgeN, edgeG / edgeN, edgeB / edgeN))
        // Dark on average, or a large share of near-black ink (e.g. green mark + black wordmark).
        lum / opaque < 0.3 || dark > opaque * 0.25 -> Backdrop.Light
        else -> Backdrop.Tinted(if (accW > 0) rgb(Math.round(accR / accW), Math.round(accG / accW), Math.round(accB / accW)) else null)
    }
}

private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
