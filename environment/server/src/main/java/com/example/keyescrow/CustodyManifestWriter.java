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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and serializes the custody manifest JSON from a parsed escrow export.
 * The resulting file is written to disk and its path is returned to the caller
 * via the migration replay endpoint.
 */
public class CustodyManifestWriter {

    private final ObjectMapper mapper;

    public CustodyManifestWriter() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
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
        Map<String, Object> root = new LinkedHashMap<>();

        root.put("manifestVersion", 1);
        root.put("generatedFrom", export.exportId);
        root.put("custodyGrants", buildGrants(export));
        root.put("keyVersions", buildKeyVersions(export));
        root.put("wrappingCertificates", buildCertificates(export));
        root.put("recordCount", countFoldedVersions(export));

        return root;
    }

    private List<Map<String, Object>> buildGrants(EscrowExport export) {
        List<CustodyGrant> grants = new ArrayList<>(export.custodyGrants);
        grants.sort(Comparator.comparing(g -> g.grantId));

        List<Map<String, Object>> out = new ArrayList<>();
        for (CustodyGrant grant : grants) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("grantId", grant.grantId);
            node.put("principal", grant.principal.trim());
            node.put("certId", normalizeKeyId(grant.certId));
            node.put("grantedEpoch", grant.grantedEpoch);
            out.add(node);
        }
        return out;
    }

    private List<Map<String, Object>> buildKeyVersions(EscrowExport export) {
        List<EscrowedKey> keys = new ArrayList<>(export.escrowedKeys);
        keys.sort(Comparator.comparing(k -> normalizeKeyId(k.keyId)));

        List<Map<String, Object>> out = new ArrayList<>();
        for (EscrowedKey key : keys) {
            Map<String, Object> keyNode = new LinkedHashMap<>();
            keyNode.put("keyId", normalizeKeyId(key.keyId));

            List<KeyVersion> versions = new ArrayList<>(key.versions);
            versions.sort(Comparator.comparingInt(v -> v.version));

            // grab the active/latest version for the manifest entry
            KeyVersion latest = versions.get(versions.size() - 1);
            Map<String, Object> vNode = new LinkedHashMap<>();
            vNode.put("version", latest.version);
            vNode.put("algorithm", latest.algorithm.toUpperCase());
            vNode.put("custodyStatus", latest.custodyStatus);
            vNode.put("createdEpoch", latest.createdEpoch);

            keyNode.put("versions", List.of(vNode));
            out.add(keyNode);
        }
        return out;
    }

    private List<Map<String, Object>> buildCertificates(EscrowExport export) {
        List<WrappingCertificate> certs = new ArrayList<>(export.wrappingCertificates);
        certs.sort(Comparator.comparing(c -> c.certId));

        List<Map<String, Object>> out = new ArrayList<>();
        for (WrappingCertificate cert : certs) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("certId", cert.certId);
            node.put("keyId", normalizeKeyId(cert.keyId));
            node.put("notAfterEpoch", cert.notAfterEpoch);
            node.put("subject", collapseWhitespace(cert.subject));
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
