# 0034. Lazy candidate traversal surface

Status: accepted, with the decision surface that consumes the cursor settled by
[`0046`](0046-bounded-routing-decision-surface.md) and the degraded path measured by
[`0070`](0070-spread-stage-feasibility-from-a-domain-count.md). Date: 2026-09-16.

The consequence below, that a builder at factor 3 over a thousand-node ring computes nothing beyond
the entries it needs, held of the builder and not of the routing call that contained it: `CORE-040`
required the whole candidate ordering on the decision, so the only routing-path consumer of the
cursor drained it. `0046` bounds what the decision materialises, and the consequence holds as
written.

It holds of the healthy path. A builder that cannot fill its replica prefix at a relaxation stage
walks the ordering to its end to establish that it cannot, once per stage.
[`0070`](0070-spread-stage-feasibility-from-a-domain-count.md) states that cost, removes it where a
domain count rules the stage out, and reports the topology that provokes it.

## Context

`CORE-012` and `PLACE-015` permit `candidates` and `candidatesForShard` to compute their result
lazily, and require the first `p` entries consumed to equal the first `p` entries of the eager
ordering for every `p`. The preference list builder of `REPL-012` consumes the candidate ordering
from its first entry until the replica prefix holds `n` entries, which for the common case of factor
3 is a handful of entries from an ordering over the whole eligible node set.

The size of the ordering is what makes this matter. A storage topology of a thousand nodes at four
tokens per weight unit and a weight of 100 gives a ring order of four hundred thousand entries and a
candidate ordering of a thousand nodes. Materialising that per routing call to read three of them is
the difference between a routing library and a routing cost.

`CORE-012` also constrains sharing: an iterator these methods return is usable by one unit of
execution at a time and is not shared across two. `CORE-057` adds that the binding must not make it
safe by taking a lock, because that cost would fall on every routing call.

Java offers three shapes. `Iterator<NodeId>` is the language's traversal interface. `Stream<NodeId>`
is the language's lazy sequence. A cursor with a separate advance and read is the shape the JDK uses
where lookahead is expensive, as in `Matcher` and in the database cursor idiom.

The difference between them is not only style. `Iterator.hasNext` on a lazily computed ordering has
to compute the next element to answer, so a caller that asks whether more entries exist and then
does not take one has paid for one it did not use, and the iterator has to buffer it. On a ring
walk, computing the next element means scanning forward over ring entries whose owners have already
been emitted, which under a thousand nodes at four hundred tokens each is a scan of unbounded
length for an answer the caller may discard. `Stream` offers `parallel`, which violates `CORE-012`
in terms, and `collect`, which discards the laziness that `PLACE-015` exists to provide.

## Decision

`candidates` and `candidatesForShard` return a cursor of two members, given in
[`../40-java-binding.md`](../40-java-binding.md). One advances the cursor and answers whether a
further candidate exists; the other answers the current candidate and is valid after the first
answered true. There is no lookahead, no buffered element, and no exception for reading past the
end.

A cursor is thread-confined under `CORE-012` and `CORE-057`. It takes no lock. An assertion checks
the owning thread when assertions are enabled, so a shared cursor fails in the conformance and test
builds and a production build pays nothing for the check.

`PreparedPlacement.shards()` returns an `Iterator<ShardId>`. It is off the routing path, it is
consumed by delta computation and by plan construction, and it carries no restriction beyond an
iterator's own.

`AttemptSequence.next()` returns `Optional<NodeId>`, not a cursor. An attempt sequence answers a few
times per request rather than once per candidate, and `FAIL-023` writes it as a call answering a
node or `exhausted`.

## Consequences

The preference list builder at factor 3 over a thousand-node ring advances the cursor until three
entries pass distinctness and the spread stage in force, and computes nothing beyond that. The lazy
prefix property of `PLACE-015` is what the surface provides rather than what it permits.

A caller writing a loop over a cursor writes a while loop that advances and then reads, which is
not the for-each loop Java authors write by habit. A cursor is not `Iterable`, deliberately, because
making it `Iterable` would put it back in a for-each loop whose desugaring is the `Iterator` this
decision rejects.

`Stream` being absent means a caller cannot write a filter or a limit over a candidate ordering in
the stream idiom. The library's own consumers of the ordering, the preference list builder and the
explain builder, are the only consumers on the routing path, and a caller who wants the whole
ordering has `ExplainRecord.candidates()`, which is a list because it is an operator surface and is
computed only where asked under `OBS-046`.

Thread confinement is documented and asserted rather than enforced. A caller who shares a cursor
across two threads and runs without assertions gets an interleaved ordering rather than an
exception. `CORE-057` forbids the lock that would prevent it, so the assertion is the whole of the
mechanism, and the conformance and test builds are where it fires.

`shards()` and `candidates` having different shapes is an inconsistency a reader notices. It follows
the difference in their constraints: one is a hot-path traversal with a single-walk rule, the other
is not.

## Alternatives

`Iterator<NodeId>`. Rejected because `hasNext` forces the next element and buffers it, which on a
ring walk means an unbounded scan for an answer the caller may discard, and because the interface
carries `remove`, which has no meaning here.

`Stream<NodeId>`. Rejected because `parallel` violates `CORE-012` directly and the interface
advertises it, and because `collect` and `toList` discard the laziness that is the point. A stream
also carries a close obligation the design does not need and a pipeline cost the routing path does
not want.

`Iterable<NodeId>`, so that a for-each loop works. Rejected because a for-each loop desugars to the
`Iterator` already rejected, and because an `Iterable` invites a second traversal, which a
single-walk lazy ring cursor cannot provide.

A materialised `List<NodeId>` from `candidates`, with laziness dropped as an implementation detail
the specification merely permits. Rejected because the specification permits laziness for a reason
the sizes make concrete, and because the list would be allocated and filled per routing call for a
builder that reads three entries from it.

A callback, `candidates(rk, eligible, consumer)` where the consumer answers whether to continue.
Rejected because it inverts control in a way that makes the preference list builder's spread ladder,
which evaluates up to nine stages over the same ordering under `SPREAD-017`, harder to express than
the loop it replaces.

Making the cursor safe for concurrent use with a lock. Rejected by `CORE-057` in terms.
