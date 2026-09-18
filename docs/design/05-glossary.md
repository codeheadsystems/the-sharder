# Glossary

The vocabulary of the sharder library, defined once. Every document in this corpus uses these terms
as defined here and does not redefine them in passing. A term that also appears in
[`10-specification.md`](10-specification.md) carries the specification's meaning, and a term that
names a member of a topology document is given its syntax in
[`20-topology-format.md`](20-topology-format.md).

Status: the library is designed and not implemented. Every term here names something the design
states, not something that has run.

## Terms

- **key**. The octet sequence a caller presents to a routing call. The library treats a key as
  opaque and applies no character encoding, no normalisation, and no case folding of its own.
- **routing key**. The octet sequence a placement strategy actually hashes, derived from the key by
  the key transform. Where no key transform is configured, the routing key is the key.
- **key transform**. A configured, byte-level function from key to routing key, used to co-locate
  keys that share a prefix or a bracketed tag.
- **keyspace**. The set of all octet sequences a topology accepts as keys.
- **shard**. The unit of ownership. A shard is the smallest extent of the keyspace that the sharder
  library names, assigns to nodes, and moves between nodes. Under a given topology a key belongs to
  at most one shard, and to none where a `directory` table matches no entry for it. Under
  `rendezvous` a shard's extent is one routing key and its identifier is that key's octets in
  lowercase hexadecimal, so the strategy enumerates no shard extents.
- **shard identifier**. The stable name of a shard within a topology, unique across the topology
  document.
- **partition**. A synonym for shard in external literature. The corpus uses shard.
- **slot**. A shard produced by dividing the keyspace into a fixed count of numbered parts by
  modular arithmetic over the key hash.
- **token**. A 64-bit position on the ring, owned by a node, that terminates one token range. Where
  two nodes derive the same position, both entries stand and the lower node identity is walked
  first.
- **token range**. The half-open interval of hash space ending at a token and beginning at the
  preceding token in ascending order, wrapping at the top of the space. Under the `ring` strategy a
  token range is a shard.
- **node**. A member of a topology that can own shards and serve keys. A node is an addressable unit
  of capacity and failure, not a process, a host, or a container.
- **node identity**. The `id` field of a node, a non-empty string that is unique within a topology
  and stable across epochs. Node identity is the only node attribute a placement strategy hashes;
  weight, administrative state, and the failure domain path shape the strategy's input without
  entering its hash. Reusing an identity for a different node is a topology authoring defect.
- **address**. An opaque string carried on a node for the caller's benefit. Placement never reads
  it.
- **replica**. A node that holds or serves a copy of a shard. The first replica in a preference list
  is the primary; the rest are secondaries.
- **primary**. The first replica of a shard in its preference list.
- **replication factor**. The number of distinct replicas a shard is placed on, written `n`. An
  override entry's `factor` supersedes the document-level factor for the keys that entry matches.
- **preference list**. The ordered list of nodes for a key, produced by applying distinctness,
  failure domain spread, and replication factor to the candidate ordering. The first `r` entries are
  the replicas, where `r` is the achieved replica count and is at most `n`; the entries after them
  are the deterministic fallback tail.
- **candidate ordering**. The total ordering over eligible nodes that a placement strategy produces
  for a routing key, before replication rules are applied.
- **materialised prefix**. The portion of a preference list a routing decision carries: the replicas
  and the attempts the resolved attempt limit permits, whichever is longer. The rest of the list is
  computed on demand.
- **fallback tail**. The portion of a preference list beyond position `r`, the achieved replica
  count, walked when a replica is unreachable. Under a replication shortfall `r` is below `n`, so
  the tail begins earlier than the replication factor alone would place it.
- **placement strategy**. The named, configured function from routing key and node set to candidate
  ordering. The core strategies are `ring`, `rendezvous`, `slot`, and `directory`.
- **strategy configuration**. The `strategy` object of a topology document, whose `kind` field
  selects the strategy and whose remaining fields configure it.
- **rendezvous hashing**. Highest-random-weight placement, where each node scores the routing key
  and the ordering is by descending score.
