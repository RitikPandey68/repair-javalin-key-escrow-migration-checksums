The Javalin key-escrow service at `/app` contains bugs in its migration replay pipeline. Invoking `POST /admin/migrations/replay` currently produces a `custody_manifest.json` whose SHA-256 digest does not match the expected value in `/app/assets/expected-custody.sha256`.

The custody rules the manifest writer must implement are fully specified in `/app/docs/key-escrow-remediation-report.md`.

Fix the Java source under `/app/src` so that replaying `/app/assets/key-escrow-export.json` via the endpoint produces a `/app/custody_manifest.json` whose `sha256sum` matches the digest stored in `/app/assets/expected-custody.sha256`.

Constraints:
- Do not modify any file under `/app/assets/`.
- Do not hard-code the expected checksum anywhere in the Java source.
- All existing API routes (`GET /health`, `POST /admin/migrations/replay`) must continue to respond normally.
- The service listens on port `7070`. Rebuild and restart it after making changes.
