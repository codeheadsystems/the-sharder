#!/usr/bin/env python3
"""Check that `rho_search`'s hash construction matches the Python reference.

The collision searcher carries its own SipHash and its own framing so that it can run at C
speed.  A divergence between the two would make the searcher return values that are not
collisions under the specification, so `--probe` prints the searcher's output over a fixed ladder
of states and this script recomputes each through `sharder_ref`.

Run it before `search_ties.py`.  Exit status 0 means the two constructions agree.
"""

import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from sharder_ref import hashing  # noqa: E402

RENDEZVOUS_KEY = b"tie-probe"
SLOT_INDEX = 0
RANGE_SHARD = b"r0"

MODES = {
    "ring": (["ring"], lambda ident: hashing.ring_token(hashing.ZERO_SEED, ident, 0)),
    "rendezvous": (["rendezvous", RENDEZVOUS_KEY.hex()],
                   lambda ident: hashing.rv_score(hashing.ZERO_SEED, RENDEZVOUS_KEY, ident, 0)),
    "slot": (["slot", str(SLOT_INDEX)],
             lambda ident: hashing.slot_score(hashing.ZERO_SEED, SLOT_INDEX, ident, 0)),
    "range": (["range", RANGE_SHARD.decode()],
              lambda ident: hashing.range_score(hashing.ZERO_SEED, RANGE_SHARD, ident, 0)),
}


def main():
    binary = HERE / "rho_search"
    if not binary.exists():
        print("rho_search is not built; run: cc -O2 -o rho_search rho_search.c")
        return 1

    failures = 0
    checked = 0
    for mode, (args, compute) in MODES.items():
        output = subprocess.run([str(binary)] + args + ["--probe"],
                                capture_output=True, check=True, text=True).stdout
        for line in output.strip().splitlines():
            identity, value = line.split()
            expected = compute(identity.encode("ascii"))
            checked += 1
            if "%016x" % expected != value:
                failures += 1
                print("  %s %s: searcher %s, reference %016x" % (mode, identity, value, expected))

    print("rho_search construction check: %d probes, %d mismatches" % (checked, failures))
    if failures:
        print("VERIFICATION FAILED")
        return 1
    print("VERIFICATION PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
