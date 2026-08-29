"""Reconcile the test-coverage manifest with the code that is actually there.

The manifest records, per service, which unit each test exercises. ``check``
classifies every unit against it so a test-writing run only does the work that is
actually missing:

* ``untested``  - unit is absent from the manifest, or present with no tests: write tests.
* ``stale``     - unit's fingerprint moved since its tests were recorded: review the
                  existing tests against the new signature and intent, do not duplicate.
* ``orphaned``  - manifest entry whose unit no longer exists: drop the entry (and its
                  tests, if the code is gone).
* ``current``   - covered and unchanged: leave alone.
"""

import argparse
import json
import subprocess
import sys

from config import REPO_ROOT, load_manifest, save_manifest, services
from unit_index import index_service


def changed_paths(base_ref):
    diff = subprocess.run(
        ["git", "diff", "--name-only", f"{base_ref}...HEAD"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=True,
    )
    return [line for line in diff.stdout.splitlines() if line]


def classify(service, indexed, manifest, limit_to_paths=None):
    recorded = manifest["services"].get(service["name"], {}).get("units", {})
    if limit_to_paths is not None:
        allowed = set(limit_to_paths)
        indexed = {unit: data for unit, data in indexed.items() if data["unit"].split("::")[0] in allowed}
    result = {"untested": [], "stale": [], "current": [], "orphaned": []}
    for unit, data in sorted(indexed.items()):
        entry = recorded.get(unit)
        if entry is None or not entry.get("tests"):
            result["untested"].append(data)
        elif entry.get("fingerprint") != data["fingerprint"]:
            result["stale"].append({**data, "tests": entry["tests"], "recorded_intent": entry.get("intent", "")})
        else:
            result["current"].append(data)
    if limit_to_paths is None:
        result["orphaned"] = sorted(set(recorded) - set(indexed))
    return result


def sync(service, indexed, manifest):
    """Refresh fingerprints and prune orphans, preserving recorded tests and intent."""
    entry = manifest["services"].setdefault(
        service["name"],
        {"language": service["language"], "path": service["path"], "units": {}},
    )
    entry["language"] = service["language"]
    entry["path"] = service["path"]
    for unit in list(entry["units"]):
        if unit not in indexed:
            del entry["units"][unit]
    for unit, data in indexed.items():
        recorded = entry["units"].setdefault(unit, {"tests": [], "intent": ""})
        recorded["signature"] = data["signature"]
        recorded["fingerprint"] = data["fingerprint"]
        if not recorded.get("intent"):
            recorded["intent"] = data["docstring"]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["check", "sync"])
    parser.add_argument("--service", action="append", dest="service_names")
    parser.add_argument("--base", help="only classify units in files changed since this git ref")
    parser.add_argument("--json", action="store_true", help="emit the full classification as JSON")
    args = parser.parse_args()

    manifest = load_manifest()
    limit_to_paths = changed_paths(args.base) if args.base else None
    report = {}
    for service in services(args.service_names):
        indexed = index_service(service)
        if args.command == "sync":
            sync(service, indexed, manifest)
        report[service["name"]] = classify(service, indexed, manifest, limit_to_paths)

    if args.command == "sync":
        save_manifest(manifest)

    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
        return

    outstanding = 0
    for name, buckets in report.items():
        counts = {bucket: len(items) for bucket, items in buckets.items()}
        outstanding += counts["untested"] + counts["stale"]
        print(f"{name}: {counts['untested']} untested, {counts['stale']} stale, "
              f"{counts['current']} current, {counts['orphaned']} orphaned")
        for data in buckets["untested"]:
            print(f"  untested  {data['unit']}  {data['signature']}")
        for data in buckets["stale"]:
            print(f"  stale     {data['unit']}  {data['signature']}  tests={', '.join(data['tests'])}")
        for unit in buckets["orphaned"]:
            print(f"  orphaned  {unit}")
    if args.command == "check" and outstanding:
        sys.exit(1)


if __name__ == "__main__":
    main()
