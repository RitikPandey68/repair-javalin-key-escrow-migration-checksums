package com.example.keyescrow;

import com.example.keyescrow.model.EscrowExport;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Parses the nested key-escrow export JSON into the {@link EscrowExport} object
 * graph. The export is read relative to the service working directory, matching the
 * regulator's packaging convention for the migration bundle.
 */
public class ExportIngester {

    private final ObjectMapper mapper;

    public ExportIngester() {
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public EscrowExport ingest(Path exportPath) throws IOException {
        if (!Files.exists(exportPath)) {
            throw new IOException("Key-escrow export not found at " + exportPath.toAbsolutePath());
        }
        byte[] raw = Files.readAllBytes(exportPath);
        return mapper.readValue(raw, EscrowExport.class);
    }
}
