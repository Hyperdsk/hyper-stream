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
 * A single JSON file holding one value. Writes go to a temp file first and are then moved
 * into place, so a crash mid-write never corrupts the stored data.
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
        System.err.println("Could not read ${file.name}, using defaults: ${t.message}")
        default()
    }

    @Synchronized
    fun write(value: T) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        jsonMapper.writerWithDefaultPrettyPrinter().writeValue(tmp, value)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

inline fun <reified T : Any> jsonFile(name: String, noinline default: () -> T): JsonFile<T> =
    JsonFile(name, object : TypeReference<T>() {}, default)
