package app.iptvplayer

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class UiHarnessTest {
    @Test
    fun targetPackageIsIsolated() {
        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        requireUiTestPackage(packageName)
    }
}
