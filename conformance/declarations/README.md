# Conformance declarations

One file per port, named by the port identifier, stating what that port reaches: the suite revision
it ran, every conformance level marked reached or excluded, the placement strategy surfaces it
exposes, its driver's output, the figures it observed at the `scale` level, and its deviations.

[`../../docs/design/30-conformance.md`](../../docs/design/30-conformance.md#declaring-conformance)
states what a declaration publishes, and
[`#conformance-declaration`](../../docs/design/35-port-conventions.md#conformance-declaration)
states its member set with a worked example.

A declaration is written by hand and is a claim the port's maintainer makes.
[`../generator/verify_declarations.py`](../generator/verify_declarations.py) checks it against
`../manifest.json` and the specification, and fails on a claim either one contradicts. A declaration
naming an earlier revision than the manifest holds is reported as lagging rather than failed.

A port carries no declaration until it reaches `hash`, `place`, `core`, and `scale`, which
`CORE-110` makes mandatory. Before that, the port's own README states which levels pass.
