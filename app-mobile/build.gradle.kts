plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version and release signing: root build.gradle.kts.
// Same applicationId as app-tv for now: installing one replaces the other and keeps the data.
// Separate packages are the owner's call at release time (docs/spec/01-architecture.md §1).
android {
    namespace = "app.iptvplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.divarion_d.fervotv"
        minSdk = 21
        targetSdk = 35
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("io.coil-kt:coil-compose:2.7.0") // channel logos: memory + disk cache

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
}
