#!/usr/bin/env python3
"""Rebuild `conformance/manifest.json` by scanning the generated tree.

The manifest lists every vector file, its conformance level, the placement strategy surfaces it
exercises, what it covers, its requirement identifiers, and the digest of every topology document
the suite ships.  It also carries the level table, the strategy surface list, and the suite
revision, so a harness reads the suite's structure rather than encoding it.  It is rebuilt from
the files themselves rather than accumulated across the generator scripts, so a file that no
script claims still appears and a file a script claims but did not write is reported as missing.

    python3 build_manifest.py [--out <conformance root>]
"""

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from sharder_ref.jcs import digest as jcs_digest     # noqa: E402

# The conformance levels of `docs/design/30-conformance.md`, in the order that document states
# them.  Every vector file, scenario, and property names one of them, and the manifest publishes
# the table so that a harness reads the level structure rather than encoding it.
LEVELS = [
    ("hash", [], "the hash primitive and the framed construction every other level rests on"),
    ("place", ["hash"], "the placement function over a topology the file carries already valid"),
    ("core", ["place"], "the document pipeline, the snapshot lifecycle, and what is reported"),
    ("failover", ["core"], "the health view, the attempt sequence, and the retry budget"),
    ("readAffinity", ["core"], "`routeForRead` and the bounded reordering of the replica prefix"),
    ("fencing", ["failover"], "the fencing token, the recipient verdict, and the redirect walk"),
    ("migration", ["failover"], "the handoff coordinator and rate control"),
]

LEVEL_NAMES = [name for name, _, _ in LEVELS]

# The placement strategy surfaces of `10-specification.md`, which `CORE-110` makes selectable.  A
# vector file, and a case that names a document of its own, is run by a port exposing every
# strategy surface the documents it names carry, and by no other.
STRATEGY_SURFACES = ["directory", "rendezvous", "ring", "slot"]

TOPOLOGY_REFERENCE = re.compile(r"^topologies/[A-Za-z0-9./-]+\.topology\.json$")


def level_of(payload, relative):
    """Read the level a generated file names, refusing a file that names none."""
    level = payload.get("level")
    if level is None:
        raise SystemExit("%s: no conformance level; the generator that writes it must name one"
                         % relative)
    if level not in LEVEL_NAMES:
        raise SystemExit("%s: unknown conformance level %r" % (relative, level))
    return level


def topology_references(value, found):
    """Every topology document path a payload names, at any depth."""
    if isinstance(value, str):
        if TOPOLOGY_REFERENCE.match(value):
            found.add(value)
    elif isinstance(value, dict):
        for member in value.values():
            topology_references(member, found)
    elif isinstance(value, list):
        for member in value:
            topology_references(member, found)
    return found


def strategies_of(paths, topologies):
    """The strategy surfaces the documents at these paths carry.

    A file's own `strategies` are the surfaces the documents it names outside its cases carry, and
    a port exposing all of them runs the file.  Its `caseStrategies` are the surfaces its cases
    name for themselves, and a port runs the cases whose own surfaces it exposes, which is what
    lets a file mixing documents of four kinds be run by a port exposing one.

    A document naming no strategy, or one outside the surfaces `CORE-110` makes selectable, adds
    nothing: an invalid document under `topologies/invalid/` is refused by every port whatever it
    exposes, so it constrains none.
    """
    kinds = set()
    for name in paths:
        kind = topologies.get(name, {}).get("strategy")
        if kind in STRATEGY_SURFACES:
            kinds.add(kind)
    return sorted(kinds)


def check_inline_documents(payload, relative, root: Path, references):
    """Refuse a `place` file that does not carry, unchanged, every document it names.

    `30-conformance.md` states that a file at `place` is run by a port with no document pipeline,
    which reads the document out of the file rather than out of `conformance/topologies/`.  The
    two copies are written in one regeneration from one source, so a difference between them is a
    generator defect rather than a drift a maintainer is asked to reconcile.
    """
    inline = payload.get("topologyDocuments", {})
    for name in sorted(references):
        if name not in inline:
            raise SystemExit("%s: names %s and does not carry it inline; a file at `place` "
                             "carries every document it names" % (relative, name))
        if inline[name] != json.loads((root / name).read_text()):
            raise SystemExit("%s: the inline copy of %s differs from the document it was taken "
                             "from" % (relative, name))
    for name in sorted(inline):
        if name not in references:
            raise SystemExit("%s: carries %s inline and names it nowhere" % (relative, name))


def scan_vectors(root: Path, topologies):
    entries = []
    for path in sorted((root / "vectors").rglob("*.json")):
        payload = json.loads(path.read_text())
        if payload.get("kind") == "index":
            continue
        relative = str(path.relative_to(root))
        level = level_of(payload, relative)
        cases = payload.get("cases", [])
        requirements = set(payload.get("requirements", []))
        for case in cases:
            requirements.update(case.get("requirements", []))
        outer = {name: value for name, value in payload.items() if name != "cases"}
        file_references = topology_references(outer, set())
        case_references = topology_references(cases, set())
        references = file_references | case_references
        if level == "place":
            check_inline_documents(payload, relative, root, references)
        elif "topologyDocuments" in payload:
            raise SystemExit("%s: carries documents inline at level %r; only `place` does"
                             % (relative, level))
        entries.append({
            "file": relative,
            "vectorSet": payload.get("vectorSet", path.stem),
            "kind": payload.get("kind", "unknown"),
            "level": level,
            "strategies": strategies_of(file_references, topologies),
            "caseStrategies": strategies_of(case_references, topologies),
            "description": payload.get("description", ""),
            "topology": payload.get("topology"),
            "caseCount": len(cases),
            "requirements": sorted(requirements),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        })
    return entries


