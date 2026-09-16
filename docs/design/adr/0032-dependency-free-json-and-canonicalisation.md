# 0032. Dependency-free JSON and canonicalisation

Status: accepted. Date: 2026-09-16.

## Context

The brief sets near-zero dependencies as the target for the Java binding. The topology format makes
that harder than it sounds. ADR 0008 fixes the document as JSON, the canonical form as RFC 8785 JSON
Canonicalization Scheme in UTF-8, and the digest as SHA-256 of that form. Stage 1 of `TOPO-001`
decodes JSON, stage 2 validates against the published JSON Schema, and stage 4 computes the
canonical form and the digest. A binding therefore needs a JSON reader, a JSON Schema validator, a
canonicaliser, and a hash, and only the last of those is in the JDK.

A dependency in a library embedded widely is not a line in a POM. It is a version an integrator's
build has to reconcile with whatever else on their classpath depends on the same library, and a
transitive vulnerability an integrator has to patch on the library's release schedule rather than
their own. Jackson in particular is present in most Java deployments at some version, and a routing
library that pins one is a routing library that participates in every conflict that version has.

Two properties of the format make the dependency less necessary than it would otherwise be. ADR 0008
refuses unknown members at every level, so a general-purpose binder's tolerance is not merely
unnecessary but wrong. ADR 0008 also restricts every JSON number to an integer in 0 to
9007199254740991, which removes the hardest rule of RFC 8785, its reproduction of the ECMAScript
number serialisation for an arbitrary double, from every code path the format reaches. Structural
validation rejects a fractional or out-of-range number at stage 2, which precedes canonicalisation
at stage 4, so the canonicaliser never meets one.

The published schema is the cross-language contract, and a hand-written validator can drift from it.
That drift is the real risk in dropping a schema validator, not the validator's absence.

## Decision

`sharder-api`, `sharder-core`, `sharder-migrate`, and `sharder-provider-file` require `java.base`
and nothing else. Their POMs declare no compile or runtime dependency, and a build check fails a
published POM that gains one.

`core.internal.json` carries a reader over the JSON grammar of RFC 8259, producing a tree that
preserves member insertion order and exact integer values. It rejects, as a structural validation
failure rather than as a parse failure, a non-integer number, an integer outside the safe range, a
duplicate member name within one object, a byte order mark, and a string containing an unpaired
surrogate.

`JcsWriter` implements RFC 8785 for the subset the format reaches: a recursive sort of object
members by the UTF-16 code unit sequence of their names, the scheme's string escaping rules, plain
decimal integers, and UTF-8 output. `MessageDigest.getInstance("SHA-256")` computes the digest.

The binding runs no general-purpose JSON Schema validator. Stage 2 of `TOPO-001` is a hand-written
structural validator that enforces exactly what `topology-v1.schema.json` declares, and stage 3 is a
semantic validator that enforces the rules of `20-topology-format.md`. Both accumulate every error
rather than stopping at the first, under `ERR-030`.

The published schema stays the contract, and a conformance suite in the schema family asserts that
the structural validator accepts exactly the documents the schema accepts and rejects exactly the
ones it rejects, over an accept corpus and a reject corpus the vectors carry. That suite is what
keeps the validator and the schema together.

The library logs nothing and depends on no logging facade. What a logging framework would carry
travels through `EventSink` and `MetricsRegistry`, both of which the integrator implements against
whatever they already run.

Test and build dependencies are unconstrained by this policy, because they reach no consumer.

## Consequences

An integrator adds `sharder-core` to a build and resolves nothing else. There is no version to
reconcile, no shading to consider, and no transitive advisory to track on the library's schedule.

Two pieces of general-purpose infrastructure are now the library's to maintain and to get right: a
JSON reader and an RFC 8785 canonicaliser. Together they are a few hundred lines with a narrow
input profile, and the conformance vectors for canonicalisation and digest exercise them against
every other port, which is a stronger check than a widely used dependency's own test suite would
give for this use.

Rejecting duplicate member names is stricter than most JSON parsers and is necessary: two parsers
resolve a duplicate differently, and the two resulting canonical forms have different digests, so a
document that round-trips in one port and another would compare unequal at the same epoch.

Rejecting an unpaired surrogate is stricter than the JSON grammar and is necessary for the same
reason: it has no UTF-8 encoding, so no canonical form over it is well defined.

The hand-written validator can drift from the published schema. The schema conformance family is the
only thing preventing it, and that family is a dependency of this decision rather than an optional
extra. A port that skips it has a validator whose relationship to the contract is unverified.

`ExplainRecord.toJson` under `OBS-047` reuses the canonical writer, so the explain serialisation
costs no further code and no dependency.

## Alternatives

Jackson, or Gson, for parsing. Rejected because the dependency is exactly what the brief asked to
avoid, because a general-purpose binder's tolerance of unknown members is the behaviour ADR 0008
refuses, and because the parse surface the format needs is a small fraction of what either library
provides.

A JSON Schema validator such as the networknt implementation, run against the published schema at
stage 2. Rejected because it is the largest dependency of the set, because it brings a JSON parser
of its own, and because the error reporting it produces is not the accumulated `ValidationError`
list that `ERR-030` requires without a translation layer of comparable size to the validator the
binding writes instead.

Generating the structural validator from the published schema at build time. Rejected for now
because the generator is a build dependency with its own correctness problem, and because the
schema conformance family verifies the relationship either way. It remains the obvious way to remove
the drift risk if the drift proves real.

A pluggable `DocumentReader` extension point, so an integrator supplies Jackson where they already
have it. Rejected because the parse behaviour is part of the digest: two integrators with different
readers would resolve a duplicate member or an out-of-range number differently and produce different
digests for the same octets, which is the divergence ADR 0008 exists to prevent.

Depending on a JSON library only in `sharder-provider-file`, where a document is read from disk.
Rejected because the reader is needed in `sharder-core` regardless, at stage 1 of the pipeline, so
the dependency would not be avoided by moving it.

Shading a JSON library into `sharder-core`. Rejected because it hides the dependency from an
integrator's tooling rather than removing it, including from the tooling that reports a
vulnerability in it.
