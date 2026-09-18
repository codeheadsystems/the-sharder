# sharder conformance suite

Language-neutral data files that hold every port of the sharder library to the same routing
decisions. The design is
[`../docs/design/30-conformance.md`](../docs/design/30-conformance.md), which gives the vector file
format, the driver contract, the conformance levels, and the rule by which a port declares
conformance.

Every expected value here is computed by the reference implementation in `generator/`. None is
written by hand.

## Contents

| Path | Contents |
|---|---|
| `manifest.json` | the suite revision, the conformance levels, the strategy surfaces, every vector file with its level, coverage, and digest, and every topology digest |
| `coverage.json` | requirement coverage, computed from `10-specification.md` |
| `topologies/` | the topology documents the vectors route against |
| `topologies/invalid/` | documents that fail a load-time rule |
| `vectors/` | the golden vectors, grouped by subject |
| `properties/properties.json` | the property definitions, with their bounds and sample sizes |
| `scenarios/` | the simulation scenarios, with `index.json` listing them |
| `generator/` | the reference implementation and the generator scripts |
| `driver/python/` | a driver written against the reference, as a worked example |
| `declarations/` | one conformance declaration per port, checked against the manifest |

## Running the suite

A port writes a driver that reads `manifest.json`, dispatches on each vector file's `kind`, and
compares each case's result to its `expect` object field by field. The driver contract is in
[`../docs/design/30-conformance.md`](../docs/design/30-conformance.md), and
[`driver/python/run_suite.py`](driver/python/run_suite.py) is a worked example of it.

Each vector file and each scenario carries the conformance level it belongs to, and the manifest's
`levels` table states what each level requires. `run_suite.py --level core` runs `core` together
with the levels it requires, which is what a port declaring `core` runs.

Each vector file also carries the placement strategy surfaces the documents it names carry.
`run_suite.py --strategy rendezvous,directory` runs the files and cases a port exposing those two
surfaces runs, and naming none runs every surface the manifest lists.

`manifest.json` carries the suite revision in `revision`, computed from the suite's own content. A
port declares its levels against that revision, and the driver prints the revision it ran.

Start with `vectors/hash/siphash-primitive.json` and `vectors/hash/construction.json`. Every other
vector rests on them, and a port whose SipHash or whose framing is wrong fails everything downstream
in a way that is hard to read.

Then run `--level place`, which is the placement engine over topologies the vector files carry
already valid. It needs no JSON reader for the topology format, no canonical form, no digest, and no
provider, so a port reaches it before it writes a document pipeline.

Then take `vectors/properties/witnesses.json`, case `sample-generator`, which fixes the key sample
`PROP-006` requires every sampled bound to be drawn from. A port that disagrees there is drawing
different keys, and its balance results say nothing.

Run `--level scale` last. It routes against two documents of a thousand nodes each, and the values
it asserts are exact, as they are at every other level. What it reports is the cost: `run_suite.py`
prints the wall time of each scale file and the driver's peak resident size after the level table,
and a port publishes both alongside its declaration. The suite asserts no bound for either.

## Declaring conformance

A port publishes what it reaches in `declarations/<port>.json`, which carries the seven things
[`../docs/design/30-conformance.md`](../docs/design/30-conformance.md#declaring-conformance)
requires, and takes the member set stated at
[`#conformance-declaration`](../docs/design/35-port-conventions.md#conformance-declaration).

`generator/verify_declarations.py` checks every declaration against `manifest.json` and the
specification, and `run.sh` runs it. A declaration naming an earlier revision than the manifest
holds is reported as lagging rather than failed, so regenerating the suite reports which ports have
yet to run the new revision.

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

[`../CONTRIBUTING.md`](../CONTRIBUTING.md) gives the whole procedure, including what a change to the
specification obliges a change to the suite to do.
