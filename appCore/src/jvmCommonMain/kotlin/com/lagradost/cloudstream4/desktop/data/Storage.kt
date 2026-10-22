package com.lagradost.cloudstream4.desktop.data

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Folders the app keeps its data in. On macOS this is ~/Library/Application Support/CloudStream, on Android the app's private files. */
object AppDirs {
    val root: File by lazy {
        // Lets tests and portable installs use a different folder
        System.getProperty("cloudstream.dataDir")?.let { return@lazy File(it).apply { mkdirs() } }
        com.lagradost.cloudstream4.desktop.Platform.dataFolder().apply { mkdirs() }
    }

    /** Downloaded .cs3 files, same name as the Android app's folder. */
    val extensions: File by lazy { File(root, "Extensions").apply { mkdirs() } }

    /** JVM jars converted from the .cs3 files. Regenerated whenever an extension updates. */
    val convertedExtensions: File by lazy { File(root, "Extensions-jvm").apply { mkdirs() } }

    val data: File by lazy { File(root, "data").apply { mkdirs() } }
}

val jsonMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)

/**
 * Data files that existed but couldn't be read this run. Their contents are missing from the app,
 * not deleted, so sync mustn't send them as deletions to other devices (see SyncManager).
 */
object DataHealth {
    val unreadable: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
}

/**
 * A single JSON file holding one value. Writes go to a temp file, flushed to disk, which then
 * replaces the old one, so neither a crash nor a power cut leaves a half-written file. A file that
 * can't be read (damaged, or from a newer version) is kept as name.unreadable-<time> rather than
 * overwritten, and noted in [DataHealth].
 */
class JsonFile<T : Any>(
    name: String,
    private val type: TypeReference<T>,
    private val default: () -> T,
) {
    private val file = File(AppDirs.data, name)

    @Synchronized
    fun read(): T = try {
        if (file.exists()) jsonMapper.readValue(file, type) else default()
    } catch (t: Throwable) {
        System.err.println("Could not read ${file.name}, using defaults and keeping a copy: ${t.message}")
        runCatching { file.copyTo(File(file.parentFile, "${file.name}.unreadable-${System.currentTimeMillis()}"), overwrite = true) }
        DataHealth.unreadable += file.name
        default()
    }

    @Synchronized
    fun write(value: T) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value))
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Saves the value [latest] returns at the moment of writing. Two threads that change a store
     * and then save could otherwise write in the wrong order, the older value last.
     */
    @Synchronized
    fun writeLatest(latest: () -> T) = write(latest())

    private val queued = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Saves in the background, soon: for frequent saves (watch progress every few seconds) that
     * shouldn't hold up the screen. Saves asked for while one is queued are one save.
     */
    fun writeSoon(latest: () -> T) {
        if (queued.compareAndSet(false, true)) {
            writer.execute {
                queued.set(false)
                runCatching { writeLatest(latest) }.onFailure { System.err.println("Could not save ${file.name}: $it") }
            }
        }
    }

    companion object {
        private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "data-writer").apply { isDaemon = true } }
    }
}

inline fun <reified T : Any> jsonFile(name: String, noinline default: () -> T): JsonFile<T> =
    JsonFile(name, object : TypeReference<T>() {}, default)