- **ring**. Consistent hashing placement, where nodes own tokens on a 64-bit circle and a key is
  owned by the first token at or above its hash.
- **virtual node**. One of several tokens or scoring slots that a single node contributes, used to
  smooth distribution and to express weight. Abbreviated vnode.
- **weight**. A non-negative integer expressing a node's share of capacity relative to its peers, in
  weight units. A weight of zero places no keys on the node.
- **weight unit**. The abstract unit of `weight`. Weight units have no dimension and are meaningful
  only in ratio to the weights of other nodes in the same topology.
- **failure domain**. A set of nodes expected to fail together, such as a rack, an availability
  zone, or a region.
- **domain level**. A named tier of the failure domain hierarchy, declared once per topology. Levels
  are ordered from coarsest to finest.
- **failure domain path**. The tuple of a node's domain identifiers, one per declared level, from
  coarsest to finest. Two nodes share a failure domain at a level when their paths agree at that
  level and at every coarser level.
- **region**, **zone**, **rack**. Conventional names for domain levels. The library attaches no
  meaning to them beyond their position in the declared level order.
- **spread**. The requirement that the replicas of a shard occupy distinct failure domains at a
  named level.
- **occupancy cap**. The greatest number of a shard's replicas permitted to share one failure domain
  at a named level. Every cap is 1, so a spread requirement is the case in which the replicas occupy
  distinct failure domains, and no member of a topology document sets another value.
- **degradation order**. The sequence in which spread requirements are relaxed when no placement
  satisfies all of them.
- **topology**. The complete view of the world that the library routes against: the node set, their
  weights and domains, the placement strategy, the replication rules, and the epoch.
- **topology document**. The canonical serialised form of a topology, as specified in
  [`20-topology-format.md`](20-topology-format.md). The topology document is the interchange format
  between implementations and the input format for conformance vectors.
- **canonical form**. The single byte sequence a topology document reduces to under the
  canonicalisation rules, used for digesting and for comparison.
- **topology digest**. The SHA-256 digest of the canonical form of a topology document, in lowercase
  hexadecimal.
- **topology identifier**. The `topologyId` field, a stable name for a sequence of topologies over
  the same cluster. Epochs are ordered within one topology identifier and are incomparable across
  two.
- **epoch**. A non-negative integer no greater than 9007199254740991 that versions a topology within
  a topology identifier, assigned by the topology authority and strictly increasing.
- **fencing token**. The pair of topology identifier and epoch that accompanies a request, allowing
  a recipient to detect that the sender routed against a different view of the world.
- **topology authority**. The external component that decides topology content and assigns epochs.
  The authority is outside the library.
- **topology provider**. The plugin, conforming to the provider contract, that delivers topology
  documents from an authority to the library. Abbreviated provider.
- **source version**. An opaque value a provider attaches to a document it delivers and the library
  returns to that provider on its next request, so that a source that has not changed answers
  without sending the document again.
- **topology snapshot**. An immutable, validated topology held in memory and replaced as a whole.
  Abbreviated snapshot.
- **stale snapshot**. A snapshot the library continues to serve after the provider has failed to
  confirm or refresh it within the configured staleness bound.
- **administrative state**. The lifecycle intent an authority records for a node in the topology
  document: `active`, `joining`, `draining`, or `leaving`. Administrative state is authored, agreed,
  and identical for every caller.
- **placement set**. The nodes eligible for placement at an epoch: those whose administrative state
  is `active` or `draining`.
- **health state**. The runtime liveness classification a caller holds for a node, derived from
  locally observed signals. Health state is observed, caller-local, and may differ between callers.
- **health view**. The mapping from node identity to health state that a routing call consults.
- **attempt sequence**. The health-filtered subsequence of a preference list, in preference list
  order, that one caller attempts. The preference list is agreed between callers and the attempt
  sequence is local to one of them, because health state is local.
- **probation**. The interval during which a node that has returned to a healthy state receives a
  restricted share of traffic before full participation resumes.
