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
            implementation(libs.gson) // The Android app bundles Gson and many extensions use it
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
                "jdk.charsets", "jdk.crypto.ec", "jdk.dynalink", "jdk.httpserver", "jdk.localedata", "jdk.unsupported", "jdk.zipfs",
            )
            // The debugger agent, only in debug builds (-PdebugApp)
            if (project.hasProperty("debugApp")) modules("jdk.jdwp.agent")
            // CI passes the release tag, e.g. -PappVersion=1.2.0; installers need MAJOR.MINOR.PATCH with MAJOR > 0
            packageVersion = (findProperty("appVersion") as String?) ?: "1.0.0"

            val iconsRoot = project.file("src/desktop-icons")
            macOS {
                // CloudStream's cloud logo on Apple's icon grid, made from icon.svg (see MACOS.md)
                iconFile.set(iconsRoot.resolve("icon-mac.icns"))
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
// The extension compatibility sweep reads this folder, so a different folder means a new run
tasks.withType<Test>().configureEach {
    inputs.property("csCompatDir", providers.environmentVariable("CS_COMPAT_DIR").orElse(""))
    // The compatibility sweep loads many extensions in one JVM
    maxHeapSize = providers.environmentVariable("CS_TEST_HEAP").orElse("2g").get()
}

// Index of the Android app's own classes (parent class, interface or not), read from its sources.
// Extensions reference app-only classes; their desktop placeholders use this so that, for example,
// a placeholder AniListApi is still a SyncAPI and casts between them work.
val generateAppClassIndex by tasks.registering {
    val sources = rootProject.fileTree("app/src/main/java") { include("**/*.kt") }
    val output = layout.buildDirectory.dir("generated/appClassIndex")
    inputs.files(sources)
    outputs.dir(output)
    doLast {
        val header = Regex(
            """^(?:public |internal )?(?:abstract |open |sealed |data |enum |annotation |value |fun )*(class|interface|object)\s+(\w+)(?:<[^>{]*>)?""" +
                """(?:\s*(?:private |internal |public |protected )?(?:@\w+\s*)*(?:constructor)?\s*\((?:[^()]|\([^()]*\))*\))?(?:\s*:\s*([^{\n]+))?""",
            RegexOption.MULTILINE,
        )
        val lines = sortedSetOf<String>()
        sources.forEach { file ->
            val text = file.readText()
            val pkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) ?: return@forEach
            val imports = Regex("""^import\s+([\w.]+)(?:\s+as\s+\w+)?""", RegexOption.MULTILINE).findAll(text)
                .map { it.groupValues[1] }.associateBy { it.substringAfterLast('.') }
            fun resolve(simple: String) = (imports[simple] ?: "$pkg.$simple").replace('.', '/')
            header.findAll(text).forEach { m ->
                val (kind, name, supers) = m.destructured
                val self = "$pkg.$name".replace('.', '/')
                val type = if (kind == "interface") "interface" else "class"
                val first = supers.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() }
                if (first == null) {
                    lines += "$self - $type"
                    return@forEach
                }
                val isClassParent = first.contains('(')
                val parentName = first.substringBefore('(').substringBefore('<').trim()
                if (parentName.isEmpty() || parentName.contains('.')) {
                    lines += "$self - $type"
                    return@forEach
                }
                // Only a call like Parent() makes it the superclass; otherwise it implements an interface
                lines += if (kind != "interface" && isClassParent) "$self ${resolve(parentName)} $type" else "$self - $type"
            }
        }
        val dir = output.get().asFile.apply { mkdirs() }
        File(dir, "app-class-index.txt").writeText(lines.joinToString("\n"))
    }
}
kotlin.sourceSets.named("jvmMain") { resources.srcDir(generateAppClassIndex) }
