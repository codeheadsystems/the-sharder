# 0031. JDK baseline

Status: accepted. Date: 2026-09-16.

## Context

The sharder library is meant to be embedded widely. A library's minimum JDK is the most visible
adoption cost it has, because a deployment that cannot move to it cannot use the library at all,
whatever the library does.

Three releases are live candidates. Java 17 is the long-term-support release that most enterprise
deployments reached, and it is the floor of a great deal of currently deployed infrastructure. Java
21 is the long-term-support release that followed it, three years old at the time of writing, and
the floor a large share of new libraries have taken. Java 25 is the current long-term-support
release and is the floor of the most recent framework generation.

The design leans on the type system in two places that a lower floor would change. The migration
hook results of `MOVE-111` are discriminated unions: `HookResult` over four variants,
`CutoverResult` over six, `VerifyResult` over three, and `StepOutcome` over five. Handling each of
them correctly is the difference between a handoff that reaches `complete` and a handoff that calls
`cleanup` on the only surviving copy, which `MOVE-181` exists to prevent. The other is the value
types of the core model, which are records in every candidate release.

Sealed interfaces arrive in 17. Pattern matching for `switch` and record patterns arrive as final
features in 21. The difference between them is whether a `switch` over a sealed type is exhaustive
without a default branch. With exhaustiveness, adding a variant to `CutoverResult` in a later
version fails the compilation of every handler. Without it, the handler falls through a default
branch at runtime, in the middle of a cutover.

`HexFormat`, used for the sixteen-digit token rendering of `RING-030`, the digest rendering, and the
`base16` matcher decoding of `PLACE-060`, arrives in 17.

The library starts no thread under `CORE-060` and holds no lock across an extension point under
`CORE-063`, so nothing in the design needs virtual threads and nothing in it pins a carrier.

## Decision

Java 21 is the floor. Published artifacts are compiled with `--release 21`. The build itself runs on
the current long-term-support release and cross-compiles, so the toolchain moves without the floor
moving.

## Consequences

A deployment on Java 17 cannot adopt the library without moving. That is the cost, and it is paid by
the population that has moved to 17 and not beyond, which is real and shrinking.

A deployment on 21 or above adopts it with no constraint, including every deployment on 25.

The coordinator's handling of `CutoverResult`, `HookResult`, `VerifyResult`, and `StepOutcome` is
written as a `switch` with no default branch and no fallback case. A variant added later is a
compilation failure in every handler in every downstream integrator, which is the intended
behaviour for a set of outcomes where a missed case loses data.

Compiling with `--release 21` rather than setting source and target compatibility means the compiler
checks the API against the 21 signature file, so a method added in 22 cannot be called by accident
and pass the build on a 25 toolchain.

Nothing in the design uses a preview feature, so no artifact carries `--enable-preview` and no
consumer has to.

Raising the floor later is a major version of the library. Lowering it is not possible without
rewriting the sealed result handling, which is why the choice is made once here.

## Alternatives

Java 17. Rejected because pattern matching for `switch` is a preview feature there, so the sealed
result types would be handled by an `instanceof` chain or a visitor. Both work and neither is
exhaustive: a variant added later compiles against both, and the failure surfaces at runtime inside
a handoff. The population gained by the lower floor is not worth a silent fallthrough in the one
part of the library where a wrong branch loses data.

Java 25. Rejected because the design uses no feature that 21 lacks, so the floor would exclude
deployments on 21 in exchange for nothing. A floor is only worth raising for a feature the design
depends on.

Java 21 for `sharder-core` and Java 17 for `sharder-api`, so that a third-party provider compiles on
17. Rejected because a provider is useless without the core artifact that consumes it, so the lower
floor on the api artifact buys nothing, and because two floors in one repository is a build
configuration that goes wrong quietly.

A multi-release jar with a 17 baseline and 21 overrides. Rejected because the feature in question is
exhaustiveness at the caller's compile time, which a multi-release jar cannot provide: a downstream
handler compiled against the 17 classes would not be checked.

Java 11. Rejected because records are the shape of most of the core model and the alternative is
several thousand lines of generated or hand-written boilerplate whose `equals` and `hashCode` are a
correctness dependency of `CORE-041`.