- **read affinity**. An explicitly requested reordering of the replicas of a preference list towards
  a named failure domain path, available on a separate read call. It changes neither ownership nor
  the replica set, and the write path keeps the unreordered list.
- **routing decision**. The result of a routing call: a bounded prefix of the preference list with
  each entry's role and position, the shard the key was selected for, the effective replication
  factor, the achieved replica count, and the fencing token of the snapshot used. The prefix spans
  the replicas and the attempts the resolved limit permits, and the whole list is answered on
  demand.
- **explain record**. The structured account of how a routing decision was reached, listing the
  routing key, the matched override, the strategy inputs, the candidate ordering, and every node
  excluded with the reason for exclusion.
- **override**. A topology-level rule that matches keys and either pins them to an explicit node
  list or constrains the node set the strategy may choose from.
- **pin**. An override that replaces the candidate ordering with an explicit ordered node list.
- **constraint**. An override that restricts the eligible node set by domain or tag before the
  strategy runs.
- **directory**. An exhaustive override table, in which a key with no matching entry has no route.
- **caller**. The application that embeds the library.
- **integrator**. The person who embeds the library in an application and implements its extension
  points.
- **operator**. The person who runs the resulting system and edits topologies.
- **rebalance**. The whole process of moving from one epoch to the next, including the ownership
  delta and every handoff it implies.
- **ownership delta**. The set of shards whose replica set differs between two epochs, with the
  nodes gained and lost for each.
- **migration**. The movement of one shard's contents from its former replicas to its new ones.
- **handoff**. The coordinated protocol by which one shard's ownership passes from a source node to
  a destination node, sequenced by the library and executed by the integrator's movement hooks.
- **movement hook**. An integrator-supplied callback that performs one step of a handoff, such as
  copying a shard's contents or verifying a copy.
- **handoff coordinator**. The component that drives a handoff through its states. Abbreviated
  coordinator.
- **rebase**. Moving a plan onto a newer topology snapshot, one handoff at a time. A handoff whose
  shard, source, and destination still hold under the newer snapshot continues from the state it is
  in; the rest are aborted. A rebase moves no ownership and commits no cutover record.
- **re-observation**. A single further call to the `observe` hook for a handoff whose cutover
  outcome the library never established, which either resolves the handoff or leaves it where it
  is.
- **cutover**. The handoff state in which the source quiesces and the cutover record is committed by
  a single-winner write the integrator's hooks perform. The cutover instant is the transition out of
  it, from `cutover` to `verifying`, at which the destination becomes the authoritative replica for
  a shard and the source ceases to be.
- **quiesce lease**. The interval for which a source node undertakes to refuse writes for a shard
  after a successful `quiesce`. The source grants it and enforces it on its own clock; the
  coordinator evaluates it on the clock the integrator supplies, less a margin that covers the
  divergence between the two.
- **drain**. The administrative act of marking a node so that the authority moves its shards away
  before the node leaves the topology. A draining node continues to own and serve its shards until a
  later epoch reassigns them.
- **hinted handoff**. The practice of writing to a substitute node while a replica is unavailable
  and replaying to the replica on its return. The library exposes it as a hook and implements no
  part of it.
- **minimal movement**. The bound on how many keys change owner when a node is added to or removed
  from a topology.
- **balance**. The spread of keys across nodes relative to their weights, measured as the ratio of a
  node's observed share to its weighted expected share.
- **hot shard**. A shard receiving a share of traffic far above its share of the keyspace.
- **conformance vector**. A language-neutral data file pairing inputs with the exact output every
  conforming implementation produces.
- **port**. An implementation of the sharder library in one language, whose source sits under
  `ports/` and which declares the conformance levels it reaches. The reference implementation that
  computes the suite's expected values is not a port, because an implementation that calls it agrees
  with it by construction.
- **conformance declaration**. The published statement of what a port reaches: the suite revision it
  ran, every level marked reached or excluded, the strategy surfaces it exposes, its driver's
  output, the figures it observed at the `scale` level, and its deviations.
