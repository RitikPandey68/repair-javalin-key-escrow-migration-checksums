# Completion Plan — repair-javalin-key-escrow-migration-checksums-478

- **Task type:** `single_step`
- **Schema version:** 1

## Original Task

### Description

The provided Javalin KeyEscrow service produces the wrong custody digest after replaying a regulator-mandated key migration. A 70k-token Markdown incident report describes the canonicalization rules, revoked wrapping-key exceptions, and ordering constraints needed to transform the nested JSON export into the migration tables. Fix the Java migration path behind the API so that replaying the fixture through the service emits the exact expected SHA-256 checksum artifact.

Instruction sketch:
Repair the Java Javalin API's database migration endpoint so POST /admin/migrations/replay ingests assets/key-escrow-export.json, applies the custody rules from docs/key-escrow-remediation-report.md, and writes the H2 migration tables deterministically. The verifier runs the service locally, invokes it with curl, dumps the generated custody_manifest.json, and compares its sha256sum against assets/expected-custody.sha256. Constraints: no network access, preserve existing API routes, do not hard-code the final checksum, and handle nested key versions, wrapping certificates, revoked grants, and report-defined canonical JSON ordering.

Target languages: Java, Bash.

External code provided: A Maven-based Java Javalin API with a broken /admin/migrations/replay migration endpoint, H2 migration scripts, and a custody manifest writer that currently mishandles report-specific canonicalization and revocation rules.

### Metadata

- **OS:** linux
- **Build timeout (s):** 600
- **Run timeout (s):** 300
- **Emit process rubric:** False

## Components

### task.toml — Generated

- **Reference:** <https://www.harborframework.com/docs/tasks>

**Produced files:**

- `task.toml`

**Needed (remaining work):**

- [ ] Confirm [agent].timeout_sec (900s, raised from the seed's 300s floor) and [verifier].timeout_sec (300s) match the intended agent/grading runtime for a Maven build + service start + checksum compare.
- [ ] Confirm [environment] resources (2 cpus / 4096 MB / 10240 MB storage) are adequate for a JDK 17 Maven build.
- [ ] Adjust [environment].os or workdir if the finalized Dockerfile layout differs.

**Acceptance criteria:**

- [ ] Harbor loads the task without errors.
- [ ] Declared [environment].os (linux) matches the files under environment/.
- [ ] metadata.languages (java, bash) matches the shipped code_project and solution/test scripts.

**Open questions:** _none_

### instruction.md — Missing

- **Reference:** <https://www.harborframework.com/docs/tasks>

**Produced files:** _none_

**Needed (remaining work):**

- [ ] Write the agent-facing instruction from the seed plan: repair the Javalin migration path so POST /admin/migrations/replay produces the correct custody digest.
- [ ] Name every path the tests and service use by their pinned values: input assets/key-escrow-export.json, rules docs/key-escrow-remediation-report.md, output custody_manifest.json, expected assets/expected-custody.sha256.
- [ ] State the constraints explicitly: no network access, preserve all existing API routes, do not hard-code the final checksum, and correctly handle nested key versions, wrapping certificates, revoked grants, and report-defined canonical JSON ordering.
- [ ] Document the service port (7070) and how the agent starts/exercises the service.
- [ ] Resolve the tests/solution open questions (final report rules, expected checksum, route inventory) before publishing.

**Acceptance criteria:**

- [ ] Following the instruction makes tests/test.sh exit zero once a correct fix is applied.
- [ ] Every output/input path the test file references appears in the instruction.
- [ ] The instruction is unambiguous that the checksum must be computed, not hardcoded.

**Open questions:** _none_

### environment/ — Partial

- **Reference:** <https://www.harborframework.com/docs/tasks>

**Produced files:**

- `environment/.dockerignore`
- `environment/Dockerfile`
- `environment/server/README.md` — code_project: key_escrow_service
- `environment/server/assets/expected-custody.sha256` — code_project: key_escrow_service
- `environment/server/assets/key-escrow-export.json` — code_project: key_escrow_service
- `environment/server/docs/key-escrow-remediation-report.md` — code_project: key_escrow_service
- `environment/server/pom.xml` — code_project: key_escrow_service
- `environment/server/src/main/java/com/example/keyescrow/App.java` — code_project: key_escrow_service
- `environment/server/src/main/java/com/example/keyescrow/CustodyManifestWriter.java` — code_project: key_escrow_service
- `environment/server/src/main/java/com/example/keyescrow/ExportIngester.java` — code_project: key_escrow_service
- `environment/server/src/main/java/com/example/keyescrow/MigrationController.java` — code_project: key_escrow_service
- `environment/server/src/main/java/com/example/keyescrow/MigrationWriter.java` — code_project: key_escrow_service
- `environment/server/src/main/java/com/example/keyescrow/model/CustodyGrant.java` — code_project: key_escrow_service
- `environment/server/src/main/java/com/example/keyescrow/model/EscrowExport.java` — code_project: key_escrow_service
- `environment/server/src/main/java/com/example/keyescrow/model/EscrowedKey.java` — code_project: key_escrow_service
- `environment/server/src/main/java/com/example/keyescrow/model/KeyVersion.java` — code_project: key_escrow_service
- `environment/server/src/main/java/com/example/keyescrow/model/WrappingCertificate.java` — code_project: key_escrow_service
- `environment/server/src/main/resources/migration_schema.sql` — code_project: key_escrow_service
- `environment/server/src/test/java/com/example/keyescrow/CustodyManifestWriterTest.java` — code_project: key_escrow_service
- `environment/server/src/test/java/com/example/keyescrow/MigrationEndpointTest.java` — code_project: key_escrow_service

**Needed (remaining work):**

- [ ] Verify the synthesized Javalin/Maven project (environment/server) builds with 'mvn -o package' and that all Maven dependencies resolve offline after 'mvn dependency:go-offline'.
- [ ] Confirm exactly three intentional bugs (canonical ordering, revoked-grant exclusion, nested-version folding) are planted and no others.
- [ ] Expand/verify docs/key-escrow-remediation-report.md is genuine long-context prose of at least 50k tokens and that its canonicalization/revocation/ordering rules are internally consistent.
- [ ] Regenerate assets/expected-custody.sha256 from the reference solution's custody_manifest.json output (currently a placeholder).
- [ ] Confirm the service resolves assets/ and docs/ and writes custody_manifest.json relative to its run directory under the finalized /app layout.

**Acceptance criteria:**

- [ ] docker build succeeds against environment/Dockerfile within the 600s build timeout.
- [ ] The service starts and POST /admin/migrations/replay responds inside the built container.
- [ ] 'mvn -o test' runs offline inside the built image and fails on the seeded project (bugs present).
- [ ] tests/test.sh runs inside the built container without environment errors.

**Open questions:** _none_

### tests/ — Generated

- **Reference:** <https://www.harborframework.com/docs/tasks>

**Produced files:**

- `tests/test.sh`
- `tests/test_outputs.py`

**Needed (remaining work):**

- [ ] Confirm every functional criterion in scaffold_plan.tests.functional_criteria has a matching pytest test that drives the running service (start service, curl/HTTP the replay endpoint, inspect custody_manifest.json).
- [ ] Confirm the manifest-checksum test compares against assets/expected-custody.sha256 and that this file holds the REAL checksum from the reference solution (currently a placeholder).
- [ ] Resolve the open question 'The exact canonical JSON key-ordering and value-normalization rules live in the >=50k-token remediation report; confirm the report is final and fully consistent with the reference solution before finalizing the canonical-ordering and version-folding assertions.', then replace any deferred (skip/xfail) canonicalization/ordering test with a binding assertion.
- [ ] Resolve the open question 'The full inventory of pre-existing API routes that must be preserved is defined by the synthesized Javalin service; confirm the complete route list so the regression assertion covers every existing endpoint.', then extend the existing_routes_preserved test to cover every preserved route.
- [ ] Add/confirm a perturbed-export fixture so the manifest_not_hardcoded test can assert the output changes with the input.
- [ ] Confirm tests/test.sh writes /logs/verifier/reward.{txt,json} (exactly 1 or 0) on every code path including early exits.

**Acceptance criteria:**

- [ ] Tests fail on the seeded (buggy) service and on a naive solution.
- [ ] Tests pass on a correct repair.
- [ ] Verifier applies a binary 0/1 reward only.
- [ ] Verifier runs identical logic for the oracle and the agent (no oracle/agent conditional branches).

**Open questions:**

- The exact canonical JSON key-ordering and value-normalization rules live in the >=50k-token remediation report; confirm the report is final and fully consistent with the reference solution before finalizing the canonical-ordering and version-folding assertions.
- The full inventory of pre-existing API routes that must be preserved is defined by the synthesized Javalin service; confirm the complete route list so the regression assertion covers every existing endpoint.

### solution — Missing

- **Reference:** <https://www.harborframework.com/docs/tasks>

**Produced files:** _none_

**Needed (remaining work):**

- [ ] Write solve.sh (Bash) that applies the correct fix to the Javalin migration/manifest code so replaying assets/key-escrow-export.json emits the canonical custody_manifest.json.
- [ ] Implement the report-defined canonical JSON ordering, revoked wrapping-key grant exclusion, and nested key-version folding generically from the report/export, not against fixture-specific literals.
- [ ] Resolve the open question 'The expected custody checksum in assets/expected-custody.sha256 is shipped as a placeholder; its real value must be regenerated from the human-authored reference solution's custody_manifest.json output before the task can be graded.' by running the solution and writing the real sha256 into assets/expected-custody.sha256.
- [ ] Do not echo/hard-code the checksum or the manifest contents; show the full derivation from inputs.

**Acceptance criteria:**

- [ ] Running solve.sh in the built container makes tests/test.sh exit zero.
- [ ] solve.sh produces a different (still correct) manifest and checksum if the input export fixture changes.
- [ ] solve.sh does not hard-code the final checksum or manifest bytes.

**Open questions:**

- The expected custody checksum in assets/expected-custody.sha256 is shipped as a placeholder; its real value must be regenerated from the human-authored reference solution's custody_manifest.json output before the task can be graded.
