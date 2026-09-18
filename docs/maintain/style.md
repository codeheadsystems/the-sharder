# Documentation style

The register, the emphasis rules, and the punctuation conventions every document in this repository
is written to, together with the terminology it uses. A reference manual describes a system; it does
not argue for one.

Most of what follows is subtractive. Where a rule and a habit disagree, the rule wins and the habit
is the defect.

## Scope

Every Markdown document in the repository is written to this guide. There are no exemptions.

- `docs/design/`, including the normative specification, the glossary, and the decision records
- `docs/maintain/`, including this guide
- `docs/README.md`, the repository's `README.md`, and `CONTRIBUTING.md`
- `conformance/` and `bench/`, including the suite and generator entry points
- `DESIGN_PROMPT.md` and `CLAUDE.md`

A new directory under `docs/` joins this list in the commit that creates it.

A document addressed to a tool rather than to a reader is under the guide unchanged. Its
instructions are procedure steps, so they take the imperative that the Register section already
allows, and everything around them stays in the third person.

This guide is maintained jointly with `rule-executor`, a separate repository outside this one that
holds its own copy. The two copies differ only in the terminology table and in the parts naming this
repository, so a change to a shared rule belongs in both. Nothing a reader needs is in that
repository, and nothing in this one depends on it.

## Register

Documentation is written in the third person, in the present tense, about the library.

- The subject of a sentence is the thing being described, not the reader and not the author. Write
  "an exhausted preference list raises `NoCandidate`", not "you will get an error back".
- Second person is correct in one place: the numbered steps of a procedure, where the imperative is
  the clearest form. "Drain the node." "Reload the topology." Everything around those steps returns
  to the third person.
- Do not address the reader's expectations, assumptions, or feelings. "That is the library working,
  not failing" and "worth knowing before you start" describe a conversation rather than a system.
- Do not write in the first person, singular or plural. The documentation has no narrator.

## Emphasis

Bold marks the first occurrence of a defined term in the document that defines it. It has no other
use. Bold applied to a clause for stress is the most common defect in this corpus, and its effect
is cumulative: where a fifth of the text is emphasised, emphasis carries no information.

- No bold for stress, contrast, warning, or surprise.
- No capitalised words for stress. Capitals are for acronyms, identifiers, enum constants, HTTP
  methods, environment variables, and other things that are genuinely spelled that way.
- The RFC 2119 keywords are spelled that way. `MUST`, `MUST NOT`, `SHOULD`, `SHOULD NOT`, and `MAY`
  are capitalised in the normative specification, where they carry their RFC 2119 meaning, and
  nowhere else. A document that is not normative says "refuses" rather than "MUST refuse".
- No italics for stress. Italics mark a term quoted as a term, and little else.

Where a fact is important, give it its own sentence, its own paragraph, or its own heading. Position
carries emphasis in a reference manual; typography does not.

## Headings

A heading is an index entry and a link target. It labels the material beneath it.

- Write a noun phrase. "Preference list construction", not "why the preference list is ordered by
  failure domain and not by hash".
- No commas, no conjunctions joining two clauses, no question forms, no verbs of judgement
  ("deliberately", "worth", "why").
- Eight words is the practical ceiling.
- Headings are link anchors. Renaming one is an interface change, and a rename that breaks a
  cross-reference fails the build in the commit that makes it.

## Justification

State what the library does. Explain a decision only where a reader who does not know it would draw
a wrong conclusion, and then explain it plainly, in its own sentence.

- Do not defend a design against an imagined objection. "That is deliberate rather than unfinished",
  "rather than an oversight", and "this is the design working" all answer a criticism nobody reading
  a manual has made.
- Do not certify a claim's provenance in passing. "Measured, not reasoned about", "read out of the
  source", and "and none of it is hedged" are assurances about the author's diligence. Where
  provenance genuinely matters, such as a benchmark's conditions, it is content: give it a sentence
  that says what was measured, on what, and when.
- Do not write about the document. A document does not explain why it exists, why it is separate
  from another document, how many times it has been corrected, or what it is not. Routing belongs in
  the directory's entry point.

