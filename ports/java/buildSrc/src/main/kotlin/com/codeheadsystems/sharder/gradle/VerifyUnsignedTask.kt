package com.codeheadsystems.sharder.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File

/**
 * The unsigned comparison check of `40-java-binding.md`.
 *
 * The specification compares hashes, tokens, and scores as unsigned 64-bit integers, and Java has
 * no unsigned long. A signed comparison of such a value produces a ring order that is wrong for
 * every operand at or above 2^63, which is half of them, and the result is a placement that is
 * internally consistent and disagrees with every other port. The vectors catch some of that; this
 * check catches the forms themselves, in the packages where a 64-bit value is a hash rather than a
 * count.
 *
 * `U64` is exempt, because it is where the sanctioned forms are written.
 */
abstract class VerifyUnsignedTask : DefaultTask() {

    @get:InputDirectory
    abstract val classesDirectory: DirectoryProperty

    /** The package roots the check reads, as internal binary name prefixes. */
    @get:Input
    abstract val packages: SetProperty<String>

    /** The classes the check does not read, as internal binary names. */
    @get:Input
    abstract val exempt: SetProperty<String>

    /**
     * The methods a signed comparison is sanctioned in, as `internal/binary/Name.method`.
     *
     * The list holds the epoch and instant comparisons named under "Epoch and instant" of
     * `40-java-binding.md` and nothing else. An epoch is bounded by 9007199254740991 and an
     * instant counts milliseconds from a monotonic base whose first reading is zero, so both fit a
     * signed `long` with room and both are compared with the relational operators. The list lives
     * here rather than in an annotation or a comment, so adding to it is a change somebody
     * reviews.
     */
    private val allowed: Set<String> = setOf(
        // No method of the two packages the check reads compares an epoch or an instant today.
    )

    @TaskAction
    fun verify() {
        val findings = mutableListOf<String>()
        val roots = packages.get()
        val exemptions = exempt.get()
        classesDirectory.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .forEach { file ->
                val name = binaryName(file)
                if (roots.none { name.startsWith(it) } || exemptions.contains(name)) {
                    return@forEach
                }
                findings += scan(file, name).filterNot { it.method in allowed }
                    .map { "${it.method} ${it.form}" }
            }
        if (findings.isNotEmpty()) {
            findings.forEach { logger.error("  $it") }
            throw GradleException(
                "${findings.size} signed comparison(s) of a 64-bit value on the placement path"
            )
        }
    }

    private fun binaryName(file: File): String =
        file.relativeTo(classesDirectory.get().asFile).path
            .removeSuffix(".class")
            .replace(File.separatorChar, '/')

    /** One signed comparison, as the method that carries it and the form it takes. */
    private data class Finding(val method: String, val form: String)

    private fun scan(file: File, name: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        val reader = ClassReader(file.readBytes())
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int, method: String, descriptor: String,
                signature: String?, exceptions: Array<out String>?
            ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {

                override fun visitInsn(opcode: Int) {
                    when (opcode) {
                        // LCMP is the signed comparison every relational operator on a long
                        // compiles to.
                        Opcodes.LCMP -> findings += Finding("$name.$method", "uses LCMP")
                        Opcodes.L2D, Opcodes.L2F ->
                            findings += Finding(
                            "$name.$method", "converts a long to a floating-point value")
                    }
                }

                override fun visitMethodInsn(
                    opcode: Int, owner: String, called: String,
                    descriptor: String, isInterface: Boolean
                ) {
                    val forbidden = when {
                        owner == "java/lang/Long" && called == "compare" -> true
                        owner == "java/lang/Long" && called == "signum" -> true
                        owner == "java/lang/Math" && called == "multiplyHigh" -> true
                        owner == "java/lang/Math" && (called == "max" || called == "min")
                                && descriptor == "(JJ)J" -> true
                        owner == "java/util/Comparator" && called == "comparingLong" -> true
                        else -> false
                    }
                    if (forbidden) {
                        findings += Finding("$name.$method", "calls $owner.$called$descriptor")
                    }
                }
            }
        }, ClassReader.SKIP_FRAMES)
        return findings
    }
}
