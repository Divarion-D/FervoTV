package app.iptvplayer

import org.junit.Assert.assertThrows
import org.junit.Test

class UiTestPackageTest {
    @Test
    fun acceptsOnlyUiTestPackage() {
        requireUiTestPackage(UI_TEST_PACKAGE)

        listOf(
            "io.github.divarion_d.fervotv",
            "io.github.divarion_d.fervotv.debug",
            "io.github.divarion_d.fervotv.uitest.other",
        ).forEach { packageName ->
            assertThrows(IllegalArgumentException::class.java) { requireUiTestPackage(packageName) }
        }
    }
}
