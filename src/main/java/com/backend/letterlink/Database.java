package com.backend.letterlink;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {

    private static final Object SCHEMA_LOCK = new Object();
    private static volatile boolean schemaInitialized = false;

    public static Connection getConnection() throws Exception {
        String url = System.getenv("TURSO_DB_URL");
        String token = System.getenv("TURSO_AUTH_TOKEN");

        if (url == null || url.isBlank()) {
            throw new RuntimeException("TURSO_DB_URL is missing");
        }

        if (token == null || token.isBlank()) {
            throw new RuntimeException("TURSO_AUTH_TOKEN is missing");
        }

        Connection conn = DriverManager.getConnection(url, "", token);
        if (conn == null) {
            throw new RuntimeException("DriverManager returned a null database connection");
        }

        return conn;
    }

    public static void ensureSchema(Connection conn) throws Exception {
        if (schemaInitialized) {
            return;
        }

        synchronized (SCHEMA_LOCK) {
            if (schemaInitialized) {
                return;
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS players (
                        id TEXT PRIMARY KEY,
                        username TEXT NOT NULL UNIQUE,
                        auth_token TEXT,
                        music_enabled INTEGER NOT NULL DEFAULT %d,
                        sfx_enabled INTEGER NOT NULL DEFAULT %d,
                        theme TEXT NOT NULL DEFAULT '%s',
                        wins INTEGER NOT NULL DEFAULT 0,
                        losses INTEGER NOT NULL DEFAULT 0,
                        current_gamemode TEXT NOT NULL DEFAULT '%s',
                        current_board_width INTEGER NOT NULL DEFAULT %d,
                        current_board_height INTEGER NOT NULL DEFAULT %d,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                """.formatted(
                    boolToInt(GameDefaults.DEFAULT_MUSIC_ENABLED),
                    boolToInt(GameDefaults.DEFAULT_SFX_ENABLED),
                    GameDefaults.DEFAULT_THEME,
                    GameDefaults.DEFAULT_GAMEMODE,
                    GameDefaults.DEFAULT_BOARD_WIDTH,
                    GameDefaults.DEFAULT_BOARD_HEIGHT
                ));

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_mmr (
                        player_id TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        mmr INTEGER NOT NULL DEFAULT %d,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (player_id, mode)
                    )
                """.formatted(GameDefaults.DEFAULT_MMR));

                addColumnIfMissing(stmt, "ALTER TABLE players ADD COLUMN auth_token TEXT");
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_players_auth_token ON players(auth_token)");
            }

            seedMissingMmrRows(conn);
            schemaInitialized = true;
        }
    }

    private static void seedMissingMmrRows(Connection conn) throws Exception {
        String sql = """
            INSERT OR IGNORE INTO player_mmr (
                player_id,
                mode,
                mmr,
                created_at,
                updated_at
            )
            SELECT
                id,
                ?,
                ?,
                created_at,
                updated_at
            FROM players
        """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (String mode : GameDefaults.ALLOWED_MMR_MODES) {
                stmt.setString(1, mode);
                stmt.setInt(2, GameDefaults.DEFAULT_MMR);
                stmt.executeUpdate();
            }
        }
    }

    private static void addColumnIfMissing(Statement stmt, String sql) throws Exception {
        try {
            stmt.execute(sql);
        } catch (SQLException e) {
            String message = e.getMessage();
            if (message == null || !message.toLowerCase().contains("duplicate column")) {
                throw e;
            }
        }
    }

    private static int boolToInt(boolean value) {
        return value ? 1 : 0;
    }
}
