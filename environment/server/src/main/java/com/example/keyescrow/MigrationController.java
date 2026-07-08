package com.example.keyescrow;

import com.example.keyescrow.model.EscrowExport;
import io.javalin.http.Context;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-facing controller that drives the regulator-mandated migration replay. The
 * replay ingests the export, writes the H2 migration tables and emits the canonical
 * custody manifest, then reports how many custody records were folded into it.
 */
public class MigrationController {

    private static final Path EXPORT_PATH = Path.of("assets", "key-escrow-export.json");
    private static final Path MANIFEST_PATH = Path.of("custody_manifest.json");

    private final ExportIngester ingester;
    private final MigrationWriter migrationWriter;
    private final CustodyManifestWriter manifestWriter;

    public MigrationController() {
        this(new ExportIngester(), new MigrationWriter(), new CustodyManifestWriter());
    }

    public MigrationController(ExportIngester ingester,
                               MigrationWriter migrationWriter,
                               CustodyManifestWriter manifestWriter) {
        this.ingester = ingester;
        this.migrationWriter = migrationWriter;
        this.manifestWriter = manifestWriter;
    }

    public void replay(Context ctx) {
        try {
            EscrowExport export = ingester.ingest(EXPORT_PATH);
            migrationWriter.write(export);
            int recordCount = manifestWriter.write(export, MANIFEST_PATH);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ok");
            body.put("manifestPath", MANIFEST_PATH.toString());
            body.put("recordCount", recordCount);
            ctx.status(200).json(body);
        } catch (Exception e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "error");
            body.put("message", e.getMessage());
            ctx.status(500).json(body);
        }
    }
}