def level_rows(vectors, scenarios, properties):
    """Count what each level carries, and the requirement identifiers it names."""
    rows = []
    for name, requires, surface in LEVELS:
        files = [e for e in vectors if e["level"] == name]
        runs = [e for e in scenarios if e["level"] == name]
        claims = [e for e in properties if e["level"] == name]
        requirements = set()
        for entry in files + runs + claims:
            requirements.update(entry["requirements"])
        rows.append({
            "level": name,
            "requires": requires,
            "surface": surface,
            "vectorFiles": len(files),
            "vectorCases": sum(e["caseCount"] for e in files),
            "scenarios": len(runs),
            "properties": len(claims),
            "requirementsNamed": len(requirements),
        })
    return rows


def scan_topologies(root: Path):
    topologies = {}
    for path in sorted((root / "topologies").rglob("*.topology.json")):
        document = json.loads(path.read_text())
        relative = str(path.relative_to(root))
        topologies[relative] = {
            "topologyId": document.get("topologyId"),
            "epoch": document.get("epoch"),
            "strategy": document.get("strategy", {}).get("kind"),
            "nodeCount": len(document.get("nodes", [])),
            "digest": jcs_digest(document),
            "valid": "invalid" not in relative,
        }
    return topologies


def scan_scenarios(root: Path):
    index = root / "scenarios/index.json"
    if not index.exists():
        return []
    scenarios = json.loads(index.read_text())["scenarios"]
    for entry in scenarios:
        level_of(entry, entry["file"])
    return scenarios


# The suite revision covers what a port runs and what a declaration means by a level: every file
# under these directories, the level table, and the strategy surfaces.  `coverage.json` and
# `manifest.json` are derived from those files rather than run, so neither is in the basis.
REVISION_TREES = ["vectors", "topologies", "scenarios", "properties"]


def revision_of(root: Path, levels, strategies):
    """Compute the suite revision from the suite's own content.

    The revision names a suite, and `30-conformance.md` has a port declare its levels against one.
    It is the SHA-256 of the RFC 8785 canonical form of the basis below, computed the way a
    topology digest is computed, so it changes when any file a port runs changes, when the level
    table changes, and when the strategy surfaces change, and never otherwise.  Nothing is bumped
    by hand.
    """
    files = {}
    for tree in REVISION_TREES:
        for path in sorted((root / tree).rglob("*.json")):
            files[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
    basis = {
        "files": files,
        "levels": [{"level": name, "requires": requires} for name, requires, _ in LEVELS],
        "strategySurfaces": strategies,
    }
    return {
        "id": jcs_digest(basis),
        "basis": "sha256 of the RFC 8785 canonical form of the file digests, the level table, "
                 "and the strategy surfaces",
        "fileCount": len(files),
        "trees": REVISION_TREES,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(HERE.parent))
    args = parser.parse_args()
    root = Path(args.out)

    topologies = scan_topologies(root)
    vectors = scan_vectors(root, topologies)
    scenarios = scan_scenarios(root)

    properties_path = root / "properties/properties.json"
    properties = json.loads(properties_path.read_text()) if properties_path.exists() else \
        {"properties": [], "requirementsCovered": []}

    requirements = set()
    for entry in vectors:
        requirements.update(entry["requirements"])
    for entry in scenarios:
        requirements.update(entry["requirements"])
    requirements.update(properties["requirementsCovered"])

    missing = [e["file"] for e in vectors
               if e["topology"] and not (root / e["topology"]).exists()]

    levels = level_rows(vectors, scenarios, properties["properties"])

    manifest = {
        "suite": "sharder conformance suite",
        "revision": revision_of(root, levels, STRATEGY_SURFACES),
        "specification": "docs/design/10-specification.md",
        "design": "docs/design/30-conformance.md",
        "generator": "conformance/generator",
        "counts": {
            "vectorFiles": len(vectors),
            "vectorCases": sum(e["caseCount"] for e in vectors),
            "topologyDocuments": len(topologies),
            "scenarios": len(scenarios),
            "scenarioSteps": sum(e["stepCount"] for e in scenarios),
            "properties": len(properties["properties"]),
            "requirementsNamed": len(requirements),
        },
        "requirementsNamed": sorted(requirements),
        "levels": levels,
        "strategySurfaces": STRATEGY_SURFACES,
        "missingTopologyReferences": missing,
        "topologies": topologies,
        "vectorFiles": vectors,
        "scenarios": scenarios,
        "properties": [{"id": p["id"], "name": p["name"], "level": p["level"],
                        "requirements": p["requirements"], "witness": p["witness"]}
                       for p in properties["properties"]],
    }
    (root / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")

    counts = manifest["counts"]
    print("manifest: %d vector files (%d cases), %d topologies, %d scenarios (%d steps), "
          "%d properties, %d requirement identifiers"
          % (counts["vectorFiles"], counts["vectorCases"], counts["topologyDocuments"],
             counts["scenarios"], counts["scenarioSteps"], counts["properties"],
             counts["requirementsNamed"]))
    print("  revision %s over %d files"
          % (manifest["revision"]["id"], manifest["revision"]["fileCount"]))
    for row in levels:
        print("  %-13s %2d files (%3d cases), %2d scenarios, %2d properties, %3d requirements"
              % (row["level"], row["vectorFiles"], row["vectorCases"], row["scenarios"],
                 row["properties"], row["requirementsNamed"]))
    if missing:
        print("  vector files naming a topology that is not present: %s" % ", ".join(missing))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
