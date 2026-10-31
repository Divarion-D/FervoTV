// Everything that isn't UI: models, sources, parsers, database, logo matching, playback engine, input commands, settings, Defaults. No Compose.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.iptvplayer.core"
    compileSdk = 35

    defaultConfig { minSdk = 21 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions.unitTests.all {
        // SRC-006: a 100 000-channel import must fit the heap of a mid-range TV box.
        it.maxHeapSize = "256m"
    }
}

// Room schema history, committed: each version's JSON is what its migration is checked against.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    implementation("androidx.room:room-paging:$room")
    ksp("androidx.room:room-compiler:$room")
    api("androidx.paging:paging-common:3.3.5") // screens get PagingData<Channel>

    // Playback engine (player/ExoEngine.kt): the apps see only the PlaybackEngine interface.
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3") // PLR-020 DASH container support
    implementation("androidx.media3:media3-session:$media3") // PLR-029 media keys and audio focus

    implementation("com.google.zxing:core:3.5.3") // QR codes (Qr.kt); R8 keeps only the encoder

    testImplementation("junit:junit:4.13.2")
    // Room/SQLite and android.util.JsonReader on the JVM, no device needed.
    testImplementation("org.robolectric:robolectric:4.14.1")
}
