# 0040. Cryptographic primitive sourcing policy

Status: accepted. Date: 2026-09-16.

## Context

The library computes two hashes. Stage 4 of `TOPO-001` takes SHA-256 over the RFC 8785 canonical
form of a topology document to produce the digest of [`0008`](0008-json-canonical-serialisation.md).
Every placement decision takes SipHash-2-4 over a framed, domain-tagged input under
`HASH-001` and `HASH-030`.

A hand-written cryptographic primitive fails in a particular way. It produces an answer that looks
like the right shape, passes the author's own unit tests, and is wrong in a way that surfaces as a
security property nobody checked rather than as a crash. That is why a project takes primitives from
a library that many people have audited, and why the rule needs to be written down once rather than
settled privately by whoever writes the next one.

Three requirements make SipHash-2-4's role in this design a security role, and a record that
pretended otherwise would not survive a reader of the `SEC-*` group. `SEC-003` names a seed the
adversary cannot read as the library's only defence against a crafted key. `SEC-010` requires
`hash.seed` to be treated as a secret and forbids exposing it through an accessor, an event, a
condition detail, or an explain record. `SEC-014` forbids a comparison that reads the seed from
short-circuiting on its octets, which is a prohibition on a timing oracle. `SEC-015` refuses an
unkeyed substitute whatever the seed's value.

Two properties separate SipHash-2-4 from the primitives the rule exists for. The first is its shape.
The algorithm is around seventy lines of addition, exclusive-or, and rotation, with no table
lookups, no key schedule, no modular arithmetic beyond the natural wrap of a 64-bit addition, and no
data-dependent branching, so its compression function is constant-time by construction and `SEC-014`
cannot be violated by accident inside it. `SEC-014`'s subject is a comparison that reads the seed,
and such a comparison sits outside the compression function wherever the function itself comes from.
The second is its acceptance test. `HASH-003` fixes a 64-row reference table, one row per message
length from 0 to 63 octets, so a transcription defect fails at a named row within milliseconds
rather than producing a plausible wrong ordering that only a cross-language vector would catch. The
primitives the rule protects against, block cipher modes, signature padding, and elliptic curve
arithmetic, have neither property.

Three independent implementations are available to check a fourth against: the reference table
published with the algorithm, the `SIPHASH` algorithm of OpenSSL 3, which
`conformance/generator/verify_siphash.py` already runs over the same 64 inputs before any vector is
generated, and Bouncy Castle's `org.bouncycastle.crypto.macs.SipHash`. Bouncy Castle 1.85, the
reference C implementation, a straightforward Java implementation, and a tuned Java implementation
all answer `a129ca6149be45e5` for the 15-octet message of that table, which is the table's row for
that message read as `HASH-002` requires.

The cost of the alternative was measured rather than assumed. On OpenJDK 25 on an AMD Ryzen 7 7840U,
over the 67-octet `rvScore` frame of `HASH-030`, four thousand routing calls after warm-up at a
summed virtual node count of 8000, Bouncy Castle's `SipHash` with one reused instance per thread
costs 57 nanoseconds per hash evaluation against 29 for a tuned hand-written implementation, and
allocates 256 kilobytes per routing call if an instance is constructed per evaluation instead.

## Decision

No cryptographic algorithm is hand-written in this project. Where the library needs one, a vetted
library provides it. SHA-256 comes from `MessageDigest.getInstance("SHA-256")` in `java.base`, which
is where it has always come from, and the equivalent standard facility serves in every other port.

SipHash-2-4, as this design uses it, is a keyed pseudorandom function for load distribution rather
than a cryptographic primitive protecting a secret, and is outside that rule. `SipHash24` in
`core.internal.hash` stays hand-written and tuned, on the routing path, and every other port carries
its own copy under `HASH-003`.

Bouncy Castle enters at test scope only, at a version pinned in `gradle/libs.versions.toml`, as a
third acceptance oracle, joining the reference table of `HASH-003` and OpenSSL's `SIPHASH`.
`org.bouncycastle.crypto.macs.SipHash` checks `SipHash24` inside `check`, beside the table, and
OpenSSL keeps its place on the generator side under `conformance/generator/verify_siphash.py`. A
disagreement between any two of them fails the build that ran them, before any conformance vector is
evaluated. It reaches no consumer, appears in no published module descriptor and in no published
POM, and adds no `requires` to a module a consumer reads.
[`0032`](0032-dependency-free-json-and-canonicalisation.md) already permits this without a policy
change: test and build dependencies are unconstrained by that policy, because they reach no
consumer.

