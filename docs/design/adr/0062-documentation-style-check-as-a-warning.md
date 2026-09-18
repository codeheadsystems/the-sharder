# 0062. Documentation style check as a warning

Status: accepted. Date: 2026-09-17.

## Context

[`../../maintain/style.md`](../../maintain/style.md) names two checks that
[`../40-java-binding.md`](../40-java-binding.md#documentation-checks) places inside `check`.
`verifyDocLinks` resolves every cross-reference and every heading anchor. `verifyDocStyle` refuses
four mechanical things: a dash outside the one sanctioned convention, a capitalised word outside the
acronym list and the RFC 2119 vocabulary, a bold count or a bold span above a ceiling, and a heading
that argues rather than labels.

The guide's standing argument was that a mechanical rule is worth enforcing mechanically, and that
a reviewer who is not counting asterisks has attention left for register and justification. That
argument holds for what the check gets right. It says nothing about what the check gets wrong.

The four refusals are not equally decidable. The dash convention is a character class and is exact.
The other three are not. The acronym rule reads a vocabulary list, so every acronym the list has not
been taught is a finding against a document that is correct. The bold ceiling is a count over a
file, so a glossary, a terminology table, or any document that defines many terms crosses it while
obeying the rule the ceiling exists to enforce; [`../05-glossary.md`](../05-glossary.md) defines a
term per entry and is the clearest case. The heading rule reads a judgement-verb list, so a heading
naming a subject that happens to contain one of those words is refused for its subject matter.

A failing gate on a judgement of that kind has one predictable outcome. A contributor with a correct
document and a red build either rewrites a sentence the guide does not ask them to rewrite, or adds
an entry to a vocabulary list to make the build pass, which is how a vocabulary list stops meaning
anything. Neither is the behaviour the guide wants.

The two checks also differ in what a finding means. An unresolved anchor is a fact about the tree
and stays wrong however the sentence around it is written. A capitalised word is evidence about a
sentence, and the sentence may be right.

## Decision

`verifyDocLinks` stays a hard gate. A finding fails `check` in the commit that introduces it.

`verifyDocStyle` becomes a reported warning. It runs in `check`, prints every finding with its file,
its line, and the rule it names, and exits zero. `check` stays green on a style finding alone.

The rules the check covers bind a document whether or not the check runs. The reviewer enforces
them, reading the check's output as a worklist rather than as a verdict.

Neither check gains a per-line suppression. A suppression comment is a second vocabulary for saying
"this one is fine", and the warning already says it at no cost to anybody.

## Consequences

A contributor is never blocked by a judgement a regular expression made about a sentence. The cost
of a false positive falls to reading one line of build output.

A style defect can now reach the default branch. The check still names it, in the output of every
build that runs after it, so it is found rather than hidden, and the review that admitted it is
where the miss happened.

The bold ceiling stops being a reason to restructure a document. A glossary defines terms in bold
because the guide's emphasis rule says a defined term's first use is bold, and it now does so
without arguing with a counter.

The acronym list stops being load-bearing. An acronym missing from it produces a warning a reviewer
dismisses, rather than a build failure a contributor routes around by editing the list, so the list
is teachable at the pace somebody actually reviews it.

The two checks now have to be described separately wherever they are described together. The guide's
Enforcement section, the Java binding's build section, and `CONTRIBUTING.md` each say which one
fails a build.

## Alternatives

Both checks as hard gates, which is the position this record changes. Rejected for the reason above:
three of `verifyDocStyle`'s four refusals are judgements, and a judgement that fails a build is
worked around rather than obeyed.

Splitting `verifyDocStyle` in two, gating the dash convention and warning on the other three.
Rejected because it buys one exact rule at the price of two tasks, two names, and a boundary that
has to be re-argued every time a refusal is added. The dash convention is the rule a reviewer
catches most reliably by eye, so gating it is worth the least.

Both checks as warnings. Rejected because a broken cross-reference has no honest reading and costs a
reader the document they were sent to. Nothing is gained by letting one through.

A per-line suppression on `verifyDocStyle`, keeping it a gate. Rejected because the suppression
comment becomes the thing under review instead of the sentence, and because a suppression that any
contributor may add is a gate that fails only the contributors who do not know about it.
