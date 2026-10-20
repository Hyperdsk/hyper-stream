package com.lagradost.cloudstream4.desktop.plugins

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Generates do-nothing placeholders for Android classes an extension references but the
 * desktop doesn't have, such as DialogFragment, View or Toast.
 *
 * Extensions often include Android settings screens. Their code only runs when the user opens
 * those screens, but the JVM still has to find every class the extension's classes mention
 * while it loads and verifies them. Each placeholder has exactly the constructors, methods and
 * fields the extension uses. Methods do nothing and return 0, false or null, and static fields
 * holding the class's own type (Kotlin `object` INSTANCE, Companion) are filled in, so calls
 * through them don't fail.
 *
 * Only Android and CloudStream app packages get placeholders. Real classes always win: the
 * desktop stand-ins and the library are found first.
 */
internal object AndroidStubs {
    private val stubbable = listOf("android/", "androidx/", "com/google/android/", "com/lagradost/cloudstream3/", "dalvik/")

    /**
     * Known parents for common Android types, so the JVM verifier accepts code that passes, for
     * example, an Activity where a Context is expected or a TextView where a View is expected.
     */
    private val knownParents = mapOf(
        "android/content/ContextWrapper" to "android/content/Context",
        "android/view/ContextThemeWrapper" to "android/content/ContextWrapper",
        "android/app/Application" to "android/content/ContextWrapper",
        "android/app/Service" to "android/content/ContextWrapper",
        "androidx/activity/ComponentActivity" to "android/app/Activity",
        "androidx/core/app/ComponentActivity" to "android/app/Activity",
        "androidx/fragment/app/FragmentActivity" to "android/app/Activity",
        "androidx/fragment/app/DialogFragment" to "androidx/fragment/app/Fragment",
        "androidx/appcompat/app/AppCompatDialogFragment" to "androidx/fragment/app/DialogFragment",
        "com/google/android/material/bottomsheet/BottomSheetDialogFragment" to "androidx/appcompat/app/AppCompatDialogFragment",
        "androidx/preference/PreferenceFragmentCompat" to "androidx/fragment/app/Fragment",
        "android/app/Dialog" to "java/lang/Object",
        "androidx/appcompat/app/AppCompatDialog" to "android/app/Dialog",
        "com/google/android/material/bottomsheet/BottomSheetDialog" to "androidx/appcompat/app/AppCompatDialog",
        "androidx/appcompat/app/AlertDialog" to "androidx/appcompat/app/AppCompatDialog",
        "android/app/AlertDialog" to "android/app/Dialog",
        "android/view/ViewGroup" to "android/view/View",
        "android/widget/FrameLayout" to "android/view/ViewGroup",
        "android/widget/LinearLayout" to "android/view/ViewGroup",
        "android/widget/RelativeLayout" to "android/view/ViewGroup",
        "android/widget/ScrollView" to "android/widget/FrameLayout",
        "androidx/core/widget/NestedScrollView" to "android/widget/FrameLayout",
        "androidx/constraintlayout/widget/ConstraintLayout" to "android/view/ViewGroup",
        "androidx/recyclerview/widget/RecyclerView" to "android/view/ViewGroup",
        "androidx/cardview/widget/CardView" to "android/widget/FrameLayout",
        "com/google/android/material/card/MaterialCardView" to "androidx/cardview/widget/CardView",
        "android/widget/TextView" to "android/view/View",
        "androidx/appcompat/widget/AppCompatTextView" to "android/widget/TextView",
        "android/widget/EditText" to "android/widget/TextView",
        "androidx/appcompat/widget/AppCompatEditText" to "android/widget/EditText",
        "com/google/android/material/textfield/TextInputEditText" to "androidx/appcompat/widget/AppCompatEditText",
        "com/google/android/material/textfield/TextInputLayout" to "android/widget/LinearLayout",
        "android/widget/Button" to "android/widget/TextView",
        "androidx/appcompat/widget/AppCompatButton" to "android/widget/Button",
        "com/google/android/material/button/MaterialButton" to "androidx/appcompat/widget/AppCompatButton",
        "android/widget/CompoundButton" to "android/widget/Button",
        "android/widget/CheckBox" to "android/widget/CompoundButton",
        "android/widget/Switch" to "android/widget/CompoundButton",
        "androidx/appcompat/widget/SwitchCompat" to "android/widget/CompoundButton",
        "com/google/android/material/switchmaterial/SwitchMaterial" to "androidx/appcompat/widget/SwitchCompat",
        "com/google/android/material/materialswitch/MaterialSwitch" to "android/widget/CompoundButton",
        "android/widget/RadioButton" to "android/widget/CompoundButton",
        "android/widget/ImageView" to "android/view/View",
        "androidx/appcompat/widget/AppCompatImageView" to "android/widget/ImageView",
        "android/widget/ImageButton" to "android/widget/ImageView",
        "android/widget/ProgressBar" to "android/view/View",
        "android/widget/Spinner" to "android/view/ViewGroup",
        "com/google/android/material/chip/Chip" to "android/widget/CheckBox",
        "com/google/android/material/chip/ChipGroup" to "android/view/ViewGroup",
    )

