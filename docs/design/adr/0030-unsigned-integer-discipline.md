# 0030. Unsigned integer discipline

Status: accepted. Date: 2026-09-16.

## Context

ADR 0001 fixes SipHash-2-4 with a 64-bit output, and the hash specification makes every token,
score, and hash value a u64 compared as unsigned. `RING-010` orders ring entries by token value
ascending as unsigned 64-bit integers. `RV-003` takes the largest of a node's scores, compared as
unsigned. `SLOT-001` reduces the key hash modulo the slot count as an unsigned remainder.

Java has no unsigned long. A `long` holds the bit pattern correctly and every operator that reads it
reads it as signed. The consequence is precise and unpleasant: for the half of all 64-bit values
whose top bit is set, a signed comparison inverts. A ring built with `Comparator.comparingLong` puts
every token at or above 2^63 below every token below it, which is a complete, self-consistent,
wrong ring. Every key routes somewhere, every node gets a share of the keyspace, balance looks
correct, minimal movement looks correct, and the port disagrees with every other port.

The failure has no natural test. A unit test written by the author of the defect asserts the
author's own ordering. A property test for balance passes, because the inverted ring is still a
partition of the hash space. A determinism test within one port passes, because the port is
deterministic. Only a cross-language vector catches it, and only if the vector happens to contain a
token above 2^63.

The idioms that produce the defect are the idioms a Java author reaches for first:
`Comparator.comparingLong` over a token accessor, `Math.max` over two rendezvous scores, a
relational operator for the ring search, and `Long.MAX_VALUE` as a sentinel for a minimum search.

Not every 64-bit quantity in the specification is unsigned in practice. `CORE-001` declares `Epoch`
as a u64 and then bounds it by 9007199254740991, and `Instant` counts milliseconds from a monotonic
source. Both fit a signed `long` with centuries to spare, and forcing unsigned comparisons on them
would add noise that hides the cases that matter.

## Decision

Every u64 is a `long` carrying the bit pattern, and never a `BigInteger`, a `double`, or a pair of
`int`s.

The sanctioned operations are `Long.compareUnsigned`, `Long.divideUnsigned`,
`Long.remainderUnsigned`, `Long.toUnsignedString`, `Long.parseUnsignedLong`, and
`HexFormat.toHexDigits`. They are written in exactly one place, a final class `U64` in
`core.internal.hash` that also carries the comparators the strategies use.

The forbidden forms are named explicitly: the relational operators, `Long.compare`, `Math.max`,
`Math.min`, `Long.signum`, `Comparator.comparingLong`, `Comparator.naturalOrder`, `Long.MAX_VALUE`
and `Long.MIN_VALUE` as bounds, and any conversion to a floating-point type.

The rule is enforced mechanically rather than by review. `verifyUnsignedComparisons` in `buildSrc`
reads the compiled classes of `core.internal.hash` and `core.internal.placement` and fails on the
`LCMP` opcode, on an invocation of any forbidden method, and on a `long` to `double` conversion.
`U64` is exempt. The allowlist of permitted call sites is a list in `buildSrc` with no per-line
suppression, so widening it is a change somebody reviews.

Epoch and instant are the stated exception and are compared with the relational operators. They are
identified by type and name at their declaration sites, and the allowlist holds those sites and
nothing else.

A conformance vector pins a topology whose derived tokens straddle 2^63, so the defect fails a
vector rather than passing every test the port wrote for itself.

Two products that overflow their natural width are computed wider. `weight * perWeightUnit` under
`PLACE-051` reaches 4096000000 and is formed in `long`. The four threshold comparisons that multiply
two counts, `HEALTH-034`, `FAIL-031`, `OBS-031`, and `SPLIT-041`, are evaluated through one helper
that compares `a * b` against `c * d` in 128 bits using `Math.multiplyHigh`.

## Consequences

The rule is enforceable, which a convention in a style guide is not. A pull request that adds a
signed comparison to the ring fails the build in the commit that adds it, and the failure names the
class and the method.

The bytecode scan is coarse. It refuses `LCMP` outright, so a genuinely signed comparison that
belongs in the placement packages has to move out of them or into the allowlist. The scope is
restricted to two packages for that reason, and the health, retry, and migration code is not
scanned, which means a signed comparison of a residue in `sharder-migrate` is not caught by the
check. `MOVE-141` makes residue opaque to the library and compares it against an integer threshold,
so the exposure is one comparison rather than an ordering.

`U64` as the single entry point costs a static call at every comparison. The JIT inlines it, and the
alternative is the sanctioned forms scattered across five strategies where the scan cannot tell a
correct one from an author's memory of one.

Declaring epoch and instant exempt reintroduces a judgement into a check that was meant to remove
one. The judgement is made once, at the declaration site, and the allowlist makes each exemption
visible in a diff.

The 128-bit product helper is used in four places where a `long` product would have been correct for
every realistic input, and would have inverted a threshold at a request count an operator can
reach in a day at a high rate. The cost is two multiplications rather than one.

## Alternatives

A wrapper type, `U64` as a value class with its own `compareTo`. Rejected because it allocates on a
path where a thousand-node rendezvous ordering computes a thousand scores, and because value classes
are not yet a language feature that removes the allocation.

An annotation, `@Unsigned`, with a checker framework pass. Rejected because the checker framework is
a build dependency of substance, because the annotation has to be applied correctly to work and an
author who would write `Comparator.comparingLong` is the author who would omit the annotation, and
because the bytecode scan catches the defect without either.

Review alone, with the rule stated in the binding document. Rejected because the defect is invisible
in a diff: a `Comparator.comparingLong` over a token accessor reads correctly, and a reviewer has to
hold the unsigned context to see it. The document states the rule anyway, and the check is what
makes the statement load-bearing.

Naming every unsigned field with a suffix, such as `tokenUnsigned`. Rejected because it makes the
hot code unreadable and because a suffix is a convention with the same enforcement problem as the
annotation.

`Comparator.comparingLong` composed with a sign flip, exclusive-or against `Long.MIN_VALUE`, which
turns an unsigned comparison into a signed one. Rejected as a sanctioned form because it is correct,
obscure, and easy to apply to one of two operands, and because `Long.compareUnsigned` says what it
does.

Extending the scan to every package in every artifact. Rejected because the health window counters,
the retry budget, the migration budgets, and the metric values are all genuinely signed, so the
allowlist would grow until it hid the cases the check exists for.
