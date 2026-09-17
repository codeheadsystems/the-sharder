# 0041. Exact product comparison surface

Status: accepted. Date: 2026-09-17.

## Context

`CORE-005` requires a comparison between products of integers to hold over the exact products. Five
requirement identifiers were named as stating such a comparison: `PLACE-051`, `HEALTH-034`,
`FAIL-031`, `OBS-031`, and `SPLIT-041`.
[`0037-specification-defect-repairs.md`](0037-specification-defect-repairs.md) records the repair
that introduced the rule, and [`../40-java-binding.md`](../40-java-binding.md) rendered all of them
through one helper.

```java
static int compareProducts(long a, long b, long c, long d);   // sign of (a*b - c*d), exact
```

Three things are wrong with that, and each is reproducible against material the repository already
ships.

The helper's shape does not fit two of the comparisons. `OBS-031` is
`shardRequests * shardCount * 100 >= totalRequests * hotShardFactorPercent`, which names three
operands on the left, and `FAIL-031` is
`retries * 100 <= retryBudgetPercent * firstAttempts + 100 * retryBudgetMinimum`, which compares a
product against a sum of two products. Neither is `a * b` against `c * d`.

The primitive is signed. `Math.multiplyHigh` returns the high half of a signed product. Every
operand of these comparisons is an unsigned 64-bit value carried in a `long`, because `SPLIT-021`
types every member of a `ShardReport` as a u64. On OpenJDK 25, `Math.multiplyHigh(-1L, 50L)` is -1
and `Math.unsignedMultiplyHigh(-1L, 50L)` is 49. The suite carries an input that separates them:
`keySkew/9223372036854775808-18446744073709551615` in
`conformance/vectors/formulas/skew-detection.json` has `hottestKeyRequests` at 2^63 and
`requests` at 2^64 - 1, and the signed form answers false where the vector expects true.

`PLACE-051` is not a comparison between two products. It is `min(weight * perWeightUnit, cap)`, one
product against one value, and it already states its own width rule. Naming it under `CORE-005` sent
an implementer to a wide helper at a site where a `long` is provably exact.

The operand ranges were never stated, so the width each comparison needs could not be read off the
specification. They are not the same. `HEALTH-034` takes two counts over the placement set and a
percentage `CFG-031` holds at or below 100. `OBS-031` and `SPLIT-041` take u64 measurements from an
integrator. `FAIL-031` takes counts over a window whose length `retryBudgetWindowMillis` sets, and
nothing bounds those counts.

`FAIL-031` is also the one comparison on a hot path. It is the retry budget, evaluated once per
attempt under `FAIL-030`, so an arbitrary-precision evaluation there is paid on every retry the
library serves.

## Decision

`CORE-005` is restated and the binding's surface is replaced.

`CORE-005` names four requirements rather than five. `PLACE-051` and `PLACE-074` are moved to a
closing sentence that says they compare one product against one value and state their own width. The
rule itself now speaks of products rather than of two products, forbids a sum of products from
wrapping as well as a product, and says which two of the four are not a single product on each side.
A table states the operand range of each of the four and the width it needs, so an implementer reads
the width from the requirement rather than deriving it.

| Requirement | Width |
|---|---|
| `HEALTH-034` | neither side reaches 2^38, so 64 bits are sufficient |
| `FAIL-031` | sufficient in 64 bits only where the implementation bounds the counts it holds |
| `OBS-031` | the left side reaches 91 bits |
| `SPLIT-041` | the left side reaches 71 bits |

The Java binding evaluates `HEALTH-034` in `long` with the signed operators, because both counts are
`int` and `CFG-031` caps the percentage at 100. It evaluates the other three through two static
methods on `U64`, which read every operand as unsigned 64-bit, form each product in 128 bits with
`Math.unsignedMultiplyHigh`, and allocate nothing.

```java
static int compareProducts(long a, long b, long c, long d);              // a*b vs c*d
static int compareProductToSum(long a, long b, long c, long d, long e);  // a*b vs c*d + e
```

Both are total over the whole unsigned 64-bit range of every operand. The product of two unsigned
64-bit values is at most 2^128 - 2^65 + 1, so the further unsigned 64-bit addend of
`compareProductToSum` cannot carry the sum past 2^128 - 1.

