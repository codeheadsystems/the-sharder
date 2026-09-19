package com.codeheadsystems.sharder.gradle

import java.io.File

/**
 * The reading of a Markdown file both documentation checks share.
 *
 * Neither check parses Markdown. Each one reads the lines, knows which of them sit inside a fenced
 * code block, and knows where a code span begins and ends, because every rule either applies to
 * prose and not to code, or resolves a reference that prose spells in a code span.
 */
object Markdown {

    private val FENCE = Regex("^\\s*(`{3,}|~{3,})")
    private val CODE_SPAN = Regex("`+[^`]*`+")
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val LINK = Regex("\\[(?:[^\\[\\]]|\\[[^\\[\\]]*\\])*\\]\\(([^()\\s]+(?:\\([^()]*\\))?)\\)")

    /** One line of a file, with its one-based number and whether it sits inside a code block. */
    data class Line(val number: Int, val text: String, val code: Boolean)

    /** The lines of a file, each marked with whether a fenced code block encloses it. */
    fun lines(file: File): List<Line> {
        val lines = mutableListOf<Line>()
        var fence: String? = null
        file.readLines().forEachIndexed { index, text ->
            val opener = FENCE.find(text)?.groupValues?.get(1)
            val inside = fence != null
            if (fence == null && opener != null) {
                fence = opener
            } else if (fence != null && opener != null && opener.startsWith(fence!![0].toString())) {
                fence = null
            }
            lines += Line(index + 1, text, inside || opener != null)
        }
        return lines
    }

    /**
     * A line with its code spans removed, which is the prose of the line.
     *
     * A code span that wraps across a line break is a span on both lines, so a caller reading a
     * whole file reads the lines this method returns for the file rather than calling it per line.
     */
    fun prose(text: String): String = CODE_SPAN.replace(text) { match ->
        match.value.map { if (it == '\n') '\n' else ' ' }.joinToString("")
    }

    /**
     * The lines of a file with the code spans removed, each still marked with whether a fenced
     * code block encloses it.
     *
     * The removal runs over the whole file, because a code span the prose wraps opens on one line
     * and closes on the next, and a line read on its own carries an unbalanced backtick that reads
     * as ordinary prose.
     */
    fun proseLines(file: File): List<Line> {
        val marked = lines(file)
        val stripped = prose(marked.joinToString("\n") { it.text }).lines()
        return marked.mapIndexed { index, line ->
            line.copy(text = stripped.getOrElse(index) { line.text })
        }
    }

    /** The headings of a file, as the anchors they define. */
    fun anchors(file: File): Set<String> {
        val anchors = mutableSetOf<String>()
        val seen = mutableMapOf<String, Int>()
        lines(file).filter { !it.code }.forEach { line ->
            val heading = HEADING.matchEntire(line.text) ?: return@forEach
            val slug = slug(heading.groupValues[2])
            val count = seen.merge(slug, 0) { previous, _ -> previous + 1 }!!
            anchors += if (count == 0) slug else "$slug-$count"
        }
        return anchors
    }

    /**
     * The anchor GitHub derives from a heading: the text, lowercased, with the formatting removed,
     * anything that is not a letter, a digit, a space, or a hyphen dropped, and the spaces turned
     * into hyphens.
     */
    fun slug(heading: String): String {
        val text = LINK.replace(heading) { it.value.substringAfter('[').substringBefore("](") }
            .replace("`", "")
            .replace("*", "")
            .replace("_", "")
        return buildString {
            text.lowercase().forEach { character ->
                when {
                    character.isLetterOrDigit() -> append(character)
                    character == ' ' || character == '-' -> append('-')
                    character == '’' -> Unit
                    else -> Unit
                }
            }
        }
    }

    /** Every inline link of a line, as the target each one names. */
    fun links(text: String): List<String> = LINK.findAll(text).map { it.groupValues[1] }.toList()

    /** Whether a heading opens the line, and the heading it opens. */
    fun heading(text: String): Pair<Int, String>? {
        val match = HEADING.matchEntire(text) ?: return null
        return match.groupValues[1].length to match.groupValues[2]
    }

    /**
     * The body of one heading, up to the next heading at the same depth or above, as
     * `conformance/generator/verify_withdrawals.py` reads it.
     */
    fun section(file: File, heading: String): List<Line> {
        val depth = heading.takeWhile { it == '#' }.length
        val lines = lines(file)
        val start = lines.indexOfFirst { !it.code && it.text.trimEnd() == heading }
        if (start < 0) {
            return emptyList()
        }
        val body = lines.drop(start + 1)
        val end = body.indexOfFirst { line ->
            !line.code && (heading(line.text)?.first ?: 99) <= depth
        }
        return if (end < 0) body else body.take(end)
    }

    /** The data rows of the tables in a section, with the header and rule rows dropped. */
    fun tableRows(body: List<Line>): List<Line> {
        val rows = mutableListOf<Line>()
        body.forEach { line ->
            val text = line.text.trim()
            if (!text.startsWith("|")) {
                return@forEach
            }
            if (text.all { it == '|' || it == '-' || it == ':' || it == ' ' }) {
                if (rows.isNotEmpty()) {
                    rows.removeAt(rows.size - 1)
                }
                return@forEach
            }
            rows += line
        }
        return rows
    }
}
