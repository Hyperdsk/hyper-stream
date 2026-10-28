// CloudStream for Android phones, tablets and Android TV, with the same Compose screens as the Mac app.
// Separate from :app (upstream's View-based Android app), with its own application id so both can be installed.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.lagradost.cloudstream4.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.lagradost.cloudstream4.app"
        minSdk = 26
        targetSdk = libs.versions.targetSdk.get().toInt()
        // CI passes its run number, so each build installs over the last one
        versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1
        // CI passes the release tag, e.g. -PappVersion=1.2.0
        versionName = (findProperty("appVersion") as String?) ?: "1.2.8"
    }

    // One key for every release build, here and on CI, so each build installs over the last. Android
    // refuses an update signed with another key. The key is in ~/.gradle/gradle.properties on the
    // Mac (hyperstream.keystore, hyperstream.keystorePassword) and in GitHub secrets for CI
    // (HS_KEYSTORE, HS_KEYSTORE_PASSWORD). Without it, release builds fall back to the debug key.
    val keystore = (findProperty("hyperstream.keystore") as String? ?: System.getenv("HS_KEYSTORE"))?.let(::file)?.takeIf { it.exists() }
    val keystorePassword = findProperty("hyperstream.keystorePassword") as String? ?: System.getenv("HS_KEYSTORE_PASSWORD")
    signingConfigs {
        if (keystore != null && keystorePassword != null) {
            create("hyperstream") {
                storeFile = keystore
                storePassword = keystorePassword
                keyAlias = "hyperstream"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            // Extensions link against the app's and the library's classes by name, so nothing may be renamed or removed
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("hyperstream") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST",
                "META-INF/*.md", "META-INF/versions/9/previous-compilation-data.bin",
            )
            pickFirsts += setOf("META-INF/*.kotlin_module", "**/module-info.class")
        }
    }

    lint {
        // Extensions are loaded from app storage on purpose
        disable += setOf("DynamicCodeLoading")
        // The shared screens live in :appCore, so check its code for Android API levels too
        checkDependencies = true
        abortOnError = false
    }
}

dependencies {
    implementation(project(":appCore"))
    implementation(libs.bundles.compose)
    implementation(libs.activity.compose)
    implementation(libs.appcompat) // Extensions cast their load context to AppCompatActivity
    implementation(libs.material) // Extensions build their settings screens with Material Components (bottom sheets)
    implementation(libs.core.ktx)
    implementation(libs.kotlinx.collections.immutable)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${libs.versions.kotlinxCoroutines.get()}")
    implementation(libs.compose.material.icons.extended.get().toString()) {
        exclude(group = "org.jetbrains.compose.material", module = "material")
    }
}