The exception is for this primitive and does not generalise. A future primitive argues its own case
in its own record, against the rule above rather than against this exception, and the argument it
has to make is the one this record makes: the shape of the algorithm, the strength of its acceptance
test, and the independent implementations available to check it against.

## Consequences

The runtime dependency policy is unchanged. Nothing new reaches a consumer's artifact, no version
appears for an integrator to reconcile, and no transitive advisory arrives on the library's release
schedule rather than the integrator's. The near-zero dependency policy that
[`../99-roadmap.md`](../99-roadmap.md#java-binding) lists as a one-way door has not been walked
through.

No Bouncy Castle version is pinned into the runtime, so the failure where a library upgrade silently
moves every key in every deployment cannot arise from this decision. Test scope still pins a
version, because an oracle whose answer depends on what the build resolved is not an oracle.

The unsigned integer discipline of [`0030`](0030-unsigned-integer-discipline.md) keeps its reach.
`verifyUnsignedComparisons` scans the compiled classes of `core.internal.hash`, and the hash is
still compiled there, so the discipline at the primitive stays verified rather than becoming
trusted.

`HASH-003` becomes a stronger requirement than it was. It already required the reference table, and
the Java binding now runs the table, an independent C implementation through the generator, and a
third implementation from a library nobody in this repository wrote, before any conformance vector
is evaluated.

The classification is a judgement, and a judgement has to be made again for the next primitive. That
is the cost of not taking the rule strictly, and it is why the rule and the exception are recorded
together: the next reader sees what the exception rests on and can see whether their primitive rests
on the same things.

The routing path keeps 29 nanoseconds per hash evaluation rather than 57. What that is worth depends
on the strategy, and [`0039`](0039-placement-cost-model-and-warning-thresholds.md) is the record
that says so: under `ring`, `directory`, and explicit `slot` and `range` assignment the difference
is one hash per routing call and invisible, and under `rendezvous` at a large summed virtual node
count the call is already over budget by two orders of magnitude for reasons a faster hash does not
repair.

## Alternatives

Bouncy Castle on the routing path, which is the strict reading of the rule. Its merit is real: one
rule with no judgement calls, nothing to re-litigate per primitive, and no dependence on a reviewer
agreeing with a classification years later. Rejected on four measured or counted costs. It is
roughly twice the cost per hash evaluation, 57 nanoseconds against 29, which is a larger penalty
than the gap between SipHash-2-4 and the fastest alternative hash function that
[`0001`](0001-hash-function-and-key-encoding.md) rejects. The `bcprov-jdk18on` artifact is 10.3
megabytes on disk and 7611 class files, of which the library would use one class, and it lands in
every consumer's artifact and module graph. It puts a pinned version on the routing path, where an
upgrade that changed behaviour would move every key in every deployment, so the compensating control
would have to be `HASH-003` running against the pinned version in `check`. And it moves the
primitive out of the reach of `verifyUnsignedComparisons`, which turns the guarantee of
[`0030`](0030-unsigned-integer-discipline.md) at the primitive from verified into trusted.

Guava's `Hashing.sipHash24()` on the routing path. Rejected for the reason
[`0032`](0032-dependency-free-json-and-canonicalisation.md) gives for Jackson: Guava is present in
most Java deployments at some version, and a routing library that pins one participates in every
conflict that version has.

Replacing SipHash-2-4 with a primitive the JDK already carries, so that the strict rule costs no
dependency. That is SHA-256 truncated to 64 bits, which
[`0001`](0001-hash-function-and-key-encoding.md) rejects as an order of magnitude slower than needed
for a per-request call, and changing the placement hash is the first one-way door
[`../99-roadmap.md`](../99-roadmap.md#one-way-doors) names.

A library implementation on the routing path with the hand-written one kept as a fallback, selected
at runtime. Rejected because two implementations of a one-way-door function that a caller can select
between is the divergence the whole hash specification exists to prevent, and because the selection
would have to be observable to be debuggable, which puts it in a document or a configuration
setting.

No rule at all, with the question settled per primitive at review. Rejected because the question was
in fact settled per primitive at review until now, and this record exists because that produced no
statement a reviewer could hold a change against.
