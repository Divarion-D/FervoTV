package app.iptvplayer.core

import androidx.paging.PagingSource
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.Reader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

/**
 * M3U with [n] channels in 50 groups, generated while it's read: the test input itself takes no memory.
 * Channel i: name "Канал i HD", tvg-id "ch<i>", relative logo, URL http://s/<i>.ts.
 */
class SyntheticM3u(private val n: Int) : Reader() {
    private var i = -1
    private var chunk = "#EXTM3U\n"
    private var pos = 0

    override fun read(buf: CharArray, off: Int, len: Int): Int {
        if (pos == chunk.length) {
            if (++i >= n) return -1
            chunk = "#EXTINF:-1 tvg-id=\"ch$i\" tvg-logo=\"logos/$i.png\" group-title=\"Группа ${i % 50}\",Канал $i HD\n" +
                "http://s/$i.ts\n"
            pos = 0
        }
        val k = minOf(len, chunk.length - pos)
        chunk.toCharArray(buf, off, pos, pos + k)
        pos += k
        return k
    }

    override fun close() {}
}

/** Live heap after a full GC. */
fun usedHeap(): Long {
    repeat(2) { System.gc() }
    val r = Runtime.getRuntime()
    return r.totalMemory() - r.freeMemory()
}

/**
 * First page of a list, as Paging does it: a source invalidated by a change that landed while it was being created
 * is replaced by a new one.
 */
suspend fun <T : Any> loadPage(size: Int = 100, key: Int? = null, source: () -> PagingSource<Int, T>): PagingSource.LoadResult.Page<Int, T> {
    repeat(5) {
        val r = source().load(PagingSource.LoadParams.Refresh(key, size, false))
        if (r is PagingSource.LoadResult.Page) return r
    }
    error("the list keeps being invalidated")
}

/**
 * XMLTV with [channels] channels "ch<c>" («Канал c») and [perChannel] 30-minute programmes each from [base],
 * channel by channel as real files are, generated while it's read (+0300 times). [bytes] = how much was read.
 */
class SyntheticXmltv(private val channels: Int, private val perChannel: Int, private val base: Long) : InputStream() {
    var bytes = 0L
        private set
    private var c = -1
    private var k = perChannel
    private var phase = 0 // 0 header, 1 channels, 2 programmes, 3 footer, 4 end
    private var chunk = ByteArray(0)
    private var pos = 0
    private val fmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.ofHours(3))

    private fun t(ms: Long) = fmt.format(Instant.ofEpochMilli(ms)) + " +0300"

    private fun next(): String? = when (phase) {
        0 -> { phase = 1; "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"test\">\n" }
        1 -> if (++c < channels) "<channel id=\"ch$c\"><display-name lang=\"ru\">Канал $c</display-name><icon src=\"http://i/$c.png\"/></channel>\n"
        else { phase = 2; c = -1; next() }
        2 -> {
            if (k >= perChannel) { c++; k = 0 }
            if (c >= channels) { phase = 3; next() } else {
                val start = base + k * 1_800_000L
                k++
                "<programme start=\"${t(start)}\" stop=\"${t(start + 1_800_000)}\" channel=\"ch$c\">" +
                    "<title lang=\"ru\">Передача $c-$k</title><desc lang=\"ru\">Описание передачи номер $k на канале $c: " +
                    "достаточно длинный текст, как в настоящих телегидах.</desc></programme>\n"
            }
        }
        3 -> { phase = 4; "</tv>\n" }
        else -> null
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        if (pos == chunk.size) {
            chunk = (next() ?: return -1).toByteArray()
            pos = 0
        }
        val n = minOf(len, chunk.size - pos)
        System.arraycopy(chunk, pos, buf, off, n)
        pos += n
        bytes += n
        return n
    }
}

/** [src] gzip-compressed on the fly (fastest level), as a stream. */
fun gzipped(src: InputStream): InputStream {
    val input = PipedInputStream(1 shl 16)
    val output = PipedOutputStream(input)
    thread(isDaemon = true) {
        object : GZIPOutputStream(output, 1 shl 16) {
            init { def.setLevel(1) }
        }.use { src.copyTo(it, 1 shl 16) }
    }
    return input
}

/**
 * M3U with [n] films (`type="movie"`) in 40 categories, generated while it's read. Film i: «|EN| Фильм i <word>
 * (year)», where the word cycles through 20, so a word matches 1/20 of the catalogue.
 */
class SyntheticVodM3u(private val n: Int) : Reader() {
    private var i = -1
    private var chunk = "#EXTM3U\n"
    private var pos = 0

    override fun read(buf: CharArray, off: Int, len: Int): Int {
        if (pos == chunk.length) {
            if (++i >= n) return -1
            chunk = "#EXTINF:-1 type=\"movie\" tvg-logo=\"http://p/$i.jpg\" group-title=\"Жанр ${i % 40}\",|EN| Фильм $i ${WORDS[i % 20]} (${1950 + i % 70})\n" +
                "http://s/movie/$i.mkv\n"
            pos = 0
        }
        val k = minOf(len, chunk.length - pos)
        chunk.toCharArray(buf, off, pos, pos + k)
        pos += k
        return k
    }

    override fun close() {}

    companion object {
        val WORDS = listOf(
            "приключения", "комедия", "драма", "детектив", "фантастика", "мелодрама", "триллер", "ужасы", "боевик", "семейный",
            "история", "война", "мюзикл", "вестерн", "спорт", "биография", "аниме", "документальный", "криминал", "мистика",
        )
    }
}

/**
 * A local HTTP server for tests (no real provider): every GET gets [answer] for its path with query → (status, body).
 * [requests] = the paths asked, in order.
 */
class FakeServer(private val answer: (String) -> Pair<Int, String>) : AutoCloseable {
    private val socket = ServerSocket(0)
    val url = "http://127.0.0.1:${socket.localPort}"
    val requests = CopyOnWriteArrayList<String>()

    init {
        thread(isDaemon = true) {
            while (true) {
                val c = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { c.use(::serve) }
            }
        }
    }

    private fun serve(c: Socket) {
        val r = c.getInputStream().bufferedReader()
        val line = r.readLine() ?: return
        while (!r.readLine().isNullOrEmpty()) {}
        val path = line.split(' ')[1]
        requests += path
        val (status, body) = answer(path)
        val b = body.toByteArray()
        c.getOutputStream().write("HTTP/1.1 $status X\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray() + b)
    }

    override fun close() = socket.close()
}
