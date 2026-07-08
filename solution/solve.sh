#!/usr/bin/env bash
# Oracle solution for repair-javalin-key-escrow-migration-checksums.
# Applies the three bug-fixes required by the remediation report:
#   1. Canonical lexicographic key ordering at every JSON object level (Rule A-1).
#   2. Revoked custody grants are excluded from the manifest (Rule C-1).
#   3. Every historical key version is folded in, not only the latest (Rule D-1).
# Then rebuilds the fat jar and verifies the service emits the correct checksum.
set -euo pipefail

APP_DIR=/app
SRC="$APP_DIR/src/main/java/com/example/keyescrow/CustodyManifestWriter.java"

# ---------------------------------------------------------------------------
# Overwrite CustodyManifestWriter.java with the corrected implementation.
# ---------------------------------------------------------------------------
cat > "$SRC" << 'JAVA_EOF'
package com.example.keyescrow;

import com.example.keyescrow.model.CustodyGrant;
import com.example.keyescrow.model.EscrowExport;
import com.example.keyescrow.model.EscrowedKey;
import com.example.keyescrow.model.KeyVersion;
import com.example.keyescrow.model.WrappingCertificate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Serializes the canonical custody_manifest.json from a parsed export by
 * applying the canonicalization, revocation-exception and version-folding rules
 * defined in docs/key-escrow-remediation-report.md.
 */
public class CustodyManifestWriter {

    private final ObjectMapper mapper;

    public CustodyManifestWriter() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        // Rule A-1: every JSON object's members MUST be in lexicographic ascending key order.
        this.mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public int write(EscrowExport export, Path manifestPath) throws IOException {
        String json = serialize(export);
        Files.write(manifestPath, json.getBytes(StandardCharsets.UTF_8));
        return countFoldedVersions(export);
    }

    public String serialize(EscrowExport export) throws IOException {
        return mapper.writeValueAsString(buildManifest(export));
    }

    Map<String, Object> buildManifest(EscrowExport export) {
        // TreeMap guarantees lexicographic key order (Rule A-1).
        Map<String, Object> root = new TreeMap<>();
        root.put("custodyGrants", buildGrants(export));
        root.put("generatedFrom", export.exportId);
        root.put("keyVersions", buildKeyVersions(export));
        root.put("manifestVersion", 1);
        root.put("recordCount", countFoldedVersions(export));
        root.put("wrappingCertificates", buildCertificates(export));
        return root;
    }

    private List<Map<String, Object>> buildGrants(EscrowExport export) {
        List<CustodyGrant> grants = new ArrayList<>(export.custodyGrants);
        grants.sort(Comparator.comparing(g -> g.grantId));

        List<Map<String, Object>> out = new ArrayList<>();
        for (CustodyGrant grant : grants) {
            // Rule C-1: revoked grants MUST NOT appear in the manifest.
            if (grant.revoked) {
                continue;
            }
            Map<String, Object> node = new TreeMap<>();
            node.put("certId", grant.certId);           // certId is verbatim (Section 6.1)
            node.put("grantId", grant.grantId);
            node.put("grantedEpoch", grant.grantedEpoch);
            node.put("principal", grant.principal.trim()); // Rule B-3: trim leading/trailing whitespace
            out.add(node);
        }
        return out;
    }

    private List<Map<String, Object>> buildKeyVersions(EscrowExport export) {
        List<EscrowedKey> keys = new ArrayList<>(export.escrowedKeys);
        // Rule E-1: sort by normalized (lower-cased) key identifier.
        keys.sort(Comparator.comparing(k -> normalizeKeyId(k.keyId)));

        List<Map<String, Object>> out = new ArrayList<>();
        for (EscrowedKey key : keys) {
            Map<String, Object> keyNode = new TreeMap<>();
            keyNode.put("keyId", normalizeKeyId(key.keyId)); // Rule B-1: lower-case key identifiers

            List<KeyVersion> versions = new ArrayList<>(key.versions);
            // Rule E-4: versions within a key ordered by ascending integer version number.
            versions.sort(Comparator.comparingInt(v -> v.version));

            List<Map<String, Object>> versionOut = new ArrayList<>();
            // Rule D-1: fold EVERY version — not only the most recent one.
            for (KeyVersion v : versions) {
                Map<String, Object> vNode = new TreeMap<>();
                vNode.put("algorithm", v.algorithm.toUpperCase()); // Rule B-4: upper-case algorithm names
                vNode.put("createdEpoch", v.createdEpoch);
                vNode.put("custodyStatus", v.custodyStatus);
                vNode.put("version", v.version);
                versionOut.add(vNode);
            }

            keyNode.put("versions", versionOut);
            out.add(keyNode);
        }
        return out;
    }

    private List<Map<String, Object>> buildCertificates(EscrowExport export) {
        List<WrappingCertificate> certs = new ArrayList<>(export.wrappingCertificates);
        certs.sort(Comparator.comparing(c -> c.certId));

        List<Map<String, Object>> out = new ArrayList<>();
        for (WrappingCertificate cert : certs) {
            Map<String, Object> node = new TreeMap<>();
            node.put("certId", cert.certId);
            node.put("keyId", normalizeKeyId(cert.keyId));
            node.put("notAfterEpoch", cert.notAfterEpoch);
            node.put("subject", collapseWhitespace(cert.subject)); // Rule B-2: collapse internal whitespace
            out.add(node);
        }
        return out;
    }

    private int countFoldedVersions(EscrowExport export) {
        int total = 0;
        for (EscrowedKey key : export.escrowedKeys) {
            total += key.versions.size();
        }
        return total;
    }

    private String normalizeKeyId(String value) {
        return value == null ? null : value.toLowerCase();
    }

    private String collapseWhitespace(String value) {
        return value == null ? null : value.trim().replaceAll("\\s+", " ");
    }
}
JAVA_EOF

echo "Source patched. Rebuilding..."

# ---------------------------------------------------------------------------
# Rebuild the fat jar offline from the corrected source.
# ---------------------------------------------------------------------------
cd "$APP_DIR"
mvn -o -B -DskipTests package

echo "Build complete. Starting service for checksum verification..."

# ---------------------------------------------------------------------------
# Start the service, call the replay endpoint, verify the checksum.
# ---------------------------------------------------------------------------
java -jar target/key-escrow-service.jar &
SVC_PID=$!
trap 'kill -TERM $SVC_PID 2>/dev/null || true' EXIT

# Wait up to 30 s for the service to be ready.
for _ in $(seq 1 30); do
    if (echo > /dev/tcp/127.0.0.1/7070) 2>/dev/null; then
        break
    fi
    sleep 1
done

curl -s -X POST http://localhost:7070/admin/migrations/replay | python3 -m json.tool

ACTUAL=$(sha256sum custody_manifest.json | awk '{print $1}')
EXPECTED=$(awk 'NF && !/^#/{print $1; exit}' assets/expected-custody.sha256)

echo "Actual   SHA-256: $ACTUAL"
echo "Expected SHA-256: $EXPECTED"

if [ "$ACTUAL" = "$EXPECTED" ]; then
    echo "CHECKSUM MATCH — solution verified."
else
    echo "CHECKSUM MISMATCH — fix is incomplete." >&2
    exit 1
fi
