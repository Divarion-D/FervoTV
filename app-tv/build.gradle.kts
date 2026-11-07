plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version and release signing: root build.gradle.kts.
android {
    namespace = "app.iptvplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.divarion_d.fervotv"
        minSdk = 21
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("uiTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".uitest"
            matchingFallbacks += listOf("debug")
            manifestPlaceholders["appLabel"] = "Fervo TV UI test"
        }
    }
    testBuildType = "uiTest"

    sourceSets {
        getByName("testDebug").java.srcDir("src/sharedUiTest/java")
        getByName("androidTest").java.srcDir("src/sharedUiTest/java")
    }

    buildFeatures { compose = true }
    testOptions.unitTests.isIncludeAndroidResources = true
    // UI-022: the language is switched inside the app, so every translation must be in the APK, not in Play splits.
    bundle { language { enableSplit = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation(files(rootProject.file("third_party/media3-ffmpeg/media3-decoder-ffmpeg-1.5.1.aar")))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("io.coil-kt:coil-compose:2.7.0") // channel logos: memory + disk cache
    implementation("androidx.paging:paging-compose:3.3.5") // channel grid straight from the database

    testImplementation("junit:junit:4.13.2") // NoUiTextInCodeTest
    add("testDebugImplementation", "org.robolectric:robolectric:4.14.1")
    add("testDebugImplementation", "androidx.room:room-runtime:2.6.1")
    add("testDebugImplementation", "androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.0")
}
