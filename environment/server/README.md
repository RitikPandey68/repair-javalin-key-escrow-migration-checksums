# key-escrow-service

A Javalin (JDK 17) service that replays the regulator-mandated key-escrow migration.
On replay it ingests the nested key-escrow export, applies the custody
canonicalization, revocation-exception and version-folding rules from the
remediation report, writes the H2 migration tables and emits a canonical
`custody_manifest.json` whose SHA-256 must match the regulator's expected checksum
artifact.

## What it does

The service reads two artifacts relative to its working directory:

- `assets/key-escrow-export.json` — the nested export: escrowed keys with their
  historical versions, wrapping certificates, and custody grants.
- `docs/key-escrow-remediation-report.md` — the binding specification of the
  canonicalization, revocation and version-folding rules.

It writes the migration into an H2 store at `./data/migration` (tables
`key_versions`, `wrapping_certificates`, `custody_grants`) and serializes
`custody_manifest.json` into the working directory. Because the manifest's SHA-256 is
the contract, it must be run from the project root so `assets/` and `docs/` resolve.

## Endpoints

| Method | Path | Description |
| ------ | ---- | ----------- |
| `GET`  | `/health` | Liveness probe; returns `200`. |
| `POST` | `/admin/migrations/replay` | Runs the migration replay; returns `200` with `{status, manifestPath, recordCount}`. |

The service listens on port `7070`.

## Build

```
mvn -o package
```

This produces the shaded fat jar at `target/key-escrow-service.jar`. Dependencies
resolve fully offline once the local Maven cache is warmed with
`mvn dependency:go-offline`.

## Run

```
java -jar target/key-escrow-service.jar
```

Then trigger a replay:

```
curl -X POST http://localhost:7070/admin/migrations/replay
```

## Test

```
mvn -o test
```

The JUnit suite under `src/test/java` asserts the canonical key ordering, the
revocation exclusions and the full version folding defined in the remediation
report, plus the health and replay HTTP behaviour.

## Verifying the manifest checksum

After a replay, compare the produced manifest against the expected digest:

```
sha256sum custody_manifest.json
cat assets/expected-custody.sha256
```
