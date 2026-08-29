"""Index the testable units (functions and methods) of each service.

A unit is identified by ``<repo-relative file>::<qualified name>`` and carries a
``fingerprint``: a hash of its normalized signature and body. The fingerprint is
what lets the test-coverage automation tell a genuinely new function from one
whose implementation changed (its tests must be reviewed) and from one that only
moved (its tests are still valid).
"""

import argparse
import ast
import hashlib
import json
import os
import re

from config import REPO_ROOT, service_for_path, services, source_files

JAVA_COMMENT = re.compile(r"//[^\n]*|/\*.*?\*/", re.DOTALL)
JAVA_METHOD = re.compile(
    r"(?P<modifiers>(?:(?:public|protected|private|static|final|abstract|synchronized|native|default)\s+)*)"
    r"(?P<returns>[\w$<>\[\],.?\s]+?)\s+"
    r"(?P<name>[\w$]+)\s*\((?P<params>(?:[^()]|\([^()]*\))*)\)\s*"
    r"(?:throws\s+[\w$,.\s]+)?\{"
)
JAVA_TYPE = re.compile(r"\b(?:class|interface|enum|record)\s+(?P<name>[\w$]+)")
JAVA_KEYWORDS = {"if", "for", "while", "switch", "catch", "try", "do", "else", "return", "new", "synchronized"}


def strip_java_comments(source):
    """Blank out comments while preserving line numbering."""
    return JAVA_COMMENT.sub(lambda match: re.sub(r"[^\n]", " ", match.group(0)), source)


def _fingerprint(text):
    return hashlib.sha256(re.sub(r"\s+", " ", text).strip().encode("utf-8")).hexdigest()[:16]


def _python_units(relative_path):
    with open(os.path.join(REPO_ROOT, relative_path), encoding="utf-8") as handle:
        tree = ast.parse(handle.read())
    units = []

    def visit(node, prefix):
        for child in node.body:
            if isinstance(child, ast.ClassDef):
                visit(child, f"{prefix}{child.name}.")
            elif isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)):
                units.append(
                    {
                        "unit": f"{relative_path}::{prefix}{child.name}",
                        "signature": f"{child.name}({', '.join(a.arg for a in child.args.args)})",
                        "decorators": [ast.unparse(d) for d in child.decorator_list],
                        "docstring": (ast.get_docstring(child) or "").strip().split("\n")[0],
                        "line": child.lineno,
                        "end_line": child.end_lineno,
                        "fingerprint": _fingerprint(ast.unparse(child)),
                    }
                )
                visit(child, f"{prefix}{child.name}.")

    visit(tree, "")
    return units


def _java_body(source, opening_brace):
    depth = 0
    for index in range(opening_brace, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[opening_brace : index + 1]
    return source[opening_brace:]


def _java_units(relative_path):
    with open(os.path.join(REPO_ROOT, relative_path), encoding="utf-8") as handle:
        source = strip_java_comments(handle.read())
    units = []
    for match in JAVA_METHOD.finditer(source):
        name = match.group("name")
        if name in JAVA_KEYWORDS or match.group("returns").strip() in {"", "new"}:
            continue
        owner = "Unknown"
        for type_match in JAVA_TYPE.finditer(source, 0, match.start()):
            owner = type_match.group("name")
        params = " ".join(match.group("params").split())
        opening_brace = source.index("{", match.end() - 1)
        body = _java_body(source, opening_brace)
        units.append(
            {
                "unit": f"{relative_path}::{owner}.{name}",
                "signature": f"{name}({params})",
                "decorators": [],
                "docstring": "",
                "line": source.count("\n", 0, match.start()) + 1,
                "end_line": source.count("\n", 0, opening_brace + len(body)) + 1,
                "fingerprint": _fingerprint(f"{match.group('returns')} {name}({params}){body}"),
            }
        )
    return units


def index_service(service):
    units = []
    for relative_path in source_files(service):
        if service["language"] == "python":
            units.extend(_python_units(relative_path))
        else:
            units.extend(_java_units(relative_path))
    return {unit["unit"]: unit for unit in units}


def index_paths(paths):
    """Index only the units of the given repo-relative source paths, by service."""
    grouped = {}
    for path in paths:
        service = service_for_path(path)
        if service is None or path not in source_files(service):
            continue
        units = _python_units(path) if service["language"] == "python" else _java_units(path)
        grouped.setdefault(service["name"], {}).update({unit["unit"]: unit for unit in units})
    return grouped


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service", action="append", dest="service_names")
    parser.add_argument("--path", action="append", dest="paths", help="index only these repo-relative files")
    args = parser.parse_args()

    if args.paths:
        indexed = index_paths(args.paths)
    else:
        indexed = {service["name"]: index_service(service) for service in services(args.service_names)}
    print(json.dumps(indexed, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
