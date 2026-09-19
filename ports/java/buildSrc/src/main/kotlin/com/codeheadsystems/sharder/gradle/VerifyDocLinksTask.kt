package com.codeheadsystems.sharder.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * The cross-reference check of `40-java-binding.md`.
 *
 * A heading is an anchor, a requirement identifier is a join key, and a withdrawn identifier is
 * permanent. None of the three survives a rename or a renumbering on its own, and none of the three
 * fails visibly: a broken anchor renders as a link, a requirement identifier the specification
 * never states reads exactly like one it does, and a withdrawn identifier restated three thousand
 * lines from its register row looks like an ordinary addition. Every finding fails the build, and
 * every finding is reported rather than the first.
 */
abstract class VerifyDocLinksTask : DefaultTask() {

    /**
     * The repository, which is the tree the documents belong to.
     *
     * <p>The root is not an input: it holds the build output as well as the documents, and a task
     * that declared it whole would declare a dependency on everything the build writes. The
     * documents themselves are the input.
     */
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    /** The Markdown files the check reads, which are what it is up to date against. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val documents: ConfigurableFileCollection

    private val identifier = Regex("`([A-Z]{2,})-(\\d{2,3})`")
    private val stated = Regex("^`([A-Z]+-\\d{3})`\\.")
    private val statedQuestion = Regex("^### (OQ-\\d{2})\\.")
    private val registerRow = Regex("^\\|\\s*`([A-Z]+-\\d{3})`\\s*\\|\\s*`(\\d{4})`\\s*\\|")
    private val questionRow = Regex("^\\|\\s*`(OQ-\\d{2})`\\s*\\|[^|]*\\|\\s*\\[`adr/(\\d{4})`\\]")
    private val prefixRow = Regex("^\\|\\s*`([A-Z]+)`\\s*\\|")
    private val recordReference = Regex("adr/(\\d{4})")

    @TaskAction
    fun verify() {
        val root = repositoryRoot.get().asFile
        val findings = mutableListOf<String>()
        val specification = root.resolve("docs/design/10-specification.md")
        val questions = root.resolve("docs/design/90-open-questions.md")

        val requirements = definitions(specification, stated)
        val openQuestions = definitions(questions, statedQuestion)
        val prefixes = Markdown.tableRows(Markdown.section(specification, "### Requirement prefixes"))
            .mapNotNull { prefixRow.find(it.text.trim())?.groupValues?.get(1) }
            .toSet()
        val records = records(root)

        val withdrawn = mutableMapOf<String, String>()
        val registerLines = mutableMapOf<File, Set<Int>>()
        listOf(
            Triple(specification, "### Withdrawn identifiers", registerRow),
            Triple(questions, "## Withdrawn questions", questionRow),
        ).forEach { (file, heading, row) ->
            val body = Markdown.section(file, heading)
            registerLines[file] = body.map { it.number }.toSet()
            Markdown.tableRows(body).forEach { line ->
                val match = row.find(line.text.trim())
                if (match == null) {
                    findings += "${relative(root, file)}:${line.number}: a register row parses as" +
                        " no register row, so it withdraws nothing"
                } else {
                    withdrawn[match.groupValues[1]] = match.groupValues[2]
                    if (!records.containsKey(match.groupValues[2])) {
                        findings += "${relative(root, file)}:${line.number}: the register names" +
                            " adr/${match.groupValues[2]}, which exists under no file"
                    }
                }
            }
        }

        val defined = requirements.keys + openQuestions.keys
        (withdrawn.keys intersect defined).sorted().forEach {
            findings += "$it is in the register and is stated as a live identifier"
        }
        val withdrawnWhole = withdrawn.keys.map { it.substringBefore('-') }.toSet() - prefixes
        requirements.keys.sorted().forEach { name ->
            val prefix = name.substringBefore('-')
            if (prefix !in prefixes) {
                findings += "$name is stated under $prefix, which " + (
                    if (prefix in withdrawnWhole) "the register withdraws whole"
                    else "the Requirement prefixes table does not name"
                    )
            }
        }
        (prefixes - requirements.keys.map { it.substringBefore('-') }.toSet()).sorted().forEach {
            findings += "the Requirement prefixes table names $it, which states no live requirement"
        }

        documents(root).forEach { file ->
            val decisionRecord = file.parentFile.name == "adr"
            val register = registerLines[file].orEmpty()
            Markdown.lines(file).filter { !it.code }.forEach { line ->
                val where = "${relative(root, file)}:${line.number}"
                Markdown.links(line.text).forEach { target ->
                    link(root, file, target)?.let { findings += "$where: $it" }
                }
                recordReference.findAll(line.text).forEach { match ->
                    if (!records.containsKey(match.groupValues[1])) {
                        findings += "$where: adr/${match.groupValues[1]} names no decision record"
                    }
                }
                if (decisionRecord || line.number in register) {
                    return@forEach
                }
                identifier.findAll(line.text).forEach { match ->
                    val name = match.groupValues[1] + "-" + match.groupValues[2]
                    when {
                        name in withdrawn ->
                            findings += "$where: $name is withdrawn, and adr/${withdrawn[name]}" +
                                " resolves it"
                        name in defined -> Unit
                        match.groupValues[1] == "OQ" ->
                            findings += "$where: $name names no open question"
                        match.groupValues[1] in prefixes ->
                            findings += "$where: $name names no requirement the specification states"
                    }
                }
            }
        }

        val reported = findings.distinct().sorted()
        if (reported.isNotEmpty()) {
            reported.forEach { logger.error("  $it") }
            throw GradleException("${reported.size} unresolved cross-reference(s)")
        }
        logger.lifecycle(
            "verifyDocLinks: ${documents(root).size} documents, ${requirements.size} requirements," +
                " ${withdrawn.size} withdrawn"
        )
    }

    /** The identifiers a document defines, each with the line that defines it. */
    private fun definitions(file: File, pattern: Regex): Map<String, Int> =
        Markdown.lines(file).filter { !it.code }
            .mapNotNull { line -> pattern.find(line.text)?.let { it.groupValues[1] to line.number } }
            .toMap()

    /** The decision records, by the number each file name opens with. */
    private fun records(root: File): Map<String, File> =
        root.resolve("docs/design/adr").listFiles().orEmpty()
            .filter { it.extension == "md" && it.name.length > 4 }
            .associateBy { it.name.take(4) }

    /** Every Markdown file the check reads. */
    private fun documents(root: File): List<File> {
        val trees = listOf(root.resolve("docs"), root.resolve("ports"))
        return (trees.flatMap { tree ->
            tree.walkTopDown()
                .onEnter { it.name != "build" && it.name != ".gradle" && it.name != "node_modules" }
                .filter { it.isFile && it.extension == "md" }
                .toList()
        } + root.resolve("README.md")).filter { it.isFile }.sorted()
    }

    /** The finding a link carries, or null where it resolves. */
    private fun link(root: File, file: File, target: String): String? {
        if (target.startsWith("http://") || target.startsWith("https://") ||
            target.startsWith("mailto:")
        ) {
            return null
        }
        val path = target.substringBefore('#')
        val anchor = target.substringAfter('#', "")
        val resolved = if (path.isEmpty()) file else file.parentFile.resolve(path).canonicalFile
        if (!resolved.exists()) {
            return "$target resolves to no file"
        }
        if (anchor.isEmpty() || resolved.extension != "md") {
            return null
        }
        return if (anchor in Markdown.anchors(resolved)) null
        else "$target names no heading of ${relative(root, resolved)}"
    }

    private fun relative(root: File, file: File): String =
        file.canonicalFile.relativeToOrSelf(root.canonicalFile).path
}
