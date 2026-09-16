#!/bin/sh
# Regenerate the whole conformance suite.
#
# Verification runs first and the generators run only if it passes, so a vector file cannot be
# written by a hash that has not been checked against the published reference vectors.
#
#   ./run.sh              regenerate everything except the collision search
#   ./run.sh --search     run the collision search first, which takes tens of minutes per mode
#
# The collision search results are kept under `generator/collisions/` and are inputs to the
# determinism vectors.  They change only if the hash construction changes.

set -e
cd "$(dirname "$0")"

echo "== verifying SipHash-2-4 against the published vectors and OpenSSL"
python3 verify_siphash.py

if [ "$1" = "--search" ]; then
    echo "== building and verifying the collision searcher"
    cc -O2 -o rho_search rho_search.c
    python3 verify_rho.py
    echo "== searching for collisions; this takes tens of minutes per mode"
    mkdir -p collisions
    ./rho_search keyHash                                 > collisions/keyHash.txt &
    ./rho_search ring                                    > collisions/ring.txt &
    ./rho_search rendezvous 7469652d70726f6265           > collisions/rendezvous.txt &
    ./rho_search slot 0                                  > collisions/slot.txt &
    ./rho_search range r0                                > collisions/range.txt &
    wait
fi

echo "== generating golden vectors"
python3 generate.py

echo "== generating formula and lineage vectors"
python3 generate_formulas.py

echo "== generating the remaining vector sets"
python3 generate_extra.py

echo "== generating property definitions"
python3 property_definitions.py

echo "== generating property witnesses; this takes a few minutes"
python3 generate_properties.py

echo "== generating simulation scenarios"
python3 generate_scenarios.py

echo "== validating the generated topologies against the published JSON Schema"
python3 verify_schema.py

echo "== rebuilding the manifest"
python3 build_manifest.py

echo "== computing requirement coverage"
python3 coverage.py --check

echo "== running the reference driver over the generated suite"
python3 ../driver/python/run_suite.py

echo "== done"
