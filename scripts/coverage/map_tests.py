"""Attribute existing tests to the units they exercise and record it in the manifest.

Python attribution is exact: the suite is re-run with ``--cov-context=test`` and each
unit claims the tests whose coverage context touched its line range. Java has no
per-test coverage attribution, so its units claim the test methods that name them.

Run this after adding tests so the manifest reflects reality; ``manifest.py sync``
preserves the attributions and only refreshes fingerprints.
"""

import argparse
import glob
import json
import os
import re
import subprocess
import sys

from config import REPO_ROOT, load_manifest, save_manifest, services
from manifest import sync
from report import command_argv, command_cwd
from unit_index import JAVA_METHOD, _java_body, index_service, strip_java_comments

JAVA_TEST_ANNOTATION = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b")


class AttributionError(RuntimeError):
    pass


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
    """{repo-relative source file: {line: {test ids}}} from a context-recording run.

    Stale coverage data is deleted first and a failing suite aborts attribution, so a
    partial run can never be mistaken for a fresh one.
    """
    service_dir = command_cwd(service)
    for stale in glob.glob(os.path.join(service_dir, ".coverage*")):
        os.remove(stale)
    run = subprocess.run(command_argv(service, ["--cov-context=test"]), cwd=service_dir, check=False)
    if run.returncode != 0:
        raise AttributionError(f"{service['name']}: test command failed, refusing to attribute tests")
    dump = subprocess.run(
        ["uv", "run", "python", "-c", DUMP_CONTEXTS],
        cwd=service_dir,
        capture_output=True,
        text=True,
        check=False,
    )
    if dump.returncode != 0:
        raise AttributionError(f"{service['name']}: could not read coverage contexts\n{dump.stderr.strip()}")
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
    """[(test id, body)] for the JUnit test methods of the service.

    Only annotated test methods count: lifecycle hooks, helpers, nested configuration
    callbacks and control-flow keywords the regex also matches would otherwise claim
    production units by name.
    """
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
            for annotation in JAVA_TEST_ANNOTATION.finditer(source):
                match = JAVA_METHOD.search(source, annotation.end())
                if match is None:
                    continue
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
        try:
            if service["language"] == "python":
                attribution = _python_attribution(service, indexed)
            else:
                attribution = _java_attribution(service, indexed)
        except AttributionError as error:
            sys.exit(str(error))
        print(f"{service['name']}: attributed tests to {len(attribution)}/{len(indexed)} units")
        for unit, tests in sorted(attribution.items()):
            if args.dry_run:
                print(f"  {unit} <- {', '.join(tests)}")
        if not args.dry_run:
            for unit, recorded in manifest["services"][service["name"]]["units"].items():
                recorded["tests"] = attribution.get(unit, [])
    if not args.dry_run:
        save_manifest(manifest)


if __name__ == "__main__":
    main()
