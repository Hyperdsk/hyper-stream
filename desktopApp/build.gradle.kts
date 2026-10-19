import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm {}
    sourceSets {
        jvmMain.dependencies {
            implementation(libs.bundles.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.collections.immutable)
            implementation(compose.desktop.currentOs) {
                // compose.desktop.currentOs imports the wrong material 2, so we exclude it
                exclude(group = "org.jetbrains.compose.material", module = "material")
            }
            implementation(project(":shared"))
            implementation(project(":library"))
            implementation(libs.nicehttp) // Same HTTP client the extensions use
            implementation(libs.jackson.module.kotlin) // Same JSON parser the extensions use
            implementation(libs.jsoup)
            implementation(libs.dex2jar.tools) // Converts Android .cs3 extensions to JVM bytecode
            implementation(libs.vlcj) // Video playback through the installed VLC
            implementation(libs.coil.network.ktor3) // Poster images
            implementation(libs.compose.material.icons.extended.get().toString()) {
                // Only the icon vectors are wanted, not Material 2
                exclude(group = "org.jetbrains.compose.material", module = "material")
            }
            implementation(libs.ktor.client.java)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// java.lang.System::load has been called by org.jetbrains.skiko.LibraryLoader in an unnamed module
tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

compose.desktop {
    application {
        mainClass = "com.lagradost.cloudstream4.MainKt"
        // ./gradlew :desktopApp:run -PdebugApp  starts with a debugger listening on port 5005
        if (project.hasProperty("debugApp")) {
            jvmArgs += listOf("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005")
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "CloudStream"
            // Needed for java.net.http, jdk.httpserver and the URLClassLoader based plugin system
            // Extensions are loaded at runtime, so include what they commonly need too (TLS curves, scripting)
            modules(
                "java.instrument", "java.management", "java.naming", "java.net.http", "java.scripting", "java.sql",
                "jdk.crypto.ec", "jdk.dynalink", "jdk.httpserver", "jdk.localedata", "jdk.unsupported",
            )
            packageVersion = "1.0.0"

            val iconsRoot = project.file("desktop-icons")
            macOS {
                // iconFile.set(iconsRoot.resolve("icon-mac.icns"))
                bundleID = "com.lagradost.cloudstream4.desktop"
                appCategory = "public.app-category.entertainment"
            }
            windows {
                iconFile.set(iconsRoot.resolve("icon-windows.ico"))
                // menuGroup = "Compose Examples"
                // see https://wixtoolset.org/documentation/manual/v3/howtos/general/generate_guids.html
                // upgradeUuid = ""
            }
            linux {
                iconFile.set(iconsRoot.resolve("icon-linux.png"))
            }
        }

        //buildTypes.release.proguard {
        //    configurationFiles.from(project.file("rules.pro"))
        //}
    }
}