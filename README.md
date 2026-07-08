# Repair Javalin Key Escrow Migration Checksums

A debugging challenge built around a real-world scenario: a Javalin-based key escrow service that produces the wrong SHA-256 digest when replaying a regulator-mandated key migration. The service compiles and runs without errors — the bugs are purely logical, hiding in the serialization and data-transformation layer.

---

## The Scenario

A financial custody provider runs a Javalin REST service that exports escrowed cryptographic keys into a canonical `custody_manifest.json`. After every migration replay, a regulator verifies the manifest by comparing its SHA-256 against a known-good digest stored in `assets/expected-custody.sha256`.

Something changed. The digest no longer matches. The service starts fine, the endpoint responds `200 OK`, but the checksum is wrong every single time.

Your job: read the incident report, find the bugs, fix the Java source, rebuild the service, and get the digest to match.

---

## Structure

```
.
├── instruction.md                          # Task instructions for the agent
├── task.toml                               # Task metadata
├── solution/
│   └── solve.sh                            # Oracle solution (patches + rebuilds the service)
├── tests/
│   └── test_outputs.py                     # Verifier test suite
└── environment/
    ├── Dockerfile                          # Builds the self-contained service image
    └── server/
        ├── assets/
        │   ├── key-escrow-export.json      # The export fixture the service replays
        │   └── expected-custody.sha256     # The correct digest to match
        ├── docs/
        │   └── key-escrow-remediation-report.md  # Full incident report (canonicalization rules)
        ├── src/main/java/com/example/keyescrow/
        │   ├── App.java
        │   ├── CustodyManifestWriter.java  # ← contains the bugs
        │   ├── ExportIngester.java
        │   ├── MigrationController.java
        │   └── model/
        └── pom.xml
```

---

## Running the Service

Build the Docker image and start the container:

```bash
docker build -t key-escrow-task ./environment
docker run -p 7070:7070 key-escrow-task
```

Trigger the migration replay:

```bash
curl -X POST http://localhost:7070/admin/migrations/replay
```

Check the manifest digest:

```bash
sha256sum custody_manifest.json
cat environment/server/assets/expected-custody.sha256
```

If they match — you fixed it. If not — keep reading the incident report.

---

## The Bugs

There are three logical bugs in `CustodyManifestWriter.java`. Each one alone is enough to corrupt the digest. Together they produce a completely wrong output.

The incident report at `environment/server/docs/key-escrow-remediation-report.md` documents the exact canonicalization rules, revocation-exception handling, and version-folding requirements that the manifest writer must satisfy.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| HTTP framework | Javalin 5.6.5 |
| JSON serialization | Jackson 2.17.2 |
| Build tool | Maven (offline cache baked into image) |
| Runtime | Java 17 |
| Container | Docker (Ubuntu 24.04 base) |
| Verifier | Python / pytest |

---

## Verification

After fixing the service and rebuilding, the verifier checks:

- The manifest exists at the expected path
- The SHA-256 matches `expected-custody.sha256` exactly
- Revoked grants are excluded from the manifest
- All historical key versions are present (not just the latest)
- JSON object keys are in canonical lexicographic order at every level
- The `recordCount` field reflects the total number of folded version records

---

## Quick Reference — API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | Returns `{"status":"ok"}` |
| `POST` | `/admin/migrations/replay` | Replays the export, writes `custody_manifest.json`, returns record count |

The service listens on port `7070`.
