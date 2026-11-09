package com.lagradost.cloudstream4.desktop.data

import android.content.SharedPreferences
import com.fasterxml.jackson.core.type.TypeReference
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** SharedPreferences for extensions, one JSON file per preferences name. */
class DesktopSharedPreferences private constructor(private val file: File) : SharedPreferences {
    private val values = ConcurrentHashMap<String, Any>()
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    init {
        if (file.exists()) {
            runCatching {
                val stored: Map<String, Any?> = jsonMapper.readValue(file, object : TypeReference<Map<String, Any?>>() {})
                stored.forEach { (k, v) -> if (v != null) values[k] = v }
            }.onFailure {
                // Kept, not overwritten by the next save, so an extension's logins and settings can be recovered
                System.err.println("Could not read ${file.name}, starting empty and keeping a copy: ${it.message}")
                runCatching { file.copyTo(File(file.parentFile, "${file.name}.unreadable-${System.currentTimeMillis()}"), overwrite = true) }
            }
        }
    }

    override fun getAll(): Map<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        (values[key] as? Collection<String>)?.toSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int = (values[key] as? Number)?.toInt() ?: defValue
    override fun getLong(key: String, defValue: Long): Long = (values[key] as? Number)?.toLong() ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = (values[key] as? Number)?.toFloat() ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values?.toList() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            val saved = synchronized(this@DesktopSharedPreferences) {
                if (clear) values.clear()
                changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
                save()
            }
            changes.keys.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this@DesktopSharedPreferences, key) } }
            // As Android: false when the change couldn't be written to disk
            return saved
        }

        override fun apply() {
            commit()
        }
    }

    /**
     * Writes to a temp file, flushed to disk, which then replaces the old one: a crash or power cut
     * while saving leaves the previous settings, not a half-written file. True once saved.
     */
    private fun save(): Boolean = runCatching {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(jsonMapper.writeValueAsBytes(HashMap(values)))
            out.fd.sync()
        }
        try {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }.onFailure { System.err.println("Could not save ${file.name}: ${it.message}") }.isSuccess

    companion object {
        private val instances = ConcurrentHashMap<String, DesktopSharedPreferences>()
        private val folder by lazy { File(AppDirs.data, "extension-preferences").apply { mkdirs() } }

        fun get(name: String): DesktopSharedPreferences = instances.getOrPut(name) {
            DesktopSharedPreferences(File(folder, name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json"))
        }
    }
}
