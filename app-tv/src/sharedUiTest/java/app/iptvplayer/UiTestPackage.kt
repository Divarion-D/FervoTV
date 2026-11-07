package app.iptvplayer

internal const val UI_TEST_PACKAGE = "io.github.divarion_d.fervotv.uitest"

internal fun requireUiTestPackage(packageName: String) {
    require(packageName == UI_TEST_PACKAGE) { "Refusing to modify non-UI-test package: $packageName" }
}
