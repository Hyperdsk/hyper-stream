import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Everything the Mac app and the Android phone and TV app share: screens, extension management,
// storage, downloads and the player model. Shared code lives in jvmCommonMain, which both the
// desktop JVM and Android compile; each platform adds its player and extension loader.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    android {
        namespace = "com.lagradost.cloudstream4.appcore"
        compileSdk = libs.versions.compileSdk.get().toInt()
        // java.nio.file, used for safe file writes, needs Android 8
        minSdk = 26
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
        }
        androidResources {
            enable = true
        }
    }

    jvm()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        all {
            languageSettings {
                optIn("com.lagradost.cloudstream3.InternalAPI")
                optIn("com.lagradost.cloudstream3.Prerelease")
            }
        }

        commonMain.dependencies {
            api(project(":shared"))
            api(project(":library"))
            implementation(libs.bundles.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.coil.network.ktor3) // Poster images
        }

        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.nicehttp) // Same HTTP client the extensions use
                implementation(libs.jackson.module.kotlin) // Same JSON parser the extensions use
                implementation(libs.jsoup)
                implementation(libs.gson) // The Android app bundles Gson and many extensions use it
                implementation(libs.compose.material.icons.extended.get().toString()) {
                    // Only the icon vectors are wanted, not Material 2
                    exclude(group = "org.jetbrains.compose.material", module = "material")
                }
            }
        }

        jvmMain {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.dex2jar.tools) // Converts Android .cs3 extensions to JVM bytecode
                implementation(libs.vlcj) // Video playback through the installed VLC
                implementation(libs.ktor.client.java)
            }
        }

        androidMain {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.activity.compose)
                implementation(libs.core.ktx)
                implementation(libs.ktor.client.android)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${libs.versions.kotlinxCoroutines.get()}")
                implementation(libs.media3.exoplayer)
                implementation(libs.media3.exoplayer.hls)
                implementation(libs.media3.exoplayer.dash)
                implementation(libs.media3.datasource.okhttp)
                implementation(libs.media3.ui)
                implementation(libs.tvprovider) // Continue watching on the TV home screen
            }
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(compose.desktop.currentOs) {
                exclude(group = "org.jetbrains.compose.material", module = "material")
            }
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.lagradost.cloudstream4.appcore.resources"
    generateResClass = always
}

// Platform.appVersion reads this on desktop; Android gets it from PackageManager instead. Release
// builds pass -PappVersion, the same property desktopApp uses for its installers.
tasks.named<org.gradle.jvm.tasks.Jar>("jvmJar") {
    manifest {
        attributes["Implementation-Version"] = (findProperty("appVersion") as String?) ?: "1.2.8"
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
