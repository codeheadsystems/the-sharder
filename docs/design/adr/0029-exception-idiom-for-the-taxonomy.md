# 0029. Exception idiom for the taxonomy

Status: accepted. Date: 2026-09-16.

## Context

The specification writes a routing call as `route(key, options) -> Result<RoutingDecision, Error>`
and defines a closed set of sixteen conditions in `ERR-010`. `ERR-060` requires a binding to map
every condition to one idiom consistently, and forbids a binding that raises one condition and
returns another as a value. ADR 0024 records why the set is closed and numbered rather than a class
hierarchy.

Java has no result type in its standard library and no idiom for one. A hand-rolled `Result<T, E>`
is a familiar shape in some Java codebases and an unfamiliar one in most, and it composes badly with
the language: no pattern that unwraps it, no interaction with `try` blocks, and a method that
returns one cannot be used in a stream without a second unwrapping layer. Libraries that have tried
it in Java have generally ended up with both, which is what `ERR-060` refuses.

Against that, exceptions have a real cost on a hot path. A routing call at a million calls per
second that fills in a stack trace on every `noCandidate` is a routing call that has become an
allocation profile. Several of the sixteen conditions are not exceptional in the ordinary sense:
a lagging provider replica delivering a lower epoch is expected traffic, and `ERR-011` says the
caller's response to it is none.

The choice between checked and unchecked has its own weight. `noCandidate`, `unready`,
`staleSnapshot`, and `exhausted` are all conditions a serious caller handles, and a checked
exception is the language's way of saying so. A checked exception on `route` is also a wrapper at
every lambda, every stream, and every functional interface a caller uses.

## Decision

The binding raises exceptions, for every one of the sixteen conditions, with no result type
anywhere.

The taxonomy is rendered twice, because the two renderings do different jobs. `ErrorCode` is an enum
of the sixteen, each constant carrying the numeric code, the `ERR-010` spelling of the name, and the
retryable flag. It is the value a metric label, a log line, a serialised error, and a conformance
vector join on, and it is switchable and serialisable. The exception hierarchy is what `catch`
operates on: a sealed abstract `SharderException`, four sealed groups matching the numeric blocks of
`ERR-010`, and sixteen final leaves. `ERR-062` permits the grouping and forbids a leaf of the
binding's own, so the leaf set is closed at sixteen and sealing is what proves it closed to the
compiler.

Exceptions are unchecked. A caller that handles a condition catches the leaf or the group; a caller
that does not is not forced to wrap `route` at every call site.

`errorName` answers the camel-case spelling of `ERR-010` rather than the enum constant's own name,
so renaming a constant cannot change what crosses a wire.

`ERR-004` types `cause` as a string and uses it for two unrelated jobs: a closed sub-reason under
`ERR-021`, `ERR-022`, `ERR-050`, and `ERR-052`, and a node identity under `ERR-040`. The base class
keeps `cause()` for serialisation, and each leaf adds the typed accessor its own requirement
implies, so `NoCandidateException.reason()` answers an enum and `NotOwnerException.currentOwner()`
answers a `NodeId`.

The load pipeline does not raise. A document rejected on a push delivery or on a scheduled poll is
recorded, counted, and emitted as `sharder.topology.rejected`, because no call is waiting for an
answer and `ERR-011` asks nothing of the caller. The same conditions do raise out of `refresh` and
out of `TopologyLoader.validate`, which are calls an integrator made.

A replication shortfall raises nothing, under `ERR-009`.

## Consequences

A caller writes ordinary Java. `route` answers a decision or raises, it composes in a lambda, and
the two conditions that ADR 0024 was most concerned to keep apart are two different classes, so a
caller cannot catch one while meaning the other.

Unchecked means a caller can ignore every condition and find out in production. The mitigation is
partial: the sealed hierarchy makes a `switch` over a caught `SharderException` exhaustive, so a
caller who handles any of them is told about the ones they missed, and a caller who handles none is
told nothing.

Stack trace cost falls on conditions that are frequent under failure, which is when a system can
least afford it. The leaves that a routing call raises suppress stack trace capture by default and
expose a setting that restores it, because a `noCandidate` carries its cause, its token, and its
shard, and the stack tells an operator less than those three do.

Two renderings of one taxonomy mean two places to add a condition, which the closed set of `ERR-001`
makes a rare event and `ERR-003` makes a permanent one. The enum and the leaf set are checked
against each other by a test that reads both.

`SharderException.cause()` and `Throwable.getCause()` sit on the same object meaning different
things. That is a confusion the binding accepts in exchange for the spelling `ERR-004` gives, and it
is stated where the accessors are declared.

## Alternatives

A `Result<RoutingDecision, SharderError>` type, matching the specification's own notation. Rejected
because Java has no result idiom, because it composes with nothing in the language, and because
every Java codebase that adopts one ends up unwrapping it into an exception at the first boundary
that calls a framework. The specification's `Result` is notation for "answers or fails", not an
instruction to a binding, and `ERR-060` explicitly leaves the idiom to the binding.

Both: a result type on the routing path and exceptions elsewhere. Rejected by `ERR-060` in terms.

One `SharderException` class carrying an `ErrorCode` field, with no hierarchy. Rejected because a
catch clause cannot then select a condition, so every handler is a switch inside a catch that
rethrows what it did not want, and because the retryable decision becomes a runtime read rather than
a property of the thing caught.

Checked exceptions. Rejected because `route` is called from lambdas, streams, and functional
interfaces throughout a caller's code, and a checked exception there produces a wrapper class whose
only purpose is to be unwrapped, which loses the type the checking was meant to preserve.

An exception per numeric block and a `cause` enum for the leaf, giving four classes rather than
twenty. Rejected because catching `NoCandidateException` is what a caller wants to write, and
catching `RoutingException` and testing its code for 101 is what they would write instead.

Typing `cause` as one enum across all sixteen conditions. Rejected because `ERR-040` puts a node
identity in it, which no enum holds.
