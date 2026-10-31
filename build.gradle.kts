plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false // Room code generation (core)
}

/**
 * Release signing secrets: env vars on CI (FERVO_KEYSTORE, ...), locally `signing/signing.properties` next to the
 * keystore (git-ignored), or `fervo.*` Gradle properties. Never committed. Without them the release build is unsigned.
 */
val signingDir = rootProject.file("signing")
val localSigning = java.util.Properties().apply {
    signingDir.resolve("signing.properties").takeIf { it.isFile }?.reader()?.use { load(it) }
}

fun secret(env: String, prop: String): String? =
    providers.environmentVariable(env).orElse(providers.gradleProperty(prop)).orNull
        ?: localSigning.getProperty(prop.removePrefix("fervo."))
            ?.let { if (prop == "fervo.keystore") signingDir.resolve(it).path else it }

// app-tv and app-mobile: one version and one release signing for both.
subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            defaultConfig {
                // 2: 0.1.0 supersedes the initial two-component 0.1 build.
                versionCode = 2
                versionName = "0.1.0"
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
                // Debug installs next to the signed release instead of failing on the signature mismatch.
                debug {
                    applicationIdSuffix = ".debug"
                    manifestPlaceholders["appLabel"] = "Fervo TV debug"
                }
                release {
                    manifestPlaceholders["appLabel"] = "Fervo TV"
                    signingConfig = signingConfigs.findByName("release")
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
                }
            }
        }
    }
}
