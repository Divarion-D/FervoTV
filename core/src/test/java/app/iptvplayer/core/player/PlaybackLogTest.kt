package app.iptvplayer.core.player

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlaybackLogTest {
    @Test
    fun redactionPreservesTheOriginButNeverUntrustedPathsOrFragments() {
        assertEquals("https://provider.example:8443/***?username=***&password=***", redactStreamUrl("https://alice:secret@provider.example:8443/alice/secret/42.ts?username=alice&password=secret#private"))
        assertEquals("http://[::1]:8080/***?***", redactStreamUrl("http://[::1]:8080/private?unlabelled-secret"))
        assertEquals("***", redactStreamUrl("not a valid URL with secret"))
        assertEquals("***", redactStreamUrl("file:///private/password"))
    }

    @Test
    fun logHidesCredentialsInXtreamPathsAndAllQueryValues() {
        val ctx = RuntimeEnvironment.getApplication() as Context
        val log = PlaybackLog(ctx)
        log.record("https://alice:secret@provider.example/live/alice/secret/42.ts?username=alice&access_token=private#secret", "error")
        val line = log.read().lineSequence().last { it.isNotBlank() }
        assertTrue(line.contains("provider.example"))
        assertFalse(line.contains("alice"))
        assertFalse(line.contains("secret"))
        assertFalse(line.contains("private"))
    }

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
