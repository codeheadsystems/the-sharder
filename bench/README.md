# Benchmarks

Measurement sources that a decision record cites. Each one is standalone, builds with a system
compiler, and depends on nothing in this repository, so a figure quoted in a record can be
reproduced rather than taken on trust.

| Source | Cited by |
|---|---|
| [`hash-short-input.c`](hash-short-input.c) | [`../docs/design/adr/0001-hash-function-and-key-encoding.md`](../docs/design/adr/0001-hash-function-and-key-encoding.md) |

Nothing here is part of the library, the conformance suite, or any published artifact. The
conformance suite's own generator lives under
[`../conformance/generator/`](../conformance/generator/) and is not a benchmark.

Build and run:

```
cc -O2 -o hash-short-input hash-short-input.c
./hash-short-input
```

A compiled binary is not committed.