    /**
     * The Android app's own classes: parent class (null if none) and whether it is an interface.
     * Generated at build time from the app sources (see generateAppClassIndex in build.gradle.kts).
     */
    private val appClasses: Map<String, Pair<String?, Boolean>> by lazy {
        AndroidStubs::class.java.getResourceAsStream("/app-class-index.txt")?.bufferedReader()?.useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split(' ')
                if (parts.size != 3) null
                else parts[0] to (parts[1].takeUnless { it == "-" } to (parts[2] == "interface"))
            }.toMap()
        } ?: emptyMap()
    }

    data class Result(val jar: File, val stubbed: List<String>)

    /** Writes a jar with placeholders for what [jars] need. Returns null when nothing is missing. */
    fun generate(jars: List<File>, parent: ClassLoader, out: File): Result? {
        val nodeJar = HashMap<ClassNode, File>()
        val nodes = jars.flatMap { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".class") }.map { entry ->
                    ClassNode().also {
                        // Frames are kept: patched calls have the same stack effect, so they stay valid
                        ClassReader(zip.getInputStream(entry).readBytes()).accept(it, 0)
                        nodeJar[it] = jar
                    }
                }.toList()
            }
        }
        val own = nodes.associateBy { it.name }

        val missingCache = HashMap<String, Boolean>()
        fun isMissing(name: String): Boolean = missingCache.getOrPut(name) {
            name !in own && stubbable.any { name.startsWith(it) } && parent.getResource("$name.class") == null
        }

        val stubs = LinkedHashMap<String, Stub>()
        fun stub(name: String): Stub? {
            if (!isMissing(name)) return null
            var created = false
            val result = stubs.getOrPut(name) { created = true; Stub(name) }
            if (created) {
                // Give it its real parent, and make sure that parent exists too
                val app = appClasses[name]
                if (app?.second == true) result.isInterface = true
                (knownParents[name] ?: app?.first)?.let { p -> result.superName = p; stub(p) }
            }
            return result
        }

        fun typeRef(t: Type) {
            when (t.sort) {
                Type.OBJECT -> stub(t.internalName)
                Type.ARRAY -> typeRef(t.elementType)
                Type.METHOD -> { t.argumentTypes.forEach(::typeRef); typeRef(t.returnType) }
            }
        }

        fun descRef(desc: String?) {
            if (desc == null) return
            runCatching { typeRef(Type.getType(desc)) }
        }

        /** The nearest placeholder an extension class inherits from, for members it calls on itself. */
        fun stubAncestor(name: String): Stub? {
            var current: String? = name
            repeat(20) {
                val c = current ?: return null
                stubs[c]?.let { return it }
                if (isMissing(c)) return stub(c)
                current = own[c]?.superName ?: return null
            }
            return null
        }

        fun declares(owner: String, method: String, desc: String): Boolean {
            var current: String? = owner
            repeat(20) {
                val node = own[current] ?: return false
                if (node.methods.any { it.name == method && it.desc == desc }) return true
                current = node.superName
            }
            return false
        }

        for (node in nodes) {
            node.superName?.let { stub(it) }
            node.interfaces.forEach { stub(it)?.isInterface = true }
            node.fields.forEach { descRef(it.desc) }
            for (method in node.methods) {
                descRef(method.desc)
                method.tryCatchBlocks.forEach { it.type?.let(::stub) }
                for (insn in method.instructions) {
                    when (insn) {
                        is MethodInsnNode -> {
                            descRef(insn.desc)
                            val target = if (isMissing(insn.owner)) stub(insn.owner)
                            else if (insn.owner in own && !declares(insn.owner, insn.name, insn.desc)) stubAncestor(insn.owner)
                            else null
                            target?.let { s ->
                                if (insn.opcode == INVOKEINTERFACE || insn.itf) s.isInterface = true
                                s.methods += Member(insn.name, insn.desc, insn.opcode == INVOKESTATIC)
                            }
                        }
                        is FieldInsnNode -> {
                            descRef(insn.desc)
                            stub(insn.owner)?.fields?.add(Member(insn.name, insn.desc, insn.opcode == GETSTATIC || insn.opcode == PUTSTATIC))
                        }
                        is TypeInsnNode -> stub(Type.getObjectType(insn.desc).let { if (it.sort == Type.ARRAY) it.elementType.internalName else it.internalName })
                        is MultiANewArrayInsnNode -> descRef(insn.desc)
                        is LdcInsnNode -> (insn.cst as? Type)?.let(::typeRef)
                    }
                }
            }
        }
        val missingMembers = patchMissingMembers(nodes, own, parent, stubs)
        if (missingMembers.isNotEmpty()) {
            val patched = nodes.filter { it.name in missingMembers.patchedClasses }
            patched.groupBy { nodeJar.getValue(it) }.forEach { (jar, list) -> rewriteJar(jar, list) }
        }

        if (stubs.isEmpty() && missingMembers.isEmpty()) return null

        // Never shadow what a real parent class already provides
        for (s in stubs.values) {
            var parentName = s.superName
            while (stubs.containsKey(parentName)) parentName = stubs.getValue(parentName).superName
            if (parentName == "java/lang/Object") continue
            val real = runCatching { Class.forName(parentName.replace('/', '.'), false, parent) }.getOrNull() ?: continue
            val realMethods = generateSequence(real) { it.superclass }
                .flatMap { c -> c.declaredMethods.asSequence().map { it.name + Type.getMethodDescriptor(it) } }
                .toSet()
            s.methods.removeIf { it.name != "<init>" && (it.name + it.desc) in realMethods }
        }

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (s in stubs.values) {
                zip.putNextEntry(ZipEntry("${s.name}.class"))
                zip.write(s.bytes(stubs))
                zip.closeEntry()
            }
            if (missingMembers.isNotEmpty()) {
                zip.putNextEntry(ZipEntry("$HELPER.class"))
                zip.write(missingMembers.helperBytes(stubs))
                zip.closeEntry()
            }
        }
        return Result(out, (stubs.keys + missingMembers.names).map { it.replace('/', '.') })
    }

    private const val HELPER = "cloudstream_desktop/MissingAndroidMembers"

    /** Calls to members a real desktop stand-in lacks, redirected to static do-nothing helpers. */
    private class MissingMembers {
        val helpers = LinkedHashMap<String, String>() // helper name -> descriptor
        val patchedClasses = HashSet<String>()
        val names = LinkedHashSet<String>() // "android/content/Context.getSystemService", for the report
        fun isNotEmpty() = helpers.isNotEmpty()
        fun isEmpty() = helpers.isEmpty()

        fun helperBytes(stubs: Map<String, Stub>): ByteArray {
            val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
            cw.visit(V1_8, ACC_PUBLIC or ACC_SUPER or ACC_FINAL, HELPER, null, "java/lang/Object", null)
            for ((name, desc) in helpers) {
                cw.visitMethod(ACC_PUBLIC or ACC_STATIC, name, desc, null, null).apply {
                    visitCode()
                    returnDefault(Type.getReturnType(desc), stubs)
                    visitMaxs(0, 0)
                    visitEnd()
                }
            }
            cw.visitEnd()
            return cw.toByteArray()
        }
    }

    /** Desktop stand-ins are the Android-named classes this app ships itself (Context, Plugin, CommonActivity...). */
    private fun isStandIn(cls: Class<*>): Boolean =
        cls.protectionDomain?.codeSource?.location == AndroidStubs::class.java.protectionDomain?.codeSource?.location

    private fun patchMissingMembers(
        nodes: List<ClassNode>,
        own: Map<String, ClassNode>,
        parent: ClassLoader,
        stubs: Map<String, Stub>,
    ): MissingMembers {
        val result = MissingMembers()
        val classCache = HashMap<String, Class<*>?>()
        fun real(name: String): Class<*>? = classCache.getOrPut(name) {
            if (name in own || name in stubs || stubbable.none { name.startsWith(it) }) null
            else runCatching { Class.forName(name.replace('/', '.'), false, parent) }.getOrNull()?.takeIf(::isStandIn)
        }
        val memberCache = HashMap<String, Set<String>>()
        fun members(cls: Class<*>): Set<String> = memberCache.getOrPut(cls.name) {
            val all = HashSet<String>()
            val seen = HashSet<Class<*>>()
            fun visit(c: Class<*>?) {
                if (c == null || !seen.add(c)) return
                c.declaredMethods.forEach { all += it.name + Type.getMethodDescriptor(it) }
                c.declaredFields.forEach { all += "field:" + it.name }
                visit(c.superclass)
                c.interfaces.forEach(::visit)
            }
            visit(cls)
            all
        }

        for (node in nodes) {
            for (method in node.methods) {
                for (insn in method.instructions.toArray()) {
                    if (insn !is MethodInsnNode || insn.name == "<init>") continue
                    val cls = real(insn.owner) ?: continue
                    if ((insn.name + insn.desc) in members(cls)) continue
                    val isStatic = insn.opcode == INVOKESTATIC
                    val desc = if (isStatic) insn.desc else "(L${insn.owner};" + insn.desc.removePrefix("(")
                    val helper = (insn.owner + "_" + insn.name).replace(Regex("[^A-Za-z0-9_]"), "_") + "_" + Integer.toHexString(desc.hashCode())
                    result.helpers[helper] = desc
                    result.names += insn.owner + "." + insn.name
                    result.patchedClasses += node.name
                    method.instructions.set(insn, MethodInsnNode(INVOKESTATIC, HELPER, helper, desc, false))
                }
            }
        }
        return result
    }

    /** Replaces the given classes inside [jar], leaving everything else as it is. */
    private fun rewriteJar(jar: File, patched: List<ClassNode>) {
        val replacements = patched.associate { node ->
            // No frame recomputation needed: each patched call keeps its exact stack effect
            "${node.name}.class" to ClassWriter(0).also { node.accept(it) }.toByteArray()
        }
        val tmp = File(jar.path + ".patching")
        ZipFile(jar).use { zip ->
            ZipOutputStream(tmp.outputStream().buffered()).use { out ->
                for (entry in zip.entries()) {
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(replacements[entry.name] ?: zip.getInputStream(entry).readBytes())
                    out.closeEntry()
                }
            }
        }
        check(tmp.renameTo(jar)) { "Could not update ${jar.name}" }
    }

    private fun org.objectweb.asm.MethodVisitor.returnDefault(type: Type, stubs: Map<String, Stub>) {
        when (type.sort) {
            Type.VOID -> visitInsn(RETURN)
            Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> { visitInsn(ICONST_0); visitInsn(IRETURN) }
            Type.LONG -> { visitInsn(LCONST_0); visitInsn(LRETURN) }
            Type.FLOAT -> { visitInsn(FCONST_0); visitInsn(FRETURN) }
            Type.DOUBLE -> { visitInsn(DCONST_0); visitInsn(DRETURN) }
            else -> {
                // A placeholder object instead of null, so chained calls on it also do nothing
                val stub = if (type.sort == Type.OBJECT) stubs[type.internalName] else null
                if (stub != null && !stub.isInterface) {
                    visitTypeInsn(NEW, stub.name)
                    visitInsn(DUP)
                    visitMethodInsn(INVOKESPECIAL, stub.name, "<init>", "()V", false)
                } else {
                    visitInsn(ACONST_NULL)
                }
                visitInsn(ARETURN)
            }
        }
    }

    private data class Member(val name: String, val desc: String, val isStatic: Boolean)

    private class Stub(val name: String) {
        var superName = "java/lang/Object"
        var isInterface = false
        val methods = LinkedHashSet<Member>()
        val fields = LinkedHashSet<Member>()

        fun bytes(all: Map<String, Stub>): ByteArray {
            val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
            // Interfaces may not carry ACC_SUPER, the JVM rejects them
            val access = if (isInterface) ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT else ACC_PUBLIC or ACC_SUPER
            val parent = if (isInterface) "java/lang/Object" else superName
            cw.visit(V1_8, access, name, null, parent, null)

            for (f in fields) {
                if (isInterface && !f.isStatic) continue
                val fAccess = ACC_PUBLIC or (if (f.isStatic) ACC_STATIC else 0) or (if (isInterface) ACC_FINAL else 0)
                cw.visitField(fAccess, f.name, f.desc, null, null).visitEnd()
            }

            if (!isInterface) {
                // Always a no-arg constructor, plus any the extension calls
                val ctors = (methods.filter { it.name == "<init>" }.map { it.desc } + "()V").toSet()
                for (desc in ctors) {
                    cw.visitMethod(ACC_PUBLIC, "<init>", desc, null, null).apply {
                        visitCode()
                        visitVarInsn(ALOAD, 0)
                        visitMethodInsn(INVOKESPECIAL, parent, "<init>", "()V", false)
                        visitInsn(RETURN)
                        visitMaxs(0, 0)
                        visitEnd()
                    }
                }
            }

            for (m in methods) {
                if (m.name == "<init>" || m.name == "<clinit>") continue
                val mAccess = ACC_PUBLIC or if (m.isStatic) ACC_STATIC else 0
                cw.visitMethod(mAccess, m.name, m.desc, null, null).apply {
                    visitCode()
                    returnDefault(Type.getReturnType(m.desc), all)
                    visitMaxs(0, 0)
                    visitEnd()
                }
            }

            // Fill static fields of placeholder types (INSTANCE, Companion) with an instance
            val selfFilled = fields.filter { f ->
                f.isStatic && Type.getType(f.desc).let { t -> t.sort == Type.OBJECT && all[t.internalName]?.isInterface == false }
            }
            if (selfFilled.isNotEmpty()) {
                cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null).apply {
                    visitCode()
                    for (f in selfFilled) {
                        val type = Type.getType(f.desc).internalName
                        visitTypeInsn(NEW, type)
                        visitInsn(DUP)
                        visitMethodInsn(INVOKESPECIAL, type, "<init>", "()V", false)
                        visitFieldInsn(PUTSTATIC, name, f.name, f.desc)
                    }
                    visitInsn(RETURN)
                    visitMaxs(0, 0)
                    visitEnd()
                }
            }
            cw.visitEnd()
            return cw.toByteArray()
        }
    }
}
