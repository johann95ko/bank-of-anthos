"""Shared access to the repository's coverage service registry."""

import json
import os
from fnmatch import fnmatch

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CONFIG_PATH = os.path.join(REPO_ROOT, ".devin", "coverage-config.json")
MANIFEST_PATH = os.path.join(REPO_ROOT, ".devin", "test-coverage-manifest.json")


def load_config():
    with open(CONFIG_PATH, encoding="utf-8") as handle:
        return json.load(handle)


def services(names=None):
    config = load_config()
    selected = config["services"]
    if names:
        by_name = {service["name"]: service for service in selected}
        unknown = sorted(set(names) - set(by_name))
        if unknown:
            raise SystemExit(f"unknown service(s): {', '.join(unknown)}")
        selected = [by_name[name] for name in names]
    return selected


def service_for_path(path, config=None):
    """Return the service owning a repo-relative path, longest prefix first."""
    config = config or load_config()
    candidates = [s for s in config["services"] if path.startswith(s["path"] + "/")]
    return max(candidates, key=lambda s: len(s["path"]), default=None)


def source_files(service):
    """Repo-relative source files of a service, honouring its include/exclude globs."""
    root = os.path.join(REPO_ROOT, service["path"])
    found = []
    for pattern in service["sources"]:
        for dirpath, _, filenames in os.walk(root):
            for filename in filenames:
                absolute = os.path.join(dirpath, filename)
                relative = os.path.relpath(absolute, root)
                if not fnmatch(relative, pattern):
                    continue
                if any(fnmatch(relative, ignored) for ignored in service["exclude"]):
                    continue
                found.append(os.path.relpath(absolute, REPO_ROOT))
    return sorted(set(found))


def load_manifest():
    if not os.path.exists(MANIFEST_PATH):
        return {"version": 1, "min_coverage": load_config()["min_coverage"], "services": {}}
    with open(MANIFEST_PATH, encoding="utf-8") as handle:
        return json.load(handle)


def save_manifest(manifest):
    with open(MANIFEST_PATH, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2, sort_keys=True)
        handle.write("\n")
