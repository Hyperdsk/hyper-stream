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
            implementation(compose.desktop.currentOs) {
                // compose.desktop.currentOs imports the wrong material 2, so we exclude it
                exclude(group = "org.jetbrains.compose.material", module = "material")
            }
            // Screens, extensions, storage and the VLC player, shared with the Android app
            implementation(project(":appCore"))
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
            // The app is called Hyper Stream; installer and bundle names avoid the space
            packageName = "HyperStream"
            // Needed for java.net.http, jdk.httpserver and the URLClassLoader based plugin system
            // Extensions are loaded at runtime, so include what they commonly need too (TLS curves, scripting)
            modules(
                "java.instrument", "java.management", "java.naming", "java.net.http", "java.scripting", "java.sql",
                "jdk.charsets", "jdk.crypto.ec", "jdk.dynalink", "jdk.httpserver", "jdk.localedata", "jdk.unsupported", "jdk.zipfs",
            )
            // The debugger agent, only in debug builds (-PdebugApp)
            if (project.hasProperty("debugApp")) modules("jdk.jdwp.agent")
            // CI passes the release tag, e.g. -PappVersion=1.2.0; installers need MAJOR.MINOR.PATCH with MAJOR > 0
            packageVersion = (findProperty("appVersion") as String?) ?: "1.1.2"

            val iconsRoot = project.file("src/desktop-icons")
            macOS {
                // The Hyper Stream HS monogram on Apple's icon grid, made from icon.svg (see branding/README.md)
                iconFile.set(iconsRoot.resolve("icon-mac.icns"))
                // Unchanged since the CloudStream name, so macOS keeps treating it as the same app
                bundleID = "com.lagradost.cloudstream4.desktop"
                dockName = "Hyper Stream"
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
