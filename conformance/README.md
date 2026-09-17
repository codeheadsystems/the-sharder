# sharder conformance suite

Language-neutral data files that hold every port of the sharder library to the same routing
decisions. The design is
[`../docs/design/30-conformance.md`](../docs/design/30-conformance.md), which gives the vector file
format, the driver contract, the conformance levels, and the rule by which a port declares
conformance.

Every expected value here was computed by the reference implementation in `generator/`. None was
written by hand.

## Contents

| Path | Contents |
|---|---|
| `manifest.json` | the conformance levels, every vector file with its level, coverage, and digest, and every topology digest |
| `coverage.json` | requirement coverage, computed from `10-specification.md` |
| `topologies/` | the topology documents the vectors route against |
| `topologies/invalid/` | documents that fail a load-time rule |
| `vectors/` | the golden vectors, grouped by subject |
| `properties/properties.json` | the property definitions, with their bounds and sample sizes |
| `scenarios/` | the simulation scenarios, with `index.json` listing them |
| `generator/` | the reference implementation and the generator scripts |
| `driver/python/` | a driver written against the reference, as a worked example |

## Running the suite

A port writes a driver that reads `manifest.json`, dispatches on each vector file's `kind`, and
compares each case's result to its `expect` object field by field. The driver contract is in
[`../docs/design/30-conformance.md`](../docs/design/30-conformance.md), and
[`driver/python/run_suite.py`](driver/python/run_suite.py) is a worked example of it.

Each vector file and each scenario carries the conformance level it belongs to, and the manifest's
`levels` table states what each level requires. `run_suite.py --level core` runs `core` together
with the levels it requires, which is what a port declaring `core` runs.

Start with `vectors/hash/siphash-primitive.json` and `vectors/hash/construction.json`. Every other
vector rests on them, and a port whose SipHash or whose framing is wrong fails everything downstream
in a way that is hard to read.

Then take `vectors/properties/witnesses.json`, case `sample-generator`, which fixes the key sample
`PROP-006` requires every sampled bound to be drawn from. A port that disagrees there is drawing
different keys, and its balance results say nothing.

## Regenerating

```sh
cd generator
./run.sh
```

See [`generator/README.md`](generator/README.md) for what the script verifies before it generates
anything, and for how to rerun the collision search that the tie-break vectors rest on.

## Editing

Files under `topologies/`, `vectors/`, `properties/`, and `scenarios/` are generated. A hand edit to
one is lost at the next regeneration. A change to what the suite covers is a change to
`generator/topologies.py` or to one of the generator scripts.
