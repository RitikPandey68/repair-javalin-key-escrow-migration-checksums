package com.example.keyescrow;

import com.example.keyescrow.model.CustodyGrant;
import com.example.keyescrow.model.EscrowExport;
import com.example.keyescrow.model.EscrowedKey;
import com.example.keyescrow.model.KeyVersion;
import com.example.keyescrow.model.WrappingCertificate;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
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
    private final ObjectWriter writer;

    public CustodyManifestWriter() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        // Override only the object indenter to force LF line endings.
        // Arrays are left with the default FixedSpaceIndenter (inline after '['),
        // matching exactly what Jackson INDENT_OUTPUT produces on Linux.
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(new DefaultIndenter("  ", "\n"));
        this.writer = mapper.writer(printer);
    }

    public int write(EscrowExport export, Path manifestPath) throws IOException {
        String json = serialize(export);
        Files.write(manifestPath, json.getBytes(StandardCharsets.UTF_8));
        return countFoldedVersions(export);
    }

    public String serialize(EscrowExport export) throws IOException {
        return writer.writeValueAsString(buildManifest(export));
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

    // total version records across all keys (used for the recordCount field)
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
