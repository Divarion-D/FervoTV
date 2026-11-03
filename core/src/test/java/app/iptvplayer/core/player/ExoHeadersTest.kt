package app.iptvplayer.core.player

import android.os.Looper
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.FakeServer
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExoHeadersTest {
    @Test fun itemHeadersReachHttpAndDoNotLeakToTheNextItem() {
        checkHeaders("User-Agent")
    }

    @Test fun lowerCaseUserAgentIsAlsoHonoured() {
        checkHeaders("user-agent")
    }

    private fun checkHeaders(agentHeader: String) {
        FakeServer { 200 to "not a media file" }.use { server ->
            val engine = ExoEngine(RuntimeEnvironment.getApplication())
            try {
                engine.play(PlayItem("${server.url}/first.mp4", headers = mapOf(
                    agentHeader to "FervoTV-P3-Test", "Referer" to "https://p3.invalid/test",
                )))
                shadowOf(Looper.getMainLooper()).idle()
                val first = server.headers.poll(10, TimeUnit.SECONDS)
                assertNotNull("ExoPlayer must request the item", first)
                assertEquals("FervoTV-P3-Test", first!!["user-agent"])
                assertEquals("https://p3.invalid/test", first["referer"])
                engine.stop()
                server.headers.clear()
                engine.play(PlayItem("${server.url}/second.mp4"))
                shadowOf(Looper.getMainLooper()).idle()
                val second = server.headers.poll(10, TimeUnit.SECONDS)
                assertNotNull("ExoPlayer must request the next item", second)
                assertEquals(Defaults.USER_AGENT, second!!["user-agent"])
                assertEquals(null, second["referer"])
            } finally {
                engine.release()
            }
        }
    }
}
