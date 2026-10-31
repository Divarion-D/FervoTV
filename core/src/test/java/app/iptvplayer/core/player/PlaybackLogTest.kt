package app.iptvplayer.core.player

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlaybackLogTest {
    @Test
    fun logDoesNotPersistQueryCredentials() {
        val ctx = RuntimeEnvironment.getApplication() as Context
        val log = PlaybackLog(ctx)
        log.record("http://provider.example/live?user=test&password=secret&token=abc", "error")
        val text = log.read()
        assertTrue(text.contains("password=***"))
        assertTrue(text.contains("token=***"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("abc"))
    }
}
