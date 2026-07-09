package com.example.keyescrow;

import com.example.keyescrow.model.CustodyGrant;
import com.example.keyescrow.model.EscrowExport;
import com.example.keyescrow.model.EscrowedKey;
import com.example.keyescrow.model.KeyVersion;
import com.example.keyescrow.model.WrappingCertificate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Populates the H2-backed migration tables from a parsed export. The store lives at
 * {@code ./data/migration} relative to the working directory; the schema is applied
 * from the bundled DDL before any rows are written so a clean container run works
 * without an out-of-band migration step.
 */
public class MigrationWriter {

    private static final String JDBC_URL = "jdbc:h2:./data/migration";

    public void write(EscrowExport export) throws SQLException, IOException {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "")) {
            conn.setAutoCommit(false);
            applySchema(conn);
            truncate(conn);
            insertKeyVersions(conn, export);
            insertWrappingCertificates(conn, export);
            insertCustodyGrants(conn, export);
            conn.commit();
        }
    }

    private void applySchema(Connection conn) throws SQLException, IOException {
        String ddl = readResource("/migration_schema.sql");
        try (Statement st = conn.createStatement()) {
            for (String stmt : ddl.split(";")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
        }
    }

    private void truncate(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM key_versions");
            st.execute("DELETE FROM wrapping_certificates");
            st.execute("DELETE FROM custody_grants");
        }
    }

    private void insertKeyVersions(Connection conn, EscrowExport export) throws SQLException {
        String sql = "INSERT INTO key_versions "
                + "(key_id, version, algorithm, custody_status, created_epoch) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (EscrowedKey key : export.escrowedKeys) {
                for (KeyVersion v : key.versions) {
                    ps.setString(1, key.keyId);
                    ps.setInt(2, v.version);
                    ps.setString(3, v.algorithm);
                    ps.setString(4, v.custodyStatus);
                    ps.setLong(5, v.createdEpoch);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private void insertWrappingCertificates(Connection conn, EscrowExport export) throws SQLException {
        String sql = "INSERT INTO wrapping_certificates "
                + "(cert_id, subject, key_id, not_after_epoch) VALUES (?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (WrappingCertificate cert : export.wrappingCertificates) {
                ps.setString(1, cert.certId);
                ps.setString(2, cert.subject);
                ps.setString(3, cert.keyId);
                ps.setLong(4, cert.notAfterEpoch);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertCustodyGrants(Connection conn, EscrowExport export) throws SQLException {
        String sql = "INSERT INTO custody_grants "
                + "(grant_id, principal, cert_id, revoked, granted_epoch) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (CustodyGrant grant : export.custodyGrants) {
                ps.setString(1, grant.grantId);
                ps.setString(2, grant.principal);
                ps.setString(3, grant.certId);
                ps.setBoolean(4, grant.revoked);
                ps.setLong(5, grant.grantedEpoch);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private String readResource(String name) throws IOException {
        String cleanPath = name.startsWith("/") ? name.substring(1) : name;
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(cleanPath);
        if (in == null) {
            ClassLoader classLoader = MigrationWriter.class.getClassLoader();
            if (classLoader != null) {
                in = classLoader.getResourceAsStream(cleanPath);
            }
        }
        if (in == null) {
            in = MigrationWriter.class.getResourceAsStream(name);
        }
        if (in == null) {
            throw new IOException("Bundled resource missing: " + name);
        }
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
