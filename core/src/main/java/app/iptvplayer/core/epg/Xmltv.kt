package app.iptvplayer.core.epg

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** `<channel id>` of an XMLTV file: its display-names and icon. */
class XmltvChannel(val id: String, val names: List<String>, val icon: String?)

/** `<programme>`; times are epoch ms (UTC), as written in the file, without any user time shift. */
class XmltvProgramme(val channel: String, val start: Long, val stop: Long, val title: String, val description: String?)

/** A gzip stream is recognized by its signature, not by the file name or the HTTP headers (EPG-003). */
fun unpacked(input: InputStream): InputStream {
    val b = BufferedInputStream(input, BUFFER)
    b.mark(2)
    val gzip = b.read() == 0x1f && b.read() == 0x8b
    b.reset()
    return if (gzip) GZIPInputStream(b, BUFFER) else b
}

/**
 * Streams an XMLTV document (EPG-003): elements are handed out one at a time, memory doesn't grow with the file.
 * Programmes of channels [wants] says no to are skipped without reading them. A programme with a broken time is
 * skipped alone; one without `stop` ends where the next programme of its channel starts. A malformed document
 * throws [org.xmlpull.v1.XmlPullParserException] at the point of damage, after everything before it was handed out.
 */
fun parseXmltv(
    input: InputStream,
    wants: (channel: String) -> Boolean,
    onChannel: (XmltvChannel) -> Unit,
    onProgramme: (XmltvProgramme) -> Unit,
) {
    val p = Xml.newPullParser()
    // Lenient about undeclared entities (&nbsp;) and similar slips of real-world generators.
    runCatching { p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
    p.setInput(input, null) // encoding from the XML declaration
    val open = HashMap<String, XmltvProgramme>() // programmes without stop, waiting for the next one of their channel
    var event = p.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) when (p.name) {
            "channel" -> readChannel(p)?.let(onChannel)
            "programme" -> {
                val channel = p.getAttributeValue(null, "channel")
                val start = p.getAttributeValue(null, "start")?.let(::parseXmltvTime)
                if (channel == null || start == null || !wants(channel)) {
                    skip(p)
                } else {
                    val stop = p.getAttributeValue(null, "stop")?.let(::parseXmltvTime)
                    val prog = readProgramme(p, channel, start, stop ?: start)
                    open.remove(channel)?.let { o -> if (start > o.start) onProgramme(o.endingAt(start)) }
                    when {
                        stop == null -> open[channel] = prog
                        stop > start -> onProgramme(prog)
                    }
                }
            }
        }
        event = p.next()
    }
}

private fun XmltvProgramme.endingAt(t: Long) = XmltvProgramme(channel, start, t, title, description)

private fun readChannel(p: XmlPullParser): XmltvChannel? {
    val id = p.getAttributeValue(null, "id")
    val names = ArrayList<String>(2)
    var icon: String? = null
    var depth = 1
    while (depth > 0) when (p.next()) {
        XmlPullParser.START_TAG -> when (p.name) {
            "display-name" -> text(p).takeIf { it.isNotEmpty() }?.let(names::add)
            "icon" -> { if (icon == null) icon = p.getAttributeValue(null, "src")?.ifBlank { null }; skip(p) }
            else -> skip(p)
        }
        XmlPullParser.END_TAG -> depth--
        XmlPullParser.END_DOCUMENT -> return null
    }
    return id?.takeIf { it.isNotBlank() }?.let { XmltvChannel(it, names, icon) }
}

private fun readProgramme(p: XmlPullParser, channel: String, start: Long, stop: Long): XmltvProgramme {
    var title: String? = null
    var description: String? = null
    var depth = 1
    while (depth > 0) when (p.next()) {
        // The first one of each wins (several = several languages).
        XmlPullParser.START_TAG -> when (p.name) {
            "title" -> text(p).let { if (title == null) title = it }
            "desc" -> text(p).let { if (description == null) description = it.ifEmpty { null } }
            else -> skip(p)
        }
        XmlPullParser.END_TAG -> depth--
        XmlPullParser.END_DOCUMENT -> break
    }
    return XmltvProgramme(channel, start, stop, title.orEmpty(), description)
}

/** Text of the current element (nested tags dropped); leaves the parser on its end tag. */
private fun text(p: XmlPullParser): String {
    val sb = StringBuilder()
    var depth = 1
    while (depth > 0) when (p.next()) {
        XmlPullParser.TEXT -> sb.append(p.text)
        XmlPullParser.START_TAG -> depth++
        XmlPullParser.END_TAG -> depth--
        XmlPullParser.END_DOCUMENT -> break
    }
    return sb.toString().trim()
}

/** Skips the current element with everything inside; leaves the parser on its end tag. */
private fun skip(p: XmlPullParser) {
    var depth = 1
    while (depth > 0) when (p.next()) {
        XmlPullParser.START_TAG -> depth++
        XmlPullParser.END_TAG -> depth--
        XmlPullParser.END_DOCUMENT -> return
    }
}

/**
 * XMLTV time `YYYYMMDDhhmmss ±hhmm` → epoch ms. Seconds, minutes and hours may be left out; no offset = UTC
 * (as the XMLTV DTD says); `+03:00` is accepted too. null if it isn't a time.
 */
fun parseXmltvTime(s: String): Long? {
    val t = s.trim()
    var digits = 0
    while (digits < t.length && t[digits] in '0'..'9') digits++
    if (digits < 8) return null
    fun n(from: Int, len: Int): Int {
        if (from + len > digits) return 0
        var v = 0
        for (i in from until from + len) v = v * 10 + (t[i] - '0')
        return v
    }
    val month = n(4, 2)
    val day = n(6, 2)
    val hour = n(8, 2)
    val minute = n(10, 2)
    val second = n(12, 2)
    if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
    var offsetMin = 0
    val zone = t.substring(digits).trim()
    if (zone.isNotEmpty() && (zone[0] == '+' || zone[0] == '-')) {
        val z = zone.substring(1).filter { it in '0'..'9' }
        if (z.length != 2 && z.length != 4) return null
        offsetMin = z.substring(0, 2).toInt() * 60 + (if (z.length == 4) z.substring(2).toInt() else 0)
        if (zone[0] == '-') offsetMin = -offsetMin
    }
    val days = daysFromCivil(n(0, 4), month, day)
    return ((days * 24 + hour) * 60 + minute - offsetMin) * 60_000L + second * 1000L
}

/** Days since 1970-01-01 of a proleptic Gregorian date (H. Hinnant's algorithm; java.time needs API 26). */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (month + if (month > 2) -3 else 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468
}

private const val BUFFER = 64 * 1024
