package com.codeheadsystems.sharder.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * The style check of `40-java-binding.md`, which reports and does not fail.
 *
 * Three of its four rules are judgements rather than facts about the tree, so a finding is a
 * worklist entry for a reviewer rather than a verdict on a document, and the task exits zero under
 * `docs/design/adr/0062-documentation-style-check-as-a-warning.md`. It reports four things and no
 * more: the dash convention, capitalised stress, the bold ceiling, and an argumentative heading.
 */
abstract class VerifyDocStyleTask : DefaultTask() {

    /** The repository, which the findings are reported relative to and which is not an input. */
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    /** The Markdown files the check reads. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val documents: ConfigurableFileCollection

    private val bold = Regex("\\*\\*([^*]+)\\*\\*")
    private val capitals = Regex("\\b[A-Z][A-Z0-9]+\\b")
    private val enDash = Regex("(?<=\\s)\u2013(?=\\s)|(?<=[a-z])\u2013(?=[a-z])")
    private val doubleHyphen = Regex("(?<=\\s)--(?=\\s)")

    @TaskAction
    fun verify() {
        val root = repositoryRoot.get().asFile
        val findings = mutableListOf<String>()
        documents(root).forEach { file ->
            val name = file.canonicalFile.relativeToOrSelf(root.canonicalFile).path
            var boldSpans = 0
            val proseByLine = Markdown.proseLines(file).associate { it.number to it.text }
            Markdown.lines(file).filter { !it.code }.forEach { line ->
                val where = "$name:${line.number}"
                val prose = proseByLine.getValue(line.number)
                if (prose.contains('\u2014')) {
                    findings += "$where: dash convention: an em dash"
                }
                if (enDash.containsMatchIn(prose)) {
                    findings += "$where: dash convention: an en dash used as a dash"
                }
                if (doubleHyphen.containsMatchIn(prose)) {
                    findings += "$where: dash convention: a spaced double hyphen"
                }
                capitals.findAll(prose).forEach { match ->
                    val word = match.value
                    val allowed = word in Vocabulary.ACRONYMS ||
                        (word in Vocabulary.RFC2119 && name == Vocabulary.NORMATIVE_DOCUMENT)
                    if (!allowed) {
                        findings += "$where: capitalised stress: $word"
                    }
                }
                bold.findAll(line.text).forEach { match ->
                    boldSpans++
                    val words = match.groupValues[1].trim().split(Regex("\\s+")).size
                    if (words > Vocabulary.BOLD_WORD_CEILING) {
                        findings += "$where: bold ceiling: a bold span of $words words, above" +
                            " ${Vocabulary.BOLD_WORD_CEILING}"
                    }
                }
                Markdown.heading(line.text)?.let { (_, text) -> findings += heading(where, text) }
                // The bold ceiling and the heading rules read the line itself, because a bold span
                // and a heading are prose.
            }
            if (name !in Vocabulary.BOLD_EXEMPT && boldSpans > Vocabulary.BOLD_CEILING) {
                findings += "$name: bold ceiling: $boldSpans bold spans, above" +
                    " ${Vocabulary.BOLD_CEILING}"
            }
        }
        findings.forEach { logger.lifecycle("  $it") }
        logger.lifecycle("verifyDocStyle: ${findings.size} finding(s), none of which fails the build")
    }

    /** The findings one heading carries. */
    private fun heading(where: String, text: String): List<String> {
        val findings = mutableListOf<String>()
        val prose = Markdown.prose(text)
        if (prose.contains(',')) {
            findings += "$where: argumentative heading: a comma"
        }
        if (prose.contains('?')) {
            findings += "$where: argumentative heading: a question mark"
        }
        val words = prose.split(Regex("[^A-Za-z0-9'-]+")).filter { it.isNotEmpty() }
        if (words.size > Vocabulary.HEADING_WORD_CEILING) {
            findings += "$where: argumentative heading: ${words.size} words, above" +
                " ${Vocabulary.HEADING_WORD_CEILING}"
        }
        words.map { it.lowercase() }.forEach { word ->
            if (word in Vocabulary.CLAUSE_CONJUNCTIONS) {
                findings += "$where: argumentative heading: the conjunction $word"
            }
            if (word in Vocabulary.JUDGEMENT_VERBS) {
                findings += "$where: argumentative heading: the verb of judgement $word"
            }
        }
        return findings
    }

    /** Every Markdown file the check reads, which is the tree `verifyDocLinks` reads. */
    private fun documents(root: File): List<File> {
        val trees = listOf(root.resolve("docs"), root.resolve("ports"))
        return (trees.flatMap { tree ->
            tree.walkTopDown()
                .onEnter { it.name != "build" && it.name != ".gradle" && it.name != "node_modules" }
                .filter { it.isFile && it.extension == "md" }
                .toList()
        } + root.resolve("README.md") + root.resolve("CONTRIBUTING.md"))
            .filter { it.isFile }.sorted()
    }
}
