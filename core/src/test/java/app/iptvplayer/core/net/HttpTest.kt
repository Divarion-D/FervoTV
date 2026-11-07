package app.iptvplayer.core.net

import app.iptvplayer.core.FakeServer
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpTest {
    @Test fun requestHeadersReachTheServer() {
        FakeServer { 200 to "ok" }.use { server ->
            assertEquals("ok", httpGet(server.url, "TV", "https://portal/"))
            val headers = server.headers.poll(2, TimeUnit.SECONDS)
            assertEquals("TV", headers?.get("user-agent"))
            assertEquals("https://portal/", headers?.get("referer"))
        }
    }
}
