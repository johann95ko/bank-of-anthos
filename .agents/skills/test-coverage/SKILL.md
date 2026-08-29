---
name: test-coverage
description: Write or refresh unit tests for this monorepo in the language of the code under test, without duplicating tests that already exist. Use for the initial per-service coverage push to 80% and for generating tests for functions changed by a pull request.
---

# Test coverage for bank-of-anthos

Every service is registered in `.devin/coverage-config.json` with its language, source
globs, test directory, coverage command and report path. Tooling in `scripts/coverage/`
reads that registry, so never hardcode service paths.

`.devin/test-coverage-manifest.json` maps every function/method ("unit") to the tests
that exercise it plus a fingerprint of its implementation. It is the deduplication
record: it is what tells you a unit is already tested, and what tells you an already
tested unit has changed since its tests were written.

## Non-negotiables

- Write tests in the language of the code under test: `pytest` for Python services,
  JUnit 5 + Spring Boot Test + Mockito for Java services. Follow the existing test files
  in the same service for style, fixtures and mocking.
- Never write a second test for a unit that the manifest already reports as `current`.
- Never weaken, delete or rewrite a passing test to make coverage numbers move, and never
  edit production code to make a test pass — if the code looks wrong, say so instead.
- Target is 80% line coverage per service (`min_coverage` in the config). Prioritise
  branch-heavy business logic over getters, DTOs and generated code.

## Commands

```bash
python3 scripts/coverage/manifest.py check                     # untested / stale / current / orphaned, all services
python3 scripts/coverage/manifest.py check --service contacts
python3 scripts/coverage/manifest.py check --base origin/main  # restrict to files the PR touched
python3 scripts/coverage/manifest.py check --json              # same, machine readable
python3 scripts/coverage/report.py --service contacts          # run tests + report coverage vs the 80% floor
python3 scripts/coverage/report.py --no-run                    # read existing reports only
python3 scripts/coverage/map_tests.py --service contacts       # re-attribute tests to units, updates the manifest
python3 scripts/coverage/manifest.py sync                      # refresh fingerprints, prune deleted units
```

`make test-coverage`, `make coverage-report`, `make coverage-manifest-check` and
`make coverage-manifest-sync` wrap the same entry points.

Python attribution is exact (coverage contexts per test). Java attribution is a
name-based heuristic, so treat a Java unit with no recorded tests as "probably untested"
and confirm by looking at the test class before writing anything.

## Workflow

1. Classify. Run `manifest.py check` for the service (add `--base <ref>` when working from
   a PR diff) and work only the `untested` and `stale` buckets.
2. For each `untested` unit: read the unit and its collaborators, then add tests to the
   service's existing test class/module for that source file, creating one only if none
   exists. Cover the happy path plus each error/branch the unit actually has.
3. For each `stale` unit: open the tests listed in the manifest entry and reconcile them
   with the new signature and intent — update assertions, arguments and mocks in place,
   remove assertions the code no longer supports, and add cases for behaviour the change
   introduced. Do not add a parallel test for the same unit.
4. For each `orphaned` entry: if the unit is gone, delete the now-dead tests and let
   `sync` drop the entry.
5. Verify. Run the service's tests and `report.py --service <name>` until they pass and
   coverage clears the floor.
6. Record. Run `map_tests.py --service <name>` (which syncs fingerprints too) and commit
   the manifest change with the tests. Reviewing a `stale` unit without changing its tests
   still requires this step, so the refreshed fingerprint records that it was reviewed.

## Service notes

- Python services run under `uv` from the service directory: `uv run pytest -q -p no:warnings`.
  `uv sync` first if the venv is missing. Flask services are tested through
  `create_app()` and a test client; JWT-protected endpoints need a token signed with the
  test key material used by the existing tests.
- Java services build from the repo root: `./mvnw verify -f <service>/pom.xml`. JaCoCo
  reports land in `<service>/target/site/jacoco/`. Prefer `@WebMvcTest`/`MockMvc` for
  controllers and plain Mockito unit tests for everything else; do not stand up
  databases, Redis or GCP clients — mock the repositories and caches.
- `src/frontend` and `src/loadgenerator` have no tests yet. Both import heavy
  dependencies at module load; test them via their Flask/Locust entry points with
  external calls mocked.
- `src/ledgermonolith` duplicates the ledger services' logic. Reuse the ledger test
  patterns rather than inventing new ones.
- Cypress e2e tests under `.github/workflows/ui-tests/` are out of scope for this skill.