A decision record under `docs/design/adr/` is the one place justification is the content rather than
a defect. Its Context, Consequences, and Alternatives sections say why a decision was taken and what
was rejected, because recording that is the document's purpose. Every other rule in this guide
applies to it unchanged: third person, no bold for stress, noun-phrase headings. The carve-out
covers what an ADR is allowed to discuss, not how it is allowed to sound.

## Computed figures

A figure that something in this repository computes is stated where it is computed, and is not
transcribed into prose. A document that needs one names the artefact and the field that holds it.

- Requirement, coverage, vector, case, topology, property, and scenario counts are computed into
  [`../../conformance/coverage.json`](../../conformance/coverage.json) and
  [`../../conformance/manifest.json`](../../conformance/manifest.json) by the generator.
- The number of decision records is the number of files under `docs/design/adr/`.
- A count of anything a reader can enumerate from the tree is read from the tree.

Where a document needs to say what a set contains, it names the identifiers, which are permanent,
rather than counting them. A figure quoted as history inside a decision record is exempt, because it
records what was true on the date the record carries and does not move afterwards.

## Punctuation and mechanics

- No em dashes and no spaced double hyphens. Use a comma, a semicolon, a colon, parentheses, or two
  sentences.
- Use a serial comma.
- British spelling in prose: `behaviour`, `serialise`, `normalise`, `initialise`. Technical terms
  keep the spelling of the thing they name: an identifier is quoted exactly as the source spells it,
  so a field named `normalizedWeight` stays `normalizedWeight`.
- Code formatting for anything a machine reads: identifiers, file paths, property keys, environment
  variables, requirement identifiers, literal values, wire field names.
- Wrap prose at 100 columns.
- Tables take a header row that names the columns. A table is for material with a repeating shape;
  prose with pipes in it is not a table.

## Terminology

| Use | Instead of |
|---|---|
| the sharder library, on first mention in a document; the library thereafter | this library, the framework, the system, the product, the tool |
| `sharder`, when naming the library, the Gradle project, or a published artifact | |
| `the-sharder`, only when naming the GitHub repository | |
| a caller, an integrator, an operator | you, the user, the developer |
| refuses, returns, answers with | will refuse, is going to return |
| a node | a box, an instance, a server |
| a topology | a cluster map, a config, the ring |

A caller is the application that embeds the library. A client is the thing that talks to a node, and
belongs to the caller's world rather than the library's; the two are not synonyms, and a document
that means the embedder says caller.

A term defined in [`../design/05-glossary.md`](../design/05-glossary.md) is used as that document
defines it, everywhere, and is not redefined in passing. A term that appears in the normative
specification carries the specification's meaning in every other document.

## Enforcement

Two checks run inside `check`, and they carry different authority.

| Check | What it proves | On a finding |
|---|---|---|
| `verifyDocLinks` | every cross-reference resolves, anchors included | fails `check` |
| `verifyDocStyle` | the mechanical rules above: one dash convention, no capitalised stress outside the RFC 2119 vocabulary, a ceiling on bold, and headings that label rather than argue | reports a warning and leaves `check` green |

A broken cross-reference is unambiguous: the target is absent, or the anchor does not exist, and no
reading of the guide makes it correct. `verifyDocLinks` therefore fails the build that introduces
one.

A style finding is a judgement about a sentence expressed as a regular expression, and a false
positive there costs a contributor a rewrite the guide does not ask for. `verifyDocStyle` therefore
prints every finding with its file, its line, and the rule it names, and does not fail `check`. The
rules it covers bind a document whether or not the check runs, and the reviewer is what enforces
them. The decision and its cost are recorded in
[`adr/0062`](../design/adr/0062-documentation-style-check-as-a-warning.md).

`verifyDocStyle` refuses four things and no more. It does not judge register, justification, or
terminology, because whether a sentence describes the design or argues for it is not a property a
regular expression can see, and a check that guessed would be wrong often enough to be worked
around. Those stay with the reviewer, and the checks exist so that a reviewer's attention goes to
them rather than to counting asterisks.

Neither check has a per-line suppression. The acronym vocabulary `verifyDocStyle` reads is a list in
`buildSrc`, so teaching it a new one is a change somebody reviews.

Neither check exists. Both land with the first Java implementation, and until then every rule in
this guide is enforced at review alone.
