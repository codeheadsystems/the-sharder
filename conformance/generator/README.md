# Conformance vector generator

Every expected value under `conformance/` is computed by running the reference implementation in
this directory. Nothing is hand-written. A vector file containing an ordering that somebody reasoned
their way to rather than executed is worse than no vector at all, because no correct implementation
can pass it and every implementer assumes the defect is theirs.

The reference is not a binding and is not a library. It exists so that the suite has a second
implementation to compute against, and so that a maintainer can regenerate the suite and diff it.

## Layout

| Path | Contents |
|---|---|
| `sharder_ref/siphash.py` | SipHash-2-4, 64-bit output |
| `sharder_ref/hashing.py` | the framed, domain-tagged construction of `HASH-020` to `HASH-032` |
| `sharder_ref/jcs.py` | RFC 8785 canonical form and the SHA-256 topology digest |
| `sharder_ref/topology.py` | document defaults, semantic validation, and the snapshot |
| `sharder_ref/transforms.py` | the three key transforms |
| `sharder_ref/placement.py` | the four core placement strategies |
| `sharder_ref/routing.py` | overrides, the preference list builder, spread degradation |
| `sharder_ref/health.py` | the built-in health state machine |
| `sharder_ref/fencing.py` | recipient verdicts and the redirect walk |
| `sharder_ref/handoff.py` | the handoff state machine and the ownership delta |
| `sharder_ref/formulas.py` | the integer formulas the specification states in closed form |
| `sharder_ref/sample.py` | the deterministic key sample |
| `rho_search.c` | the 64-bit collision search the tie-break vectors need |

| Script | What it does |
|---|---|
| `run.sh` | verifies, generates, rebuilds the manifest, and runs the driver |
| `verify_siphash.py` | checks SipHash against the published vectors and against OpenSSL |
| `verify_rho.py` | checks the searcher's construction against the Python reference |
| `verify_schema.py` | validates every topology against the published JSON Schema |
| `generate.py` | the strategy, transform, digest, validation, and determinism vectors |
| `generate_formulas.py` | the integer formula vectors |
| `generate_extra.py` | the error taxonomy, defaults, comparator, and delta vectors |
| `property_definitions.py` | the property definitions |
| `generate_properties.py` | the property witnesses |
| `generate_scenarios.py` | the simulation scenarios |
| `build_manifest.py` | rebuilds `manifest.json`, with the suite revision, by scanning the tree |
| `coverage.py` | computes `coverage.json` from the specification |
| `verify_withdrawals.py` | checks that no withdrawn identifier is restated, cited, or named |

## Regenerating

```sh
./run.sh
```

`run.sh` verifies the hash before it generates anything, then runs each generator, rebuilds the
manifest, and prints requirement coverage. It exits non-zero if verification fails, if a vector
file names a topology that is not present, if a file at the `place` level does not carry the
documents it names, or if the suite names a requirement identifier the specification does not state.

Regenerating is expected to produce a byte-identical tree. A maintainer who changes the reference
runs `./run.sh` and reads the diff; a diff in a vector file that the change was not meant to touch
is the signal the script exists to produce.

The property witnesses take a few minutes, because a balance witness evaluates a hundred thousand
keys against every node of a topology. `python3 generate_properties.py --quick` uses sample sizes
below the specification's preconditions and is for checking the script rather than for publishing.

## Verifying SipHash against the published vectors

```sh
python3 verify_siphash.py
```

Two independent checks run over the same 64 inputs, which are the reference inputs of the SipHash
paper: key `000102030405060708090a0b0c0d0e0f`, and message `0001...(i-1)` for `i` from 0 to 63.

1. The 64 published outputs, held in `PAPER_VECTORS_LE` as the paper prints them, eight octets
   least significant first. These are the `vectors_sip64` table of the reference C implementation
   accompanying Aumasson and Bernstein, "SipHash: a fast short-input PRF".
2. OpenSSL 3's `SIPHASH` message authentication code at `size:8`, which is an implementation
   nobody in this repository wrote. OpenSSL prints the eight output octets least significant
   first, so the script reads its output little-endian.

Both checks pass at 64 of 64. The framed construction above SipHash is checked separately by the
`framedMessage` field of `conformance/vectors/hash/construction.json`, which carries the octets fed
to SipHash alongside the result, so a port that fails a hash vector can tell a framing defect from
a SipHash defect.

## Searching for collisions

The determinism vectors need cases that reach a tie-break: two node identities deriving one ring
token, and two node identities with one maximum score. Both are 64-bit collisions, so they are
found rather than chosen. A birthday table would need roughly 2^32 stored values; Brent's cycle
detection finds the same collision in constant memory, at roughly 2^33 hash evaluations per mode.

```sh
cc -O2 -o rho_search rho_search.c
python3 verify_rho.py          # the searcher's hash must match the Python reference
./run.sh --search              # tens of minutes per mode, three modes in parallel
```

`verify_rho.py` compares the searcher's construction against `sharder_ref` over a fixed ladder of
states, because the searcher carries its own SipHash and its own framing in order to run at C
speed. `generate.py` recomputes every collision through the Python reference before it writes a
vector, and drops a collision that does not verify.

The search results live in `collisions/`. They are inputs to the suite and change only if the hash
construction changes, so `run.sh` without `--search` reuses them. `generate.py` builds a tie-break
case for each mode it finds a result for and omits the rest, so a missing result costs a vector
rather than failing the run.

`collisions/` holds `keyHash`, `ring`, and `rendezvous`, which are the modes the tie-break vectors
need.

## Reference driver

[`../driver/python/run_suite.py`](../driver/python/run_suite.py) drives the generated suite against
the reference. It is the worked example a port copies, and it catches a vector file whose shape has
drifted from the driver contract. It does not verify the suite, because it runs the same reference
that computed the expectations; independent verification is a second port.

## Reference implementation caveats

The reference implements placement, replication, spread, fencing, health, and the handoff state
machine. It does not implement the provider contract, the metrics surface, the
explain record, the executor model, or the concurrency requirements of `CORE-050` to `CORE-065`,
because none of those has an output a language-neutral vector can carry.

The reference implements the specification as written. The defects it surfaced while being built
are repaired in the specification, and the table in
[`../../docs/design/30-conformance.md`](../../docs/design/30-conformance.md) joins each to the
requirement that repairs it.
