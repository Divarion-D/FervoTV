package app.iptvplayer.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppUpdateTest {
    @Test fun `new universal apk is selected and notes are retained`() {
        val release = parseLatestRelease(
            response = "{\"tag_name\":\"v0.2.0\",\"name\":\"Fervo TV 0.2.0\",\"body\":\"Fixes\",\"assets\":[{\"name\":\"FervoTV-0.2.0.apk\",\"browser_download_url\":\"https://example/apk\",\"size\":123}]}",
            currentVersion = "0.1.0",
            supportedAbis = listOf("arm64-v8a"),
        )
        assertEquals("0.2.0", release?.version)
        assertEquals("Fixes", release?.notes)
        assertEquals(123L, release?.apk?.size)
    }

    @Test fun `older and non-semver releases are ignored`() {
        assertNull(parseLatestRelease("{\"tag_name\":\"v0.2\",\"assets\":[]}", "0.1.0", emptyList()))
    }

    @Test fun `unsupported abi asset is not selected`() {
        assertNull(parseLatestRelease(
            "{\"tag_name\":\"v0.2.0\",\"assets\":[{\"name\":\"FervoTV-x86.apk\",\"browser_download_url\":\"https://example/apk\"}]}",
            "0.1.0",
            listOf("arm64-v8a"),
        ))
    }
}
