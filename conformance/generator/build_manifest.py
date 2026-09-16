#!/usr/bin/env python3
"""Rebuild `conformance/manifest.json` by scanning the generated tree.

The manifest lists every vector file, what it covers, its requirement identifiers, and the digest
of every topology document the suite ships.  It is rebuilt from the files themselves rather than
accumulated across the generator scripts, so a file that no script claims still appears and a
file a script claims but did not write is reported as missing.

    python3 build_manifest.py [--out <conformance root>]
"""

import argparse
import hashlib
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from sharder_ref.jcs import digest as jcs_digest     # noqa: E402


def scan_vectors(root: Path):
    entries = []
    for path in sorted((root / "vectors").rglob("*.json")):
        payload = json.loads(path.read_text())
        if payload.get("kind") == "index":
            continue
        relative = str(path.relative_to(root))
        cases = payload.get("cases", [])
        requirements = set(payload.get("requirements", []))
        for case in cases:
            requirements.update(case.get("requirements", []))
        entries.append({
            "file": relative,
            "vectorSet": payload.get("vectorSet", path.stem),
            "kind": payload.get("kind", "unknown"),
            "description": payload.get("description", ""),
            "topology": payload.get("topology"),
            "caseCount": len(cases),
            "requirements": sorted(requirements),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        })
    return entries


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
    return json.loads(index.read_text())["scenarios"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(HERE.parent))
    args = parser.parse_args()
    root = Path(args.out)

    vectors = scan_vectors(root)
    topologies = scan_topologies(root)
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

    manifest = {
        "suite": "sharder conformance suite",
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
    if missing:
        print("  vector files naming a topology that is not present: %s" % ", ".join(missing))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
