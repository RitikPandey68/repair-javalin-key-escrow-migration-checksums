package com.example.keyescrow;

import com.example.keyescrow.model.EscrowExport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the canonicalization, revocation-exception and version-folding rules the
 * remediation report mandates for custody_manifest.json: object keys are emitted in
 * the report-defined canonical order at every object level, revoked custody grants
 * are excluded from the manifest, and every historical key version is folded in
 * without loss.
 */
class CustodyManifestWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private EscrowExport export;

    @BeforeEach
    void loadExport() throws Exception {
        export = new ExportIngester().ingest(Path.of("assets", "key-escrow-export.json"));
    }

    /**
     * Rule: object keys are serialized in the report-defined canonical order,
     * lexicographic ascending, at every object level.
     */
    @Test
    void manifestKeysAreCanonicalLexicographicOrder() throws Exception {
        String json = new CustodyManifestWriter().serialize(export);

        List<String> topLevelKeys = topLevelFieldOrder(json);
        List<String> sorted = new ArrayList<>(topLevelKeys);
        sorted.sort(String::compareTo);
        assertEquals(sorted, topLevelKeys,
                "Top-level manifest keys must be emitted in lexicographic ascending order");

        // Also assert a representative nested object (a grant) is canonically ordered.
        JsonNode root = mapper.readTree(json);
        JsonNode firstGrant = root.get("custodyGrants").get(0);
        List<String> grantKeys = fieldNames(firstGrant);
        List<String> grantSorted = new ArrayList<>(grantKeys);
        grantSorted.sort(String::compareTo);
        assertEquals(grantSorted, grantKeys,
                "Grant object keys must be emitted in lexicographic ascending order");
    }

    /**
     * Rule: revoked custody grants are excluded from the emitted manifest; only
     * standing grants remain.
     */
    @Test
    void revokedGrantsAreExcluded() throws Exception {
        JsonNode root = mapper.readTree(new CustodyManifestWriter().serialize(export));
        List<String> grantIds = new ArrayList<>();
        root.get("custodyGrants").forEach(g -> grantIds.add(g.get("grantId").asText()));

        // CG-0002 and CG-0005 are revoked in the export and must not appear.
        assertFalse(grantIds.contains("CG-0002"), "Revoked grant CG-0002 must be excluded");
        assertFalse(grantIds.contains("CG-0005"), "Revoked grant CG-0005 must be excluded");

        assertTrue(grantIds.contains("CG-0001"));
        assertTrue(grantIds.contains("CG-0003"));
        assertTrue(grantIds.contains("CG-0007"));
        assertEquals(3, grantIds.size(), "Exactly the three standing grants must remain");
    }

    /**
     * Rule: every nested key version is folded into the manifest in ascending
     * version order; no historical version is dropped.
     */
    @Test
    void allKeyVersionsAreFoldedInOrder() throws Exception {
        JsonNode root = mapper.readTree(new CustodyManifestWriter().serialize(export));
        JsonNode keyVersions = root.get("keyVersions");

        JsonNode zeta = null;
        for (JsonNode k : keyVersions) {
            if ("kms-root-zeta".equals(k.get("keyId").asText())) {
                zeta = k;
            }
        }
        assertTrue(zeta != null, "kms-root-zeta must be present");

        JsonNode versions = zeta.get("versions");
        assertEquals(3, versions.size(),
                "All three historical versions of kms-root-zeta must be folded in");
        assertEquals(1, versions.get(0).get("version").asInt());
        assertEquals(2, versions.get(1).get("version").asInt());
        assertEquals(3, versions.get(2).get("version").asInt());
    }

    /**
     * Rule: recordCount reflects the total folded versions across all keys
     * (3 + 2 + 1 = 6), not the count of keys.
     */
    @Test
    void recordCountReflectsAllFoldedVersions() throws Exception {
        JsonNode root = mapper.readTree(new CustodyManifestWriter().serialize(export));
        assertEquals(6, root.get("recordCount").asInt());
    }

    private List<String> topLevelFieldOrder(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        return fieldNames(root);
    }

    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