A left-hand side of three operands folds its two small factors into one operand before the call,
which is what keeps the surface at two products rather than three. `OBS-031` folds
`shardCount * 100` into a single `long`, exactly, because `shardCount` is an `int`. That fold is the
whole reason a two-product signature is enough, and it is stated in the binding rather than left to
the reader.

`Math.multiplyHigh` joins the forbidden forms of the unsigned discipline of
[`0030-unsigned-integer-discipline.md`](0030-unsigned-integer-discipline.md), alongside
`Long.compare` and `Comparator.comparingLong`, and `verifyUnsignedComparisons` refuses an invocation
of it. The JDK feature table names `Math.unsignedMultiplyHigh` at release 18, which is inside the
floor [`0031-jdk-baseline.md`](0031-jdk-baseline.md) sets.

## Consequences

Every comparison `CORE-005` governs has an expression in the binding that reproduces the shipped
vectors. The four formula call sites were compiled under `--release 21` and run against the 32 cases
of `vectors/formulas/skew-detection.json`, `vectors/formulas/failover.json`, and
`vectors/formulas/health.json` that name `CORE-005`, and against two million random operand
quintuples cross-checked with `BigInteger`. The
`shardIsHot/9223372036854775807-1048576-18446744073709551615` case reaches 90 bits on the left with
an 83-bit intermediate, and answers as the vector expects.

The retry budget costs two multiplications, one high-half multiplication, an add, and two unsigned
comparisons per attempt, with no allocation and no branch on operand magnitude.

The surface is two methods rather than one, and neither is a general 128-bit arithmetic type. A port
that later needs a third shape adds a third method rather than reaching into a `U128`, which keeps
the set of exact wide operations small enough to read.

A port that types its retry budget counters narrowly may still evaluate `FAIL-031` in 64 bits and
conform, because `CORE-005` permits any rule that agrees with the exact comparison over the range of
the operands the implementation admits. The specification states no bound on those counts, so it
cannot require one width.

Requirement identifiers are unchanged. `PLACE-051` keeps its number, its text, and its own width
rule; only the sentence in `CORE-005` that named it is gone. The conformance suite is keyed to
identifiers and none moved, so coverage is unaffected.

## Alternatives

One helper for all five comparisons, repaired only to be unsigned. Rejected because the signature
cannot express `OBS-031` or `FAIL-031` at all, which is the defect that started this record, and
because it puts a wide evaluation at two sites where a `long` is provably exact.

A general unsigned 128-bit type, `U128`, carrying multiplication, addition, and comparison. Rejected
because the design needs exactly two shapes, and a type invites a third and a fourth. It also costs
a decision about representation: a record allocates unless the just-in-time compiler removes it, and
an `(hi, lo)` pair carried through static methods needs two calls to return one value, which reads
worse at the call site than the two methods chosen.

`BigInteger` at every site. Rejected for `FAIL-031`, which is evaluated once per attempt and would
allocate three objects per evaluation on the retry path. Rejected for `OBS-031`, which is evaluated
per shard per `intervalMillis` and reaches 1048576 shards under `slot`. `CORE-005` permits it, so a
port may still choose it; the binding does not.

A three-operand helper, `compareProducts(a, b, c, d, e)` as the sign of `a*b*c - d*e`. Rejected
because the third operand of `OBS-031` is a constant 100 and the second is an `int`, so folding them
is exact in a `long` and costs nothing, while a three-operand product needs a 192-bit intermediate
to be total.

Evaluating `FAIL-031` in `long` by declaring the window counters `int`. Rejected because an
accounting window is set by `retryBudgetWindowMillis`, which admits several days, and a count of
attempts over several days passes 2^31 at a rate an ordinary service reaches. The counters are
`long`, and the comparison is wide because of it.

Bounding `retryBudgetPercent` at 100 so that the products fit a `long`. Rejected because a budget
above 100 per cent is a meaningful configuration for a caller that retries more often than it makes
first attempts, and the specification has no other reason to forbid it.

Leaving `PLACE-051` named in `CORE-005` as harmless over-specification. Rejected because it is not
harmless: the review that found this defect read the list as the set of sites needing the wide
helper, which is what a list in a normative requirement is for.
