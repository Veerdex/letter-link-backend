package com.backend.letterlink;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
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

        try (Connection conn = Database.getConnection();
             Statement stmt = conn.createStatement()) {

            createTablesIfNeeded(stmt);

            String safeUsername = escapeSql(username);
            String safeNow = escapeSql(now);
            String safePlayerId = escapeSql(playerId);

            stmt.execute("""
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
                VALUES (
                    '%s',
                    '%s',
                    1,
                    1,
                    'default',
                    0,
                    0,
                    'casual',
                    4,
                    4,
                    '%s',
                    '%s'
                )
            """.formatted(safePlayerId, safeUsername, safeNow, safeNow));

            stmt.execute("""
                INSERT INTO player_mmr (
                    player_id,
                    mode,
                    mmr,
                    created_at,
                    updated_at
                )
                VALUES (
                    '%s',
                    '4x4',
                    1000,
                    '%s',
                    '%s'
                )
            """.formatted(safePlayerId, safeNow, safeNow));

            stmt.execute("""
                INSERT INTO player_mmr (
                    player_id,
                    mode,
                    mmr,
                    created_at,
                    updated_at
                )
                VALUES (
                    '%s',
                    '4x5',
                    1000,
                    '%s',
                    '%s'
                )
            """.formatted(safePlayerId, safeNow, safeNow));

            stmt.execute("""
                INSERT INTO player_mmr (
                    player_id,
                    mode,
                    mmr,
                    created_at,
                    updated_at
                )
                VALUES (
                    '%s',
                    '5x5',
                    1000,
                    '%s',
                    '%s'
                )
            """.formatted(safePlayerId, safeNow, safeNow));

            return """
                {
                  "id":"%s",
                  "username":"%s"
                }
                """.formatted(playerId, username);

        } catch (Exception e) {
            e.printStackTrace();

            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }

            return "Error registering player | exception=" + e.getClass().getName()
                    + " | message=" + safeMessage(e)
                    + " | root=" + root.getClass().getName()
                    + " | rootMessage=" + safeMessage(root);
        }
    }

    @GetMapping("/players/get")
    public String getPlayerData(@RequestParam String id) {
        String safeId = escapeSql(id);

        try (Connection conn = Database.getConnection();
             Statement tableStmt = conn.createStatement();
             Statement playerStmt = conn.createStatement();
             Statement mmrStmt = conn.createStatement()) {

            createTablesIfNeeded(tableStmt);

            ResultSet playerRs = playerStmt.executeQuery("""
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
                WHERE id = '%s'
            """.formatted(safeId));

            if (!playerRs.next()) {
                return "Player not found";
            }

            int mmr4x4 = 1000;
            int mmr4x5 = 1000;
            int mmr5x5 = 1000;

            ResultSet mmrRs = mmrStmt.executeQuery("""
                SELECT mode, mmr
                FROM player_mmr
                WHERE player_id = '%s'
            """.formatted(safeId));

            while (mmrRs.next()) {
                String mode = mmrRs.getString("mode");
                int mmr = mmrRs.getInt("mmr");

                switch (mode) {
                    case "4x4" -> mmr4x4 = mmr;
                    case "4x5" -> mmr4x5 = mmr;
                    case "5x5" -> mmr5x5 = mmr;
                }
            }

            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"id\":\"").append(playerRs.getString("id")).append("\",");
            json.append("\"username\":\"").append(playerRs.getString("username")).append("\",");
            json.append("\"musicEnabled\":").append(playerRs.getInt("music_enabled") == 1).append(",");
            json.append("\"sfxEnabled\":").append(playerRs.getInt("sfx_enabled") == 1).append(",");
            json.append("\"theme\":\"").append(playerRs.getString("theme")).append("\",");
            json.append("\"wins\":").append(playerRs.getInt("wins")).append(",");
            json.append("\"losses\":").append(playerRs.getInt("losses")).append(",");
            json.append("\"currentGamemode\":\"").append(playerRs.getString("current_gamemode")).append("\",");
            json.append("\"currentBoardWidth\":").append(playerRs.getInt("current_board_width")).append(",");
            json.append("\"currentBoardHeight\":").append(playerRs.getInt("current_board_height")).append(",");
            json.append("\"createdAt\":\"").append(playerRs.getString("created_at")).append("\",");
            json.append("\"updatedAt\":\"").append(playerRs.getString("updated_at")).append("\",");
            json.append("\"mmr\":{");
            json.append("\"4x4\":").append(mmr4x4).append(",");
            json.append("\"4x5\":").append(mmr4x5).append(",");
            json.append("\"5x5\":").append(mmr5x5);
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

    @GetMapping("/players/update-settings")
    public String updatePlayerSettings(
            @RequestParam String id,
            @RequestParam boolean musicEnabled,
            @RequestParam boolean sfxEnabled,
            @RequestParam String theme,
            @RequestParam String currentGamemode,
            @RequestParam int currentBoardWidth,
            @RequestParam int currentBoardHeight
    ) {
        String safeId = escapeSql(id);
        String safeTheme = escapeSql(theme);
        String safeGamemode = escapeSql(currentGamemode);
        String now = Instant.now().toString();
        String safeNow = escapeSql(now);

        int musicValue = musicEnabled ? 1 : 0;
        int sfxValue = sfxEnabled ? 1 : 0;

        try (Connection conn = Database.getConnection();
             Statement stmt = conn.createStatement()) {

            createTablesIfNeeded(stmt);

            ResultSet rs = stmt.executeQuery("""
                SELECT COUNT(*) AS count
                FROM players
                WHERE id = '%s'
            """.formatted(safeId));

            if (!rs.next() || rs.getInt("count") == 0) {
                return "Player not found";
            }

            stmt.execute("""
                UPDATE players
                SET
                    music_enabled = %d,
                    sfx_enabled = %d,
                    theme = '%s',
                    current_gamemode = '%s',
                    current_board_width = %d,
                    current_board_height = %d,
                    updated_at = '%s'
                WHERE id = '%s'
            """.formatted(
                    musicValue,
                    sfxValue,
                    safeTheme,
                    safeGamemode,
                    currentBoardWidth,
                    currentBoardHeight,
                    safeNow,
                    safeId
            ));

            return """
                {
                  "success":true,
                  "id":"%s",
                  "musicEnabled":%s,
                  "sfxEnabled":%s,
                  "theme":"%s",
                  "currentGamemode":"%s",
                  "currentBoardWidth":%d,
                  "currentBoardHeight":%d,
                  "updatedAt":"%s"
                }
                """.formatted(
                    id,
                    musicEnabled,
                    sfxEnabled,
                    theme,
                    currentGamemode,
                    currentBoardWidth,
                    currentBoardHeight,
                    now
            );

        } catch (Exception e) {
            e.printStackTrace();

            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }

            return "Error updating player settings | exception=" + e.getClass().getName()
                    + " | message=" + safeMessage(e)
                    + " | root=" + root.getClass().getName()
                    + " | rootMessage=" + safeMessage(root);
        }
    }

    private void createTablesIfNeeded(Statement stmt) throws Exception {
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

    private String escapeSql(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }

    private String safeMessage(Throwable t) {
        return t.getMessage() == null ? "<null>" : t.getMessage();
    }
}