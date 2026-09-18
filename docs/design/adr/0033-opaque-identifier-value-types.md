# 0033. Opaque identifier value types

Status: accepted. Date: 2026-09-16.

## Context

`CORE-001` types `NodeId`, `ShardId`, `Key`, and `RoutingKey` as octet sequences. `CORE-003` and
`PLACE-020` require node and shard identities to be compared as unsigned octet sequences and forbid
comparing either as text. `PLACE-021` fixes the prefix rule: where one sequence is a proper prefix
of the other, the shorter compares less.

The obvious Java rendering is `byte[]`. It has three properties that are wrong here. It has identity
equality, so a `Set<byte[]>` of node identities is a set of references and `PLACE-013` cannot be
implemented over it. It has an unusable `hashCode`, so a `Map` keyed by node identity, which the
health view of `HEALTH-006` and the metric recorder both need, does not work. It is mutable, so a
caller who hands the library an array and then writes to it changes a `RoutingDecision` that
`CORE-041` declares immutable.

Not every octet sequence in the specification has the same needs. A domain identifier, a tag value,
a tag key, a level name, and `topologyId` are compared for equality and never for order:
`OVR-022`, `OVR-023`, `OVR-026`, `SPREAD-011`, and `FENCE-071` all test equality. A key is supplied
by the caller on every call, is not retained under `CORE-070`, and is on the hottest path in the
library, so wrapping it would allocate and copy a buffer per routing call for no gain.

The specification also uses `X | none` in eleven places, and Java has `Optional`, `null`, and a
sentinel. `Optional` allocates and is unwelcome on a hot path; `null` is invisible in a signature
and produces a failure far from its cause.

## Decision

`NodeId`, `ShardId`, and `RoutingKey` are final value classes over an immutable copy of their
octets, with cached `hashCode`, value `equals`, and `compareTo` implemented by
`Arrays.compareUnsigned`. `Digest` has the same shape over exactly 32 octets and adds `toHex`.

`Arrays.compareUnsigned(byte[], byte[])` is exactly `PLACE-020` and `PLACE-021`, prefix rule
included, so the binding writes no comparison loop and no port-specific interpretation of the rule.

A domain identifier, a tag key, a tag value, a level name, and `topologyId` are `String`. UTF-8
octet equality and `String.equals` agree for every well-formed string, and structural validation
rejects a document carrying an unpaired surrogate, which is what makes the agreement total. No
ordering over any of them is required by any requirement.

A key is not wrapped. `route` accepts `byte[]` and `String`, retains neither under `CORE-070`, and
copies the octets only where an `ExplainRecord` holds them.

`Optional` marks absence on a public return type where absence is a documented answer the caller
handles, and the eleven `X | none` shapes of the specification each map to `Optional`,
`OptionalInt`, or `OptionalLong`. Null crosses no API boundary in either direction, and no record
component is ever null. Internal code uses a sentinel or a null field, and neither escapes.

The candidate cursor of ADR 0034 carries no `Optional`, because it answers once per candidate on the
routing path.

`NodeSet` is a purpose-built final class rather than a `java.util.Set`, and iterates in ascending
node identity order. `CORE-002` forbids the iteration order of a node set from reaching a result,
and a `Set` hands a caller `stream`, `iterator`, and `parallelStream` in hash order.

## Consequences

A node identity is a map key, a set member, and a sort key without ceremony, and `PLACE-013`,
`HEALTH-006`, and the metric label paths all work over ordinary collections.

Each value class costs one object and one array copy at construction. Node identities are
constructed at document load and held on the snapshot, so the cost falls on installation rather than
on routing. Shard identifiers under `ring` and `slot` are constructed per routing call where the
caller asked for the shard, which is the one place the cost is on the hot path, and it is one small
allocation against a decision that allocates a list of preference entries anyway.

Rendering a domain identifier as `String` rather than as a value type over octets means the octet
comparison of `OVR-026` is implemented by `String.equals`. That is correct only because the two
agree for well-formed strings, and it is correct only while the validator rejects an unpaired
surrogate. The dependency between the two is stated at the validator and at the comparison.

Leaving the key as `byte[]` puts an obligation on the caller that `CORE-070` already states and that
the type system does not enforce. A caller who mutates a key buffer mid-call gets a wrong route
rather than an exception. The alternative costs a copy on every routing call in the library's
hottest path, for a defect the specification already assigns to the caller.

`Optional` on eleven return types allocates on calls that are mostly not on the hot path. The two
that are, `RoutingDecision.shard` and `AttemptSequence.next`, allocate one small object per call and
per attempt respectively, which the allocation gate in `check` measures.

`NodeSet` as its own class means a caller cannot stream it, which is a real ergonomic loss for code
that wants to inspect a placement set. `TopologySnapshot.nodes()` returns a `List<Node>` for that
purpose, and it is in document order rather than in any order a placement result could depend on.

## Alternatives

`byte[]` throughout, matching the specification's own typing. Rejected for identity equality, for
the unusable `hashCode`, and for mutability, each of which breaks a requirement rather than an
aesthetic.

`ByteBuffer` as the identifier type. Rejected because a `ByteBuffer`'s equality depends on its
position and limit, which is a sharper version of the same class of defect, and because a caller who
reads from one changes it.

`String` for node and shard identities, since both are spelled as strings in the document. Rejected
because `PLACE-022` forbids comparing node identities by code point, and `String.compareTo` compares
by UTF-16 code unit, which differs from UTF-8 octet order for every character above the basic
multilingual plane. A topology whose node identities contain an emoji would order differently in
Java than in every port that compares octets.

A record over `byte[]`, which is the shortest thing to write. Rejected because a record's generated
`equals` and `hashCode` delegate to the component's, which for an array is identity.

Value types for domain identifiers and tag values as well, for uniformity. Rejected because no
requirement orders them, so the value type would buy only the uniformity, and it would cost an
allocation per domain entry per node at load.

Null rather than `Optional` for absence, with the contract in the documentation. Rejected because
the eleven absence cases include several a caller genuinely has to handle, `shardOf` under `DIR-020`
most of all, and a null there produces a failure in the caller's code rather than at the boundary.

Making `NodeSet` a `java.util.Set`. Rejected because `parallelStream` on a node set is a direct
violation of `CORE-002` that the type would advertise.
