plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false // Room code generation (core)
}

/**
 * Release signing secrets: env vars on CI (FERVO_KEYSTORE, ...), ~/.gradle/gradle.properties locally (fervo.keystore, ...).
 * Never stored in the repo. Without them the release build is simply unsigned.
 */
fun secret(env: String, prop: String): String? =
    providers.environmentVariable(env).orElse(providers.gradleProperty(prop)).orNull

// app-tv and app-mobile: one version and one release signing for both.
subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            defaultConfig {
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
        }
    }
}
