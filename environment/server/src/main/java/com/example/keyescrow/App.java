package com.example.keyescrow;

import io.javalin.Javalin;

/**
 * Javalin bootstrap for the key-escrow migration replay service. Wires the admin
 * migration routes and the health probe, then binds to the configured port.
 */
public class App {

    public static final int PORT = 7070;

    public static Javalin create() {
        Javalin app = Javalin.create();
        MigrationController migrations = new MigrationController();

        app.get("/health", ctx -> ctx.status(200).json(java.util.Map.of("status", "up")));
        app.post("/admin/migrations/replay", migrations::replay);

        return app;
    }

    public static void main(String[] args) {
        create().start(PORT);
    }
}
