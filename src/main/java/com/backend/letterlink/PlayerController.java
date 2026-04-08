package com.backend.letterlink;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

@RestController
public class PlayerController {

    @GetMapping("/players/register")
    public String registerPlayer(@RequestParam String username) {
        String playerId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        Connection conn = null;

        try {
            conn = Database.getConnection();
            conn.setAutoCommit(false);

            createTablesIfNeeded(conn);

            String insertPlayerSql = """
                INSERT INTO players (
                    id,
                    username,
                    music_enabled,
                    sfx_enabled,
                    theme,
                    wins,
                    losses,
                    current_gamemode,
                    current_board_width,
                    current_board_height,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

            try (PreparedStatement ps = conn.prepareStatement(insertPlayerSql)) {
                ps.setString(1, playerId);
                ps.setString(2, username);
                ps.setInt(3, 1);
                ps.setInt(4, 1);
                ps.setString(5, "default");
                ps.setInt(6, 0);
                ps.setInt(7, 0);
                ps.setString(8, "casual");
                ps.setInt(9, 4);
                ps.setInt(10, 4);
                ps.setString(11, now);
                ps.setString(12, now);
                ps.executeUpdate();
            }

            String insertMmrSql = """
                INSERT INTO player_mmr (
                    player_id,
                    mode,
                    mmr,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?)
            """;

            try (PreparedStatement ps = conn.prepareStatement(insertMmrSql)) {
                insertDefaultMmr(ps, playerId, "4x4", now);
                insertDefaultMmr(ps, playerId, "4x5", now);
                insertDefaultMmr(ps, playerId, "5x5", now);
            }

            conn.commit();

            return """
                {
                  "id":"%s",
                  "username":"%s"
                }
                """.formatted(playerId, username);

        } catch (Exception e) {
            e.printStackTrace();

            try {
                if (conn != null) {
                    conn.rollback();
                }
            } catch (Exception rollbackException) {
                rollbackException.printStackTrace();
            }

            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }

            return "Error registering player | exception=" + e.getClass().getName()
                    + " | message=" + safeMessage(e)
                    + " | root=" + root.getClass().getName()
                    + " | rootMessage=" + safeMessage(root);

        } finally {
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (Exception closeException) {
                closeException.printStackTrace();
            }
        }
    }

    @GetMapping("/players/get")
    public String getPlayerData(@RequestParam String id) {
        String playerSql = """
            SELECT
                id,
                username,
                music_enabled,
                sfx_enabled,
                theme,
                wins,
                losses,
                current_gamemode,
                current_board_width,
                current_board_height,
                created_at,
                updated_at
            FROM players
            WHERE id = ?
        """;

        String mmrSql = """
            SELECT mode, mmr
            FROM player_mmr
            WHERE player_id = ?
        """;

        try (Connection conn = Database.getConnection()) {
            createTablesIfNeeded(conn);

            StringBuilder json = new StringBuilder();

            try (PreparedStatement ps = conn.prepareStatement(playerSql)) {
                ps.setString(1, id);

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return "Player not found";
                    }

                    json.append("{");
                    json.append("\"id\":\"").append(rs.getString("id")).append("\",");
                    json.append("\"username\":\"").append(rs.getString("username")).append("\",");
                    json.append("\"musicEnabled\":").append(rs.getInt("music_enabled") == 1).append(",");
                    json.append("\"sfxEnabled\":").append(rs.getInt("sfx_enabled") == 1).append(",");
                    json.append("\"theme\":\"").append(rs.getString("theme")).append("\",");
                    json.append("\"wins\":").append(rs.getInt("wins")).append(",");
                    json.append("\"losses\":").append(rs.getInt("losses")).append(",");
                    json.append("\"currentGamemode\":\"").append(rs.getString("current_gamemode")).append("\",");
                    json.append("\"currentBoardWidth\":").append(rs.getInt("current_board_width")).append(",");
                    json.append("\"currentBoardHeight\":").append(rs.getInt("current_board_height")).append(",");
                    json.append("\"createdAt\":\"").append(rs.getString("created_at")).append("\",");
                    json.append("\"updatedAt\":\"").append(rs.getString("updated_at")).append("\",");
                    json.append("\"mmr\":{");
                }
            }

            boolean first = true;

            try (PreparedStatement ps = conn.prepareStatement(mmrSql)) {
                ps.setString(1, id);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (!first) {
                            json.append(",");
                        }
                        first = false;

                        json.append("\"")
                                .append(rs.getString("mode"))
                                .append("\":")
                                .append(rs.getInt("mmr"));
                    }
                }
            }

            json.append("}}");
            return json.toString();

        } catch (Exception e) {
            e.printStackTrace();

            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }

            return "Error getting player data | exception=" + e.getClass().getName()
                    + " | message=" + safeMessage(e)
                    + " | root=" + root.getClass().getName()
                    + " | rootMessage=" + safeMessage(root);
        }
    }

    private void insertDefaultMmr(PreparedStatement ps, String playerId, String mode, String now) throws Exception {
        ps.setString(1, playerId);
        ps.setString(2, mode);
        ps.setInt(3, 1000);
        ps.setString(4, now);
        ps.setString(5, now);
        ps.executeUpdate();
    }

    private void createTablesIfNeeded(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    id TEXT PRIMARY KEY,
                    username TEXT NOT NULL UNIQUE,
                    music_enabled INTEGER NOT NULL DEFAULT 1,
                    sfx_enabled INTEGER NOT NULL DEFAULT 1,
                    theme TEXT NOT NULL DEFAULT 'default',
                    wins INTEGER NOT NULL DEFAULT 0,
                    losses INTEGER NOT NULL DEFAULT 0,
                    current_gamemode TEXT NOT NULL DEFAULT 'casual',
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
        }
    }

    private String safeMessage(Throwable t) {
        return t.getMessage() == null ? "<null>" : t.getMessage();
    }
}