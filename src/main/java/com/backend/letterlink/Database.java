package com.backend.letterlink;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class Database {

    public static Connection getConnection() throws Exception {
        String url = System.getenv("TURSO_DB_URL");
        String token = System.getenv("TURSO_AUTH_TOKEN");

        if (isBlank(url)) {
            throw new RuntimeException("TURSO_DB_URL is missing");
        }

        if (isBlank(token)) {
            throw new RuntimeException("TURSO_AUTH_TOKEN is missing");
        }

        Connection conn = DriverManager.getConnection(url, "", token);

        if (conn == null) {
            throw new RuntimeException("DriverManager returned a null database connection");
        }

        return conn;
    }

    public static void ensureSchema(Connection conn) throws Exception {
        if (conn == null) {
            throw new RuntimeException("Connection is null");
        }

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    id TEXT PRIMARY KEY,
                    username TEXT NOT NULL UNIQUE,
                    music_enabled INTEGER NOT NULL DEFAULT 1,
                    sfx_enabled INTEGER NOT NULL DEFAULT 1,
                    vibration_enabled INTEGER NOT NULL DEFAULT 1,
                    theme TEXT NOT NULL DEFAULT 'Cabin',
                    mode TEXT NOT NULL DEFAULT 'practice',
                    wins INTEGER NOT NULL DEFAULT 0,
                    losses INTEGER NOT NULL DEFAULT 0,
                    current_gamemode TEXT NOT NULL DEFAULT 'Standard',
                    current_board_width INTEGER NOT NULL DEFAULT 4,
                    current_board_height INTEGER NOT NULL DEFAULT 4,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS player_mmr (
                    player_id TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    mmr INTEGER NOT NULL DEFAULT 1000,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (player_id, mode)
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS player_sessions (
                    player_id TEXT PRIMARY KEY,
                    auth_token TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS game_sessions (
                    id TEXT PRIMARY KEY,
                    player_id TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    board_width INTEGER NOT NULL,
                    board_height INTEGER NOT NULL,
                    board_letters TEXT NOT NULL,
                    ranked INTEGER NOT NULL DEFAULT 0,
                    time_limit_seconds INTEGER NOT NULL DEFAULT 180,
                    status TEXT NOT NULL,
                    started_at TEXT NOT NULL,
                    finished_at TEXT,
                    submitted_word_count INTEGER,
                    accepted_word_count INTEGER,
                    rejected_word_count INTEGER,
                    accepted_words_json TEXT,
                    rejected_words_json TEXT,
                    validated_score INTEGER,
                    counted_as_win INTEGER,
                    mmr_before INTEGER,
                    mmr_after INTEGER,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
            """);

            ensureColumnExists(stmt, "players", "vibration_enabled",
                "ALTER TABLE players ADD COLUMN vibration_enabled INTEGER NOT NULL DEFAULT 1");
            ensureColumnExists(stmt, "players", "mode",
                "ALTER TABLE players ADD COLUMN mode TEXT NOT NULL DEFAULT 'practice'");

            tryUpdate(stmt, """
                UPDATE players
                SET theme = 'Cabin'
                WHERE theme IS NULL OR TRIM(theme) = '' OR theme = 'default'
            """);

            tryUpdate(stmt, """
                UPDATE players
                SET current_gamemode = 'Standard'
                WHERE current_gamemode IS NULL OR TRIM(current_gamemode) = '' OR current_gamemode = 'casual'
            """);

            tryUpdate(stmt, """
                UPDATE players
                SET mode = 'practice'
                WHERE mode IS NULL OR TRIM(mode) = ''
            """);
        }
    }

    private static void ensureColumnExists(
        Statement stmt,
        String tableName,
        String columnName,
        String sqlIfMissing
    ) throws Exception {
        if (!columnExists(stmt, tableName, columnName)) {
            stmt.execute(sqlIfMissing);
        }
    }

    private static boolean columnExists(Statement stmt, String tableName, String columnName) throws Exception {
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                String existing = rs.getString("name");
                if (columnName.equalsIgnoreCase(existing)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void tryUpdate(Statement stmt, String sql) {
        try {
            stmt.executeUpdate(sql);
        } catch (Exception ignored) {
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
