package com.lagradost.cloudstream4.desktop.plugins

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Repairs nesting information that dex2jar guesses wrong.
 *
 * Shrunk extensions lose the "enclosing method" marker of local classes, such as a data class
 * declared inside a function. dex2jar then records them as nested classes of the top-level
 * class. Kotlin reflection (used by Jackson to parse JSON into those classes) looks for that
 * nested class in the outer class's metadata, fails, and throws "Unresolved class".
 *
 * A real nested class is always named `Outer$Inner`. Any entry whose names don't line up that
 * way is turned back into a plain class without a declaring class, which is how Android sees it.
 */
internal object InnerClassFixer {
    /** Writes the classes in [source], a jar or a folder of class files, to the jar [out]. */
    fun fix(source: File, out: File) {
        ZipOutputStream(out.outputStream().buffered()).use { output ->
            fun put(name: String, bytes: ByteArray) {
                output.putNextEntry(ZipEntry(name))
                output.write(if (name.endsWith(".class")) fixClass(bytes) else bytes)
                output.closeEntry()
            }
            if (source.isDirectory) {
                source.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                    put(file.relativeTo(source).invariantSeparatorsPath, file.readBytes())
                }
            } else {
                ZipFile(source).use { zip ->
                    for (entry in zip.entries()) {
                        if (!entry.isDirectory) put(entry.name, zip.getInputStream(entry).use { it.readBytes() })
                    }
                }
            }
        }
    }

    private fun fixClass(bytes: ByteArray): ByteArray {
        val reader = ClassReader(bytes)
        var changed = false
        // No frame recomputation: only the InnerClasses attribute changes
        val writer = ClassWriter(reader, 0)
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                if (outerName != null && innerName != null && name != "$outerName$$innerName") {
                    changed = true
                    super.visitInnerClass(name, null, innerName, access and Opcodes.ACC_STATIC.inv())
                } else {
                    super.visitInnerClass(name, outerName, innerName, access)
                }
            }
        }, 0)
        return if (changed) writer.toByteArray() else bytes
    }
}
