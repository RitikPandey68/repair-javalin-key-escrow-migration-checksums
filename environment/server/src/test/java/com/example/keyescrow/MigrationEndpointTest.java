package com.example.keyescrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the HTTP surface end to end: the pre-existing health probe must keep
 * responding, and the migration replay must ingest the export, emit the manifest and
 * report the folded record count. The app is started on an ephemeral port so tests
 * never collide with a service already bound to 7070.
 */
class MigrationEndpointTest {

    private static final Path MANIFEST = Path.of("custody_manifest.json");
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void cleanup() throws Exception {
        Files.deleteIfExists(MANIFEST);
    }

    @Test
    void healthStaysUp() throws Exception {
        Javalin app = App.create().start(0);
        try {
            int port = app.port();
            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, res.statusCode());
        } finally {
            app.stop();
        }
    }

    @Test
    void replayReturns200AndEmitsManifest() throws Exception {
        Javalin app = App.create().start(0);
        try {
            int port = app.port();
            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/migrations/replay"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, res.statusCode());

            JsonNode body = mapper.readTree(res.body());
            assertEquals("ok", body.get("status").asText());
            assertEquals("custody_manifest.json", body.get("manifestPath").asText());
            // All folded versions: 3 + 2 + 1 across the three escrowed keys.
            assertEquals(6, body.get("recordCount").asInt());

            assertTrue(Files.exists(MANIFEST), "custody_manifest.json must be emitted");
            JsonNode manifest = mapper.readTree(Files.readAllBytes(MANIFEST));
            assertNotNull(manifest.get("keyVersions"));
        } finally {
            app.stop();
        }
    }
}
