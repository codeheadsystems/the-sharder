package com.codeheadsystems.sharder.gradle

/**
 * The vocabularies `verifyDocStyle` reads, and the two ceilings it counts against.
 *
 * Each list is a list here rather than a per-line suppression, under `40-java-binding.md`, so
 * teaching the check a new entry is a change somebody reviews.
 */
object Vocabulary {

    /** A word of two or more capitals the check reads as a name rather than as stress. */
    val ACRONYMS: Set<String> = setOf(
        "ADR", "AMD", "API", "ASCII", "ASM", "BLAKE3", "BOM", "CBOR", "CPU", "CRC16", "CRUSH",
        "DNS", "DSL", "FNV", "HTTP", "IDE", "IEEE", "JDK", "JIT", "JMH", "JPMS", "JSON", "JVM",
        "KB", "MB", "OQ", "POM", "RFC", "SHA", "SPI", "UTF", "XXH3", "YAML",
        // The documents that name themselves.
        "CONTRIBUTING", "README", "MEMORY",
    )

    /** The RFC 2119 keywords, which are stress only outside the specification. */
    val RFC2119: Set<String> = setOf(
        "MUST", "NOT", "REQUIRED", "SHALL", "SHOULD", "RECOMMENDED", "MAY", "OPTIONAL",
    )

    /** The one document the RFC 2119 capitals belong to, under `docs/maintain/style.md`. */
    const val NORMATIVE_DOCUMENT: String = "docs/design/10-specification.md"

    /** A verb of judgement, which a noun-phrase heading carries none of. */
    val JUDGEMENT_VERBS: Set<String> = setOf(
        "argues", "avoid", "avoids", "bad", "beats", "best", "better", "consider", "considers",
        "fails", "fix", "fixes", "good", "improve", "improves", "justifies", "justify", "matters",
        "must", "prefer", "prefers", "proves", "recommend", "recommends", "right", "should",
        "why", "wins", "worse", "worst", "wrong",
    )

    /** A conjunction that joins clauses, which a noun phrase does not do. */
    val CLAUSE_CONJUNCTIONS: Set<String> = setOf(
        "although", "because", "but", "however", "if", "since", "so", "therefore", "though",
        "unless", "whereas", "yet",
    )

    /** The most bold spans a document carries, under the first-occurrence rule of the style guide. */
    const val BOLD_CEILING: Int = 20

    /** The most words one bold span carries, a defined term being a short noun phrase. */
    const val BOLD_WORD_CEILING: Int = 4

    /** The most words a heading carries, under `docs/maintain/style.md`. */
    const val HEADING_WORD_CEILING: Int = 8

    /**
     * The documents the bold ceiling does not count, each one a list of defined terms whose every
     * entry opens with the term it defines.
     */
    val BOLD_EXEMPT: Set<String> = setOf("docs/design/05-glossary.md")
}
