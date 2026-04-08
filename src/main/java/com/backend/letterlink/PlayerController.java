package com.backend.letterlink;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/players")
public class PlayerController {

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<ApiModels.RegisterPlayerData>> registerPlayer(
            @RequestBody ApiModels.RegisterPlayerRequest request
    ) {
        String username = request == null ? null : request.username;
        String validationError = validateUsername(username);
        if (validationError != null) {
            return badRequest(validationError);
        }

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
                    %d,
                    %d,
                    '%s',
                    0,
                    0,
                    '%s',
                    %d,
                    %d,
                    '%s',
                    '%s'
                )
            """.formatted(
                    safePlayerId,
                    safeUsername,
                    boolToInt(GameDefaults.DEFAULT_MUSIC_ENABLED),
                    boolToInt(GameDefaults.DEFAULT_SFX_ENABLED),
                    GameDefaults.DEFAULT_THEME,
                    GameDefaults.DEFAULT_GAMEMODE,
                    GameDefaults.DEFAULT_BOARD_WIDTH,
                    GameDefaults.DEFAULT_BOARD_HEIGHT,
                    safeNow,
                    safeNow
            ));

            for (String mode : GameDefaults.ALLOWED_MMR_MODES) {
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
                        '%s',
                        %d,
                        '%s',
                        '%s'
                    )
                """.formatted(
                        safePlayerId,
                        mode,
                        GameDefaults.DEFAULT_MMR,
                        safeNow,
                        safeNow
                ));
            }

            ApiModels.RegisterPlayerData data = new ApiModels.RegisterPlayerData();
            data.id = playerId;
            data.username = username;

            return ok(data);

        } catch (Exception e) {
            e.printStackTrace();
            return serverError("Error registering player", e);
        }
    }

    @GetMapping("/get")
    public ResponseEntity<ApiResponse<ApiModels.PlayerData>> getPlayerData(@RequestParam String id) {
        String validationError = validatePlayerId(id);
        if (validationError != null) {
            return badRequest(validationError);
        }

        try (Connection conn = Database.getConnection();
             Statement stmt = conn.createStatement()) {

            createTablesIfNeeded(stmt);

            ApiModels.PlayerData player = fetchPlayerDataById(conn, id);
            if (player == null) {
                return notFound("Player not found");
            }

            return ok(player);

        } catch (Exception e) {
            e.printStackTrace();
            return serverError("Error getting player data", e);
        }
    }

    @GetMapping("/by-username")
    public ResponseEntity<ApiResponse<ApiModels.PlayerData>> getPlayerByUsername(@RequestParam String username) {
        String validationError = validateUsername(username);
        if (validationError != null) {
            return badRequest(validationError);
        }

        try (Connection conn = Database.getConnection();
             Statement stmt = conn.createStatement()) {

            createTablesIfNeeded(stmt);

            ApiModels.PlayerData player = fetchPlayerDataByUsername(conn, username);
            if (player == null) {
                return notFound("Player not found");
            }

            return ok(player);

        } catch (Exception e) {
            e.printStackTrace();
            return serverError("Error getting player by username", e);
        }
    }

    @PostMapping("/update-settings")
    public ResponseEntity<ApiResponse<ApiModels.UpdatePlayerSettingsData>> updatePlayerSettings(
            @RequestBody ApiModels.UpdatePlayerSettingsRequest request
    ) {
        if (request == null) {
            return badRequest("Request body is required");
        }

        String idError = validatePlayerId(request.id);
        if (idError != null) {
            return badRequest(idError);
        }

        String themeError = validateTheme(request.theme);
        if (themeError != null) {
            return badRequest(themeError);
        }

        String modeError = validateGamemode(request.currentGamemode);
        if (modeError != null) {
            return badRequest(modeError);
        }

        String boardError = validateBoardSize(request.currentBoardWidth, request.currentBoardHeight);
        if (boardError != null) {
            return badRequest(boardError);
        }

        String now = Instant.now().toString();

        try (Connection conn = Database.getConnection();
             Statement stmt = conn.createStatement()) {

            createTablesIfNeeded(stmt);

            ApiModels.PlayerData existing = fetchPlayerDataById(conn, request.id);
            if (existing == null) {
                return notFound("Player not found");
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
                    boolToInt(request.musicEnabled),
                    boolToInt(request.sfxEnabled),
                    escapeSql(request.theme),
                    escapeSql(request.currentGamemode),
                    request.currentBoardWidth,
                    request.currentBoardHeight,
                    escapeSql(now),
                    escapeSql(request.id)
            ));

            ApiModels.UpdatePlayerSettingsData data = new ApiModels.UpdatePlayerSettingsData();
            data.id = request.id;
            data.musicEnabled = request.musicEnabled;
            data.sfxEnabled = request.sfxEnabled;
            data.theme = request.theme;
            data.currentGamemode = request.currentGamemode;
            data.currentBoardWidth = request.currentBoardWidth;
            data.currentBoardHeight = request.currentBoardHeight;
            data.updatedAt = now;

            return ok(data);

        } catch (Exception e) {
            e.printStackTrace();
            return serverError("Error updating player settings", e);
        }
    }

    @PostMapping("/update-stats")
    public ResponseEntity<ApiResponse<ApiModels.UpdatePlayerStatsData>> updatePlayerStats(
            @RequestBody ApiModels.UpdatePlayerStatsRequest request
    ) {
        if (request == null) {
            return badRequest("Request body is required");
        }

        String idError = validatePlayerId(request.id);
        if (idError != null) {
            return badRequest(idError);
        }

        if (request.winsToAdd < 0 || request.lossesToAdd < 0) {
            return badRequest("winsToAdd and lossesToAdd must be 0 or greater");
        }

        String now = Instant.now().toString();

        try (Connection conn = Database.getConnection();
             Statement stmt = conn.createStatement()) {

            createTablesIfNeeded(stmt);

            ApiModels.PlayerData existing = fetchPlayerDataById(conn, request.id);
            if (existing == null) {
                return notFound("Player not found");
            }

            int newWins = existing.wins + request.winsToAdd;
            int newLosses = existing.losses + request.lossesToAdd;

            stmt.execute("""
                UPDATE players
                SET
                    wins = %d,
                    losses = %d,
                    updated_at = '%s'
                WHERE id = '%s'
            """.formatted(
                    newWins,
                    newLosses,
                    escapeSql(now),
                    escapeSql(request.id)
            ));

            ApiModels.UpdatePlayerStatsData data = new ApiModels.UpdatePlayerStatsData();
            data.id = request.id;
            data.wins = newWins;
            data.losses = newLosses;
            data.updatedAt = now;

            return ok(data);

        } catch (Exception e) {
            e.printStackTrace();
            return serverError("Error updating player stats", e);
        }
    }

    @PostMapping("/update-mmr")
    public ResponseEntity<ApiResponse<ApiModels.UpdatePlayerMmrData>> updatePlayerMmr(
            @RequestBody ApiModels.UpdatePlayerMmrRequest request
    ) {
        if (request == null) {
            return badRequest("Request body is required");
        }

        String idError = validatePlayerId(request.id);
        if (idError != null) {
            return badRequest(idError);
        }

        String modeError = validateMmrMode(request.mode);
        if (modeError != null) {
            return badRequest(modeError);
        }

        if (request.mmr < 0) {
            return badRequest("MMR must be 0 or greater");
        }

        String now = Instant.now().toString();

        try (Connection conn = Database.getConnection();
             Statement stmt = conn.createStatement();
             Statement readStmt = conn.createStatement()) {

            createTablesIfNeeded(stmt);

            ApiModels.PlayerData existingPlayer = fetchPlayerDataById(conn, request.id);
            if (existingPlayer == null) {
                return notFound("Player not found");
            }

            String safeId = escapeSql(request.id);
            String safeMode = escapeSql(request.mode);
            String safeNow = escapeSql(now);

            String createdAt = now;

            ResultSet rs = readStmt.executeQuery("""
                SELECT created_at
                FROM player_mmr
                WHERE player_id = '%s' AND mode = '%s'
            """.formatted(safeId, safeMode));

            if (rs.next()) {
                createdAt = rs.getString("created_at");
            }

            stmt.execute("""
                INSERT OR REPLACE INTO player_mmr (
                    player_id,
                    mode,
                    mmr,
                    created_at,
                    updated_at
                )
                VALUES (
                    '%s',
                    '%s',
                    %d,
                    '%s',
                    '%s'
                )
            """.formatted(
                    safeId,
                    safeMode,
                    request.mmr,
                    escapeSql(createdAt),
                    safeNow
            ));

            ApiModels.UpdatePlayerMmrData data = new ApiModels.UpdatePlayerMmrData();
            data.id = request.id;
            data.mode = request.mode;
            data.mmr = request.mmr;
            data.updatedAt = now;

            return ok(data);

        } catch (Exception e) {
            e.printStackTrace();
            return serverError("Error updating player MMR", e);
        }
    }

    private ApiModels.PlayerData fetchPlayerDataById(Connection conn, String id) throws Exception {
        return fetchPlayerData(conn, "id", id);
    }

    private ApiModels.PlayerData fetchPlayerDataByUsername(Connection conn, String username) throws Exception {
        return fetchPlayerData(conn, "username", username);
    }

    private ApiModels.PlayerData fetchPlayerData(Connection conn, String field, String value) throws Exception {
        String safeValue = escapeSql(value);

        try (Statement playerStmt = conn.createStatement();
             Statement mmrStmt = conn.createStatement()) {

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
                WHERE %s = '%s'
            """.formatted(field, safeValue));

            if (!playerRs.next()) {
                return null;
            }

            String playerId = playerRs.getString("id");

            Map<String, Integer> mmrMap = new LinkedHashMap<>();
            mmrMap.put("4x4", GameDefaults.DEFAULT_MMR);
            mmrMap.put("4x5", GameDefaults.DEFAULT_MMR);
            mmrMap.put("5x5", GameDefaults.DEFAULT_MMR);

            ResultSet mmrRs = mmrStmt.executeQuery("""
                SELECT mode, mmr
                FROM player_mmr
                WHERE player_id = '%s'
            """.formatted(escapeSql(playerId)));

            while (mmrRs.next()) {
                mmrMap.put(mmrRs.getString("mode"), mmrRs.getInt("mmr"));
            }

            ApiModels.PlayerData data = new ApiModels.PlayerData();
            data.id = playerId;
            data.username = playerRs.getString("username");
            data.musicEnabled = playerRs.getInt("music_enabled") == 1;
            data.sfxEnabled = playerRs.getInt("sfx_enabled") == 1;
            data.theme = playerRs.getString("theme");
            data.wins = playerRs.getInt("wins");
            data.losses = playerRs.getInt("losses");
            data.currentGamemode = playerRs.getString("current_gamemode");
            data.currentBoardWidth = playerRs.getInt("current_board_width");
            data.currentBoardHeight = playerRs.getInt("current_board_height");
            data.createdAt = playerRs.getString("created_at");
            data.updatedAt = playerRs.getString("updated_at");
            data.mmr = mmrMap;

            return data;
        }
    }

    private void createTablesIfNeeded(Statement stmt) throws Exception {
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS players (
                id TEXT PRIMARY KEY,
                username TEXT NOT NULL UNIQUE,
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
    }

    private String validateUsername(String username) {
        if (username == null || username.isBlank()) {
            return "Username is required";
        }

        if (username.length() < GameDefaults.MIN_USERNAME_LENGTH
                || username.length() > GameDefaults.MAX_USERNAME_LENGTH) {
            return "Username must be between "
                    + GameDefaults.MIN_USERNAME_LENGTH
                    + " and "
                    + GameDefaults.MAX_USERNAME_LENGTH
                    + " characters";
        }

        return null;
    }

    private String validatePlayerId(String id) {
        if (id == null || id.isBlank()) {
            return "Player id is required";
        }
        return null;
    }

    private String validateTheme(String theme) {
        if (theme == null || theme.isBlank()) {
            return "Theme is required";
        }

        if (theme.length() > GameDefaults.MAX_THEME_LENGTH) {
            return "Theme must be " + GameDefaults.MAX_THEME_LENGTH + " characters or less";
        }

        return null;
    }

    private String validateGamemode(String gamemode) {
        if (gamemode == null || gamemode.isBlank()) {
            return "currentGamemode is required";
        }

        if (!GameDefaults.ALLOWED_GAME_MODES.contains(gamemode)) {
            return "Invalid currentGamemode";
        }

        return null;
    }

    private String validateBoardSize(int width, int height) {
        if (!GameDefaults.isAllowedBoardSize(width, height)) {
            return "Invalid board size. Allowed sizes are 4x4, 4x5, and 5x5";
        }
        return null;
    }

    private String validateMmrMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "mode is required";
        }

        if (!GameDefaults.ALLOWED_MMR_MODES.contains(mode)) {
            return "Invalid MMR mode";
        }

        return null;
    }

    private int boolToInt(boolean value) {
        return value ? 1 : 0;
    }

    private String escapeSql(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    private <T> ResponseEntity<ApiResponse<T>> badRequest(String error) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(error));
    }

    private <T> ResponseEntity<ApiResponse<T>> notFound(String error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure(error));
    }

    private <T> ResponseEntity<ApiResponse<T>> serverError(String context, Exception e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }

        String error = context
                + " | exception=" + e.getClass().getName()
                + " | message=" + safeMessage(e)
                + " | root=" + root.getClass().getName()
                + " | rootMessage=" + safeMessage(root);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failure(error));
    }

    private String safeMessage(Throwable t) {
        return t.getMessage() == null ? "<null>" : t.getMessage();
    }
}