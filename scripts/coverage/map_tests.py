"""Attribute existing tests to the units they exercise and record it in the manifest.

Python attribution is exact: the suite is re-run with ``--cov-context=test`` and each
unit claims the tests whose coverage context touched its line range. Java has no
per-test coverage attribution, so its units claim the test methods that name them.

Run this after adding tests so the manifest reflects reality; ``manifest.py sync``
preserves the attributions and only refreshes fingerprints.
"""

import argparse
import json
import os
import re
import subprocess

from config import REPO_ROOT, load_manifest, save_manifest, services
from manifest import sync
from unit_index import JAVA_METHOD, _java_body, index_service, strip_java_comments


DUMP_CONTEXTS = """
import json, coverage
data = coverage.CoverageData()
data.read()
print(json.dumps({
    path: {str(line): sorted(c for c in contexts if c)
           for line, contexts in data.contexts_by_lineno(path).items()}
    for path in data.measured_files()
}))
"""


def _python_contexts(service):
    """{repo-relative source file: {line: {test ids}}} from a context-recording run."""
    service_dir = os.path.join(REPO_ROOT, service["path"])
    subprocess.run(
        f"{service['command']} --cov-context=test",
        shell=True,
        cwd=service_dir,
        check=False,
    )
    dump = subprocess.run(
        ["uv", "run", "python", "-c", DUMP_CONTEXTS],
        cwd=service_dir,
        capture_output=True,
        text=True,
        check=False,
    )
    if dump.returncode != 0:
        print(f"{service['name']}: no coverage data to attribute ({dump.stderr.strip().splitlines()[-1:]})")
        return {}
    return {
        os.path.relpath(path, REPO_ROOT): {int(line): set(contexts) for line, contexts in lines.items()}
        for path, lines in json.loads(dump.stdout).items()
    }


def _python_attribution(service, indexed):
    per_file = _python_contexts(service)
    attribution = {}
    for unit, data in indexed.items():
        path = data["unit"].split("::")[0]
        tests = set()
        for line, contexts in per_file.get(path, {}).items():
            if data["line"] <= line <= data["end_line"]:
                tests.update(context.split("|")[0] for context in contexts)
        if tests:
            attribution[unit] = sorted(tests)
    return attribution


def _java_test_methods(service):
    """[(test id, body)] for every test method of the service."""
    tests_root = os.path.join(REPO_ROOT, service["path"], service["tests"])
    collected = []
    for dirpath, _, filenames in os.walk(tests_root):
        for filename in filenames:
            if not filename.endswith(".java"):
                continue
            absolute = os.path.join(dirpath, filename)
            relative = os.path.relpath(absolute, REPO_ROOT)
            with open(absolute, encoding="utf-8") as handle:
                source = strip_java_comments(handle.read())
            for match in JAVA_METHOD.finditer(source):
                body = _java_body(source, source.index("{", match.end() - 1))
                collected.append((f"{relative}::{match.group('name')}", body))
    return collected


def _java_attribution(service, indexed):
    test_methods = _java_test_methods(service)
    attribution = {}
    for unit, data in indexed.items():
        method = data["signature"].split("(")[0]
        pattern = re.compile(rf"\b{re.escape(method)}\s*\(")
        tests = sorted(test_id for test_id, body in test_methods if pattern.search(body))
        if tests:
            attribution[unit] = tests
    return attribution


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service", action="append", dest="service_names")
    parser.add_argument("--dry-run", action="store_true", help="print the attribution without writing")
    args = parser.parse_args()

    manifest = load_manifest()
    for service in services(args.service_names):
        indexed = index_service(service)
        sync(service, indexed, manifest)
        if service["language"] == "python":
            attribution = _python_attribution(service, indexed)
        else:
            attribution = _java_attribution(service, indexed)
        print(f"{service['name']}: attributed tests to {len(attribution)}/{len(indexed)} units")
        for unit, tests in sorted(attribution.items()):
            if args.dry_run:
                print(f"  {unit} <- {', '.join(tests)}")
            else:
                manifest["services"][service["name"]]["units"][unit]["tests"] = tests
    if not args.dry_run:
        save_manifest(manifest)


if __name__ == "__main__":
    main()
