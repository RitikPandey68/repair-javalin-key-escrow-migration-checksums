"""Verifier tests for the key-escrow migration replay repair task.

The system under test is a Java/Javalin service that lives at /app inside the
container. tests/test.sh builds it from current source, starts it on port 7070,
then runs this module. Every test drives the running service over HTTP and/or
reads the custody_manifest.json it emits at /app; the rule-specific tests
recompute their expectations independently from the read-only export so the
agent cannot satisfy them by overwriting an output artifact.

Each test maps to a functional_criteria[] entry in scaffold_plan.yaml.
Run via tests/test.sh, which writes /logs/verifier/reward.txt.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import time
from pathlib import Path

import pytest
import requests


# --- Pinned locations (project is copied to /app by environment/Dockerfile). ---
APP_DIR = Path("/app")
EXPORT_PATH = APP_DIR / "assets" / "key-escrow-export.json"
MANIFEST_PATH = APP_DIR / "custody_manifest.json"
EXPECTED_CHECKSUM_PATH = APP_DIR / "assets" / "expected-custody.sha256"
POM_PATH = APP_DIR / "pom.xml"

# --- Pinned runtime values (scaffold_plan thresholds / runtime.api_endpoints). ---
BASE_URL = "http://127.0.0.1:7070"
REPLAY_URL = f"{BASE_URL}/admin/migrations/replay"
HEALTH_URL = f"{BASE_URL}/health"


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #
def _replay(timeout: int = 30) -> requests.Response:
    """POST the migration replay and return the response."""
    return requests.post(REPLAY_URL, timeout=timeout)

def _replay_ok() -> None:
    """Drive a successful replay so the manifest reflects the current export."""
    resp = _replay()
    assert resp.status_code == 200, f"replay failed: {resp.status_code} {resp.text}"


def _read_manifest_bytes() -> bytes:
    assert MANIFEST_PATH.is_file(), f"{MANIFEST_PATH} was not emitted by the replay"
    return MANIFEST_PATH.read_bytes()


def _read_manifest_json() -> dict:
    # json.loads preserves object key insertion order, so the parsed dict's key
    # order equals the serialized byte order — this is what lets us assert the
    # canonical ordering below.
    return json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))


def _load_export() -> dict:
    return json.loads(EXPORT_PATH.read_text(encoding="utf-8"))


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _expected_checksum() -> str:
    """First whitespace-delimited token of the first non-comment line."""
    for line in EXPECTED_CHECKSUM_PATH.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        return stripped.split()[0].lower()
    raise AssertionError(f"no checksum line found in {EXPECTED_CHECKSUM_PATH}")


def _assert_keys_sorted(obj: dict, label: str) -> None:
    keys = list(obj.keys())
    assert keys == sorted(keys), (
        f"{label} keys are not in lexicographic ascending order: {keys}"
    )


# --------------------------------------------------------------------------- #
# Fixtures
# --------------------------------------------------------------------------- #
@pytest.fixture(scope="session", autouse=True)
def _service_ready() -> None:
    """Block until the service under test answers /health, or fail fast."""
    deadline = time.time() + 60
    last_err: Exception | None = None
    while time.time() < deadline:
        try:
            resp = requests.get(HEALTH_URL, timeout=3)
            if resp.status_code == 200:
                return
        except requests.RequestException as err:
            last_err = err
        time.sleep(0.5)
    pytest.fail(f"service on {BASE_URL} never became reachable: {last_err}")


# --------------------------------------------------------------------------- #
# Tests — one (or more) per functional_criteria[] entry
# --------------------------------------------------------------------------- #
def test_replay_endpoint_returns_success():
    """functional_criteria[id=replay_endpoint_returns_success]: POST /admin/migrations/replay
    returns 200 and reports a successful ingestion of the export."""
    resp = _replay()
    assert resp.status_code == 200, f"expected 200, got {resp.status_code}: {resp.text}"
    body = resp.json()
    assert body.get("status") == "ok", f"unexpected status field: {body}"
    assert body.get("manifestPath") == "custody_manifest.json", body
    # recordCount is the number of custody records folded from the export. The
    # export carries 3 + 2 + 1 = 6 key versions across its three escrowed keys.
    assert isinstance(body.get("recordCount"), int), body
    assert body["recordCount"] == 6, f"recordCount should reflect all folded versions: {body}"


def test_manifest_checksum_matches_expected():
    """functional_criteria[id=manifest_checksum_matches_expected]: the SHA-256 of the
    emitted custody_manifest.json equals the value in assets/expected-custody.sha256."""
    _replay_ok()
    actual = _sha256(_read_manifest_bytes())
    expected = _expected_checksum()
    assert actual == expected, (
        "custody_manifest.json checksum does not match the expected digest "
        f"(expected {expected}, got {actual})"
    )


def test_revoked_grants_excluded():
    """functional_criteria[id=revoked_grants_excluded]: grants marked revoked in the
    export never appear in the manifest; exactly the standing grants remain."""
    _replay_ok()
    export = _load_export()
    revoked = {g["grantId"] for g in export["custodyGrants"] if g["revoked"]}
    standing = {g["grantId"] for g in export["custodyGrants"] if not g["revoked"]}
    assert revoked, "test fixture sanity: export must contain at least one revoked grant"

    manifest = _read_manifest_json()
    grant_ids = {g["grantId"] for g in manifest["custodyGrants"]}
    assert grant_ids.isdisjoint(revoked), (
        f"revoked grants leaked into the manifest: {sorted(grant_ids & revoked)}"
    )
    assert grant_ids == standing, (
        f"manifest grants must be exactly the standing grants {sorted(standing)}, "
        f"got {sorted(grant_ids)}"
    )


def test_nested_key_versions_folded():
    """functional_criteria[id=nested_key_versions_folded]: every nested key version from
    the export is folded into the manifest with none dropped and none invented."""
    # OPEN_QUESTION: the exact emitted version *ordering* is a report-defined
    # canonical rule (scaffold_plan.open_questions, affects=tests). This test
    # asserts the decision-independent invariant — the full set of versions per
    # key is preserved — by comparing sorted sequences, so a correct solution
    # passes regardless of the report's final ordering rule.
    _replay_ok()
    export = _load_export()
    # keyId case-normalization is a report-defined value rule (see open question);
    # compare case-insensitively so this test does not depend on that decision.
    expected = {
        key["keyId"].lower(): sorted(v["version"] for v in key["versions"])
        for key in export["escrowedKeys"]
    }
    total_expected = sum(len(v) for v in expected.values())

    manifest = _read_manifest_json()
    actual = {
        key["keyId"].lower(): sorted(v["version"] for v in key["versions"])
        for key in manifest["keyVersions"]
    }

    assert set(actual) == set(expected), (
        f"manifest keys {sorted(actual)} do not match export keys {sorted(expected)}"
    )
    for key_id, versions in expected.items():
        assert actual[key_id] == versions, (
            f"key {key_id}: expected folded versions {versions} (order-independent), "
            f"got {actual[key_id]}"
        )
    total_actual = sum(len(v) for v in actual.values())
    assert total_actual == total_expected, (
        f"expected {total_expected} folded versions across all keys, got {total_actual}"
    )


def test_manifest_values_whitespace_normalized():
    """functional_criteria[id=canonical_json_ordering_applied] (decision-independent part):
    manifest values are emitted without surrounding whitespace. The exact key *ordering*
    is deferred to test_canonical_json_ordering_applied below (an open question)."""
    _replay_ok()
    manifest = _read_manifest_json()

    grants = manifest["custodyGrants"]
    assert grants, "manifest must contain at least one custody grant to inspect"
    # Value normalization that is unambiguous regardless of the report's finer
    # rules: principals are emitted without surrounding whitespace.
    for grant in grants:
        principal = grant["principal"]
        assert principal == principal.strip(), (
            f"principal is not whitespace-normalized: {principal!r}"
        )


def test_canonical_json_ordering_applied():
    """functional_criteria[id=canonical_json_ordering_applied]: manifest object keys are
    serialized in canonical order at every object level.

    Currently asserts lexicographic-ascending ordering, which is the rule the shipped
    code and the project's own JUnit contract assume. Skipped until the report's exact
    canonical order is confirmed (see the skip reason)."""
    _replay_ok()
    manifest = _read_manifest_json()

    _assert_keys_sorted(manifest, "top-level manifest")

    grants = manifest["custodyGrants"]
    assert grants, "manifest must contain at least one custody grant to inspect"
    _assert_keys_sorted(grants[0], "custody grant")

    key_versions = manifest["keyVersions"]
    assert key_versions, "manifest must contain at least one key entry to inspect"
    _assert_keys_sorted(key_versions[0], "key entry")
    _assert_keys_sorted(key_versions[0]["versions"][0], "key version")

    certs = manifest["wrappingCertificates"]
    assert certs, "manifest must contain at least one wrapping certificate to inspect"
    _assert_keys_sorted(certs[0], "wrapping certificate")


def test_replay_is_deterministic():
    """functional_criteria[id=replay_is_deterministic]: replaying the same export twice
    produces byte-identical manifest output and an identical checksum."""
    _replay_ok()
    first = _read_manifest_bytes()
    _replay_ok()
    second = _read_manifest_bytes()
    assert first == second, "custody_manifest.json bytes differ between identical replays"
    assert _sha256(first) == _sha256(second), "checksum differs between identical replays"


def test_existing_routes_preserved():
    """functional_criteria[id=existing_routes_preserved]: the pre-existing GET /health
    probe still responds 200 after the repair."""
    # OPEN_QUESTION: the full pre-existing route inventory is defined by the
    # synthesized service (scaffold_plan.open_questions, affects=tests). The
    # confirmed pre-existing route is GET /health; that it stays 200 is the
    # decision-independent regression signal here.
    resp = requests.get(HEALTH_URL, timeout=5)
    assert resp.status_code == 200, f"GET /health regressed: {resp.status_code}"


def test_manifest_not_hardcoded():
    """functional_criteria[id=manifest_not_hardcoded]: the manifest is derived from the
    export — perturbing the export changes the manifest and its checksum."""
    original = EXPORT_PATH.read_bytes()
    try:
        _replay_ok()
        baseline_bytes = _read_manifest_bytes()
        baseline_sum = _sha256(baseline_bytes)

        data = json.loads(original.decode("utf-8"))
        data["exportId"] = f"{data['exportId']}-PERTURBED-CHECK"
        EXPORT_PATH.write_text(json.dumps(data), encoding="utf-8")

        _replay_ok()
        perturbed_bytes = _read_manifest_bytes()
        perturbed_sum = _sha256(perturbed_bytes)

        assert perturbed_bytes != baseline_bytes, (
            "manifest did not change when the export was perturbed — it appears hardcoded"
        )
        assert perturbed_sum != baseline_sum, (
            "manifest checksum did not change when the export was perturbed"
        )
    finally:
        # Restore the read-only fixture and regenerate the canonical manifest so
        # this test leaves no state behind for the rest of the suite.
        EXPORT_PATH.write_bytes(original)
        _replay_ok()


def test_key_escrow_service_java_tests():
    """functional_criteria supporting check: the project's own JUnit suite — which
    encodes the correct canonicalization/revocation/version-folding behaviour —
    passes once the migration logic is repaired."""
    # Supporting-only signal (the suite is agent-editable); the verifier-owned
    # HTTP/manifest invariants above are the authoritative checks. Path adapted
    # to the runtime project root /app (the plan's test_command is repo-relative).
    result = subprocess.run(
        ["mvn", "-o", "-q", "-f", str(POM_PATH), "test"],
        cwd=str(APP_DIR),
        capture_output=True,
        text=True,
        timeout=240,
    )
    assert result.returncode == 0, (
        "project-internal JUnit suite failed:\n"
        f"{result.stdout[-4000:]}\n{result.stderr[-4000:]}"
    )
