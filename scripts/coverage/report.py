"""Run each service's tests with coverage and report line coverage per service.

Python services report through Cobertura XML (pytest-cov), Java services through
the JaCoCo CSV report; both are reduced to covered/total lines so the monorepo can
be judged against a single threshold.
"""

import argparse
import csv
import os
import subprocess
import sys
import xml.etree.ElementTree as ElementTree

from config import REPO_ROOT, load_config, services


def _cobertura_lines(report_path):
    root = ElementTree.parse(report_path).getroot()
    covered = total = 0
    for line in root.iter("line"):
        total += 1
        covered += int(line.get("hits", "0")) > 0
    return covered, total


def _jacoco_lines(report_path):
    covered = total = 0
    with open(report_path, encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            covered += int(row["LINE_COVERED"])
            total += int(row["LINE_COVERED"]) + int(row["LINE_MISSED"])
    return covered, total


def run_tests(service):
    cwd = os.path.join(REPO_ROOT, service.get("command_cwd", service["path"]))
    print(f"--- {service['name']}: {service['command']}", flush=True)
    return subprocess.run(service["command"], shell=True, cwd=cwd, check=False).returncode


def read_coverage(service):
    report_path = os.path.join(REPO_ROOT, service["path"], service["report"])
    if not os.path.exists(report_path):
        return None
    if report_path.endswith(".csv"):
        return _jacoco_lines(report_path)
    return _cobertura_lines(report_path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service", action="append", dest="service_names")
    parser.add_argument("--no-run", action="store_true", help="read existing reports instead of running tests")
    parser.add_argument("--min", type=float, help="threshold to enforce (defaults to the configured minimum)")
    args = parser.parse_args()

    minimum = args.min if args.min is not None else load_config()["min_coverage"]
    selected = services(args.service_names)
    failures = []
    rows = []
    repo_covered = repo_total = 0

    for service in selected:
        if not args.no_run and run_tests(service) != 0:
            failures.append(f"{service['name']}: tests failed")
        measured = read_coverage(service)
        if measured is None:
            rows.append((service["name"], None))
            failures.append(f"{service['name']}: no coverage report at {service['report']}")
            continue
        covered, total = measured
        repo_covered += covered
        repo_total += total
        percent = 100.0 * covered / total if total else 0.0
        rows.append((service["name"], (percent, covered, total)))
        if percent < minimum:
            failures.append(f"{service['name']}: {percent:.1f}% line coverage is below {minimum:.0f}%")

    print(f"\n{'service':<22}{'lines':>14}{'coverage':>11}")
    for name, measured in rows:
        if measured is None:
            print(f"{name:<22}{'-':>14}{'no report':>11}")
        else:
            percent, covered, total = measured
            print(f"{name:<22}{f'{covered}/{total}':>14}{f'{percent:.1f}%':>11}")
    if repo_total:
        print(f"{'TOTAL':<22}{f'{repo_covered}/{repo_total}':>14}{f'{100.0 * repo_covered / repo_total:.1f}%':>11}")

    if failures:
        print("\n" + "\n".join(failures))
        sys.exit(1)


if __name__ == "__main__":
    main()
