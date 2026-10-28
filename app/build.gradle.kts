plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

/**
 * Release signing secrets: env vars on CI (FERVO_KEYSTORE, ...), ~/.gradle/gradle.properties locally (fervo.keystore, ...).
 * Never stored in the repo. Without them the release build is simply unsigned.
 */
fun secret(env: String, prop: String): String? =
    providers.environmentVariable(env).orElse(providers.gradleProperty(prop)).orNull

android {
    namespace = "app.iptvplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.divarion_d.fervotv"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        val keystore = secret("FERVO_KEYSTORE", "fervo.keystore")
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = secret("FERVO_KEYSTORE_PASSWORD", "fervo.keystorePassword")
            keyAlias = secret("FERVO_KEY_ALIAS", "fervo.keyAlias")
            keyPassword = secret("FERVO_KEY_PASSWORD", "fervo.keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("io.coil-kt:coil-compose:2.7.0") // channel logos: memory + disk cache

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    testImplementation("junit:junit:4.13.2")
}
