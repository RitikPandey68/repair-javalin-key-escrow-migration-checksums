The Javalin key-escrow service at `/app` contains bugs in its migration replay pipeline. Invoking `POST /admin/migrations/replay` currently produces a `custody_manifest.json` whose SHA-256 digest does not match the expected value in `/app/assets/expected-custody.sha256`.

The custody rules the manifest writer must implement are fully specified in `/app/docs/key-escrow-remediation-report.md`.

Fix the Java source under `/app/src` so that replaying `/app/assets/key-escrow-export.json` via the endpoint produces a `/app/custody_manifest.json` whose `sha256sum` matches the digest stored in `/app/assets/expected-custody.sha256`.

### API Specification

The service MUST expose the following HTTP endpoints on port `7070`:

1. **`GET /health`**
   * **Response Body**:
     ```json
     {
       "status": "up"
     }
     ```

2. **`POST /admin/migrations/replay`**
   * Replays the migration from `/app/assets/key-escrow-export.json` and writes the resulting canonicalized manifest to `/app/custody_manifest.json`.
   * **Response Body**:
     ```json
     {
       "status": "ok",
       "manifestPath": "custody_manifest.json",
       "recordCount": 6
     }
     ```

### Manifest Schema & Serialization Requirements

The generated `/app/custody_manifest.json` file MUST conform to the following schema and formatting requirements:

* **Top-Level Object Fields** (sorted lexicographically):
  * `custodyGrants`: List of custody grant objects.
  * `generatedFrom`: String (the `exportId` from the input export).
  * `keyVersions`: List of key version grouping objects.
  * `manifestVersion`: Integer (must be `1`).
  * `recordCount`: Integer (total number of folded/unfolded version records across all keys).
  * `wrappingCertificates`: List of wrapping certificate objects.
* **Lexicographical Key Ordering**: Every JSON object's members/keys MUST be serialized in lexicographical ascending order (Rule A-1).
* **Whitespace Normalization**:
  * Trim leading/trailing whitespace from principal email fields (Rule B-3).
  * Collapse consecutive internal whitespace into a single space in wrapping certificate subjects (Rule B-2).
* **Business Logic Rules**:
  * **Revocation Filtering**: Revoked custody grants (`revoked: true`) MUST NOT appear in the manifest (Rule C-1).
  * **Version Folding**: All historical versions for each key MUST be folded into the list of versions, sorted by ascending version number (Rule D-1, Rule E-4).
  * **Identifiers**: Sort wrapping certs by `certId` ascending. Sort keys by normalized (lower-cased) `keyId` ascending.

Constraints:
- Do not modify any file under `/app/assets/`.
- Do not hard-code the expected checksum anywhere in the Java source.
- All existing API routes (`GET /health`, `POST /admin/migrations/replay`) must continue to respond normally.
- The service listens on port `7070`. Rebuild and restart it after making changes.
