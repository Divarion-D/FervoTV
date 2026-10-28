package app.iptvplayer.core

import java.io.Reader

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
