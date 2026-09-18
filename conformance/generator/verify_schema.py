#!/usr/bin/env python3
"""Validate every topology document in the suite against the published JSON Schema.

`TOPO-001` puts schema validation at stage 2 of the load pipeline, before the semantic rules the
reference implements.  This script runs the real schema over every document the suite ships, so a
document that the reference accepts and the schema refuses is caught here rather than in a port.

A document under `topologies/invalid/` is expected to fail a semantic rule rather than a schema
rule, so it is reported separately: a document that fails the schema is not testing what its
validation case says it tests.

    python3 verify_schema.py [--root <conformance root>]

Needs `jsonschema`.  Where it is absent the script reports that and exits 0, so that
regeneration does not depend on it.
"""

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCHEMA = HERE.parent.parent / "docs/design/topology-v1.schema.json"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=str(HERE.parent))
    args = parser.parse_args()
    root = Path(args.root)

    try:
        import jsonschema
    except ImportError:
        print("jsonschema is not installed; schema validation skipped")
        return 0

    schema = json.loads(SCHEMA.read_text())
    validator = jsonschema.Draft202012Validator(schema)

    valid_ok = valid_bad = invalid_semantic = invalid_schema = 0
    failures = []
    for path in sorted(root.joinpath("topologies").rglob("*.topology.json")):
        document = json.loads(path.read_text())
        errors = sorted(validator.iter_errors(document), key=lambda e: list(e.path))
        relative = path.relative_to(root)
        is_invalid_case = "invalid" in relative.parts
        if is_invalid_case:
            if errors:
                invalid_schema += 1
            else:
                invalid_semantic += 1
            continue
        if errors:
            valid_bad += 1
            failures.append("%s: %s" % (relative, errors[0].message))
        else:
            valid_ok += 1

    print("schema validation against %s" % SCHEMA.name)
    print("  documents the suite routes against: %d pass, %d fail" % (valid_ok, valid_bad))
    print("  documents under topologies/invalid: %d fail a semantic rule only, "
          "%d also fail the schema" % (invalid_semantic, invalid_schema))
    for failure in failures:
        print("    " + failure)
    if failures:
        print("VERIFICATION FAILED")
        return 1
    print("VERIFICATION PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
