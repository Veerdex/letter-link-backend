package com.backend.letterlink;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/players")
public class PlayerController {

    private static final String HEADER_PLAYER_ID = "X-Player-Id";
    private static final String HEADER_PLAYER_TOKEN = "X-Player-Token";

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<ApiModels.RegisterPlayerData>> registerPlayer(
        @RequestBody ApiModels.RegisterPlayerRequest request
    ) {
        String username = request == null ? null : request.username;
        String validationError = validateUsername(username);
        if (validationError != null) {
            return badRequest(validationError);
        }

        String now = Instant.now().toString();
        String playerId = UUID.randomUUID().toString();
        String authToken = UUID.randomUUID() + "-" + UUID.randomUUID();

        try (Connection conn = Database.getConnection()) {
            Database.ensureSchema(conn);

            if (usernameExists(conn, username)) {
                return conflict("Username already exists");
            }

            try (PreparedStatement insertPlayer = conn.prepareStatement("""
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
                VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?, ?)
            """)) {
                insertPlayer.setString(1, playerId);
                insertPlayer.setString(2, username);
                insertPlayer.setInt(3, boolToInt(GameDefaults.DEFAULT_MUSIC_ENABLED));
                insertPlayer.setInt(4, boolToInt(GameDefaults.DEFAULT_SFX_ENABLED));
                insertPlayer.setString(5, GameDefaults.DEFAULT_THEME);
                insertPlayer.setString(6, GameDefaults.DEFAULT_GAMEMODE);
                insertPlayer.setInt(7, GameDefaults.DEFAULT_BOARD_WIDTH);
                insertPlayer.setInt(8, GameDefaults.DEFAULT_BOARD_HEIGHT);
                insertPlayer.setString(9, now);
                insertPlayer.setString(10, now);
                insertPlayer.executeUpdate();
            }

            upsertSession(conn, playerId, authToken, now, now);

            try (PreparedStatement insertMmr = conn.prepareStatement("""
                INSERT INTO player_mmr (
                    player_id,
                    mode,
                    mmr,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?)
            """)) {
                for (String mode : GameDefaults.ALLOWED_MMR_MODES) {
                    insertMmr.setString(1, playerId);
                    insertMmr.setString(2, mode);
                    insertMmr.setInt(3, GameDefaults.DEFAULT_MMR);
                    insertMmr.setString(4, now);
                    insertMmr.setString(5, now);
                    insertMmr.addBatch();
                }
                insertMmr.executeBatch();
            } catch (Exception mmrInsertError) {
                cleanupPartialPlayer(conn, playerId);
                throw mmrInsertError;
            }

            ApiModels.RegisterPlayerData data = new ApiModels.RegisterPlayerData();
            data.id = playerId;
            data.username = username;
            data.authToken = authToken;
            return ok(data);

        } catch (SQLException e) {
            if (isUniqueConstraintViolation(e)) {
                return conflict("Username already exists");
            }
            logServerError("Error registering player", e);
            return serverError("Error registering player");
        } catch (Exception e) {
            logServerError("Error registering player", e);
            return serverError("Error registering player");
        }
    }

    @PostMapping("/bootstrap-session")
    public ResponseEntity<ApiResponse<ApiModels.PlayerData>> bootstrapSession(
        @RequestBody ApiModels.BootstrapSessionRequest request
    ) {
        if (request == null) {
            return badRequest("Request body is required");
        }

        String requestedId = firstNonBlank(trimToNull(request.id), trimToNull(request.playerId));
        String validationError = validatePlayerId(requestedId);
        if (validationError != null) {
            return badRequest(validationError);
        }

        String newToken = UUID.randomUUID() + "-" + UUID.randomUUID();
        String now = Instant.now().toString();

        try (Connection conn = Database.getConnection()) {
            Database.ensureSchema(conn);

            ApiModels.PlayerData data = fetchPlayerDataById(conn, requestedId);
            if (data == null) {
                return notFound("Player not found");
            }

            if (hasAnySession(conn, requestedId)) {
                return unauthorized("Session token required for this player");
            }

            upsertSession(conn, requestedId, newToken, now, now);
            data.authToken = newToken;
            return ok(data);

        } catch (Exception e) {
            logServerError("Error bootstrapping session", e);
            return serverError("Error bootstrapping session");
        }
    }

    @GetMapping("/get")
    public ResponseEntity<ApiResponse<ApiModels.PlayerData>> getPlayerData(
        @RequestParam String id,
        @RequestHeader(value = HEADER_PLAYER_ID, required = false) String authPlayerId,
        @RequestHeader(value = HEADER_PLAYER_TOKEN, required = false) String authToken
    ) {
        String validationError = validatePlayerId(id);
        if (validationError != null) {
            return badRequest(validationError);
        }

        try (Connection conn = Database.getConnection()) {
            Database.ensureSchema(conn);

            if (!isAuthenticated(conn, id, authPlayerId, authToken)) {
                return unauthorized("Invalid player credentials");
            }

            ApiModels.PlayerData player = fetchPlayerDataById(conn, id);
            if (player == null) {
                return notFound("Player not found");
            }

            player.authToken = null;
            return ok(player);

        } catch (Exception e) {
            logServerError("Error getting player data", e);
            return serverError("Error getting player data");
        }
    }

    @GetMapping("/by-username")
    public ResponseEntity<ApiResponse<ApiModels.PlayerData>> getPlayerByUsername(
        @RequestParam String username,
        @RequestHeader(value = HEADER_PLAYER_ID, required = false) String authPlayerId,
        @RequestHeader(value = HEADER_PLAYER_TOKEN, required = false) String authToken
    ) {
        String validationError = validateUsername(username);
        if (validationError != null) {
            return badRequest(validationError);
        }

        try (Connection conn = Database.getConnection()) {
            Database.ensureSchema(conn);

            if (!hasValidSession(conn, authPlayerId, authToken)) {
                return unauthorized("Invalid player credentials");
            }

            ApiModels.PlayerData player = fetchPlayerDataByUsername(conn, username);
            if (player == null) {
                return notFound("Player not found");
            }

            if (!player.id.equals(authPlayerId)) {
                return forbidden("Full player data can only be fetched for the authenticated player");
            }

            player.authToken = null;
            return ok(player);

        } catch (Exception e) {
            logServerError("Error getting player by username", e);
            return serverError("Error getting player by username");
        }
    }

    @PostMapping("/update-settings")
    public ResponseEntity<ApiResponse<ApiModels.UpdatePlayerSettingsData>> updatePlayerSettings(
        @RequestBody ApiModels.UpdatePlayerSettingsRequest request,
        @RequestHeader(value = HEADER_PLAYER_ID, required = false) String authPlayerId,
        @RequestHeader(value = HEADER_PLAYER_TOKEN, required = false) String authToken
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

        try (Connection conn = Database.getConnection()) {
            Database.ensureSchema(conn);

            if (!isAuthenticated(conn, request.id, authPlayerId, authToken)) {
                return unauthorized("Invalid player credentials");
            }

            try (PreparedStatement update = conn.prepareStatement("""
                UPDATE players
                SET
                    music_enabled = ?,
                    sfx_enabled = ?,
                    theme = ?,
                    current_gamemode = ?,
                    current_board_width = ?,
                    current_board_height = ?,
                    updated_at = ?
                WHERE id = ?
            """)) {
                update.setInt(1, boolToInt(request.musicEnabled));
                update.setInt(2, boolToInt(request.sfxEnabled));
                update.setString(3, request.theme);
                update.setString(4, request.currentGamemode);
                update.setInt(5, request.currentBoardWidth);
                update.setInt(6, request.currentBoardHeight);
                update.setString(7, now);
                update.setString(8, request.id);

                if (update.executeUpdate() == 0) {
                    return notFound("Player not found");
                }
            }

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
            logServerError("Error updating player settings", e);
            return serverError("Error updating player settings");
        }
    }

    @PostMapping("/update-stats")
    public ResponseEntity<ApiResponse<ApiModels.UpdatePlayerStatsData>> updatePlayerStats(
        @RequestBody ApiModels.UpdatePlayerStatsRequest request,
        @RequestHeader(value = HEADER_PLAYER_ID, required = false) String authPlayerId,
        @RequestHeader(value = HEADER_PLAYER_TOKEN, required = false) String authToken
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

        try (Connection conn = Database.getConnection()) {
            Database.ensureSchema(conn);

            if (!isAuthenticated(conn, request.id, authPlayerId, authToken)) {
                return unauthorized("Invalid player credentials");
            }

            try (PreparedStatement update = conn.prepareStatement("""
                UPDATE players
                SET
                    wins = wins + ?,
                    losses = losses + ?,
                    updated_at = ?
                WHERE id = ?
            """)) {
                update.setInt(1, request.winsToAdd);
                update.setInt(2, request.lossesToAdd);
                update.setString(3, now);
                update.setString(4, request.id);

                if (update.executeUpdate() == 0) {
                    return notFound("Player not found");
                }
            }

            ApiModels.PlayerData player = fetchPlayerDataById(conn, request.id);
            if (player == null) {
                return notFound("Player not found");
            }

            ApiModels.UpdatePlayerStatsData data = new ApiModels.UpdatePlayerStatsData();
            data.id = request.id;
            data.wins = player.wins;
            data.losses = player.losses;
            data.updatedAt = player.updatedAt;
            return ok(data);

        } catch (Exception e) {
            logServerError("Error updating player stats", e);
            return serverError("Error updating player stats");
        }
    }

    @PostMapping("/update-mmr")
    public ResponseEntity<ApiResponse<ApiModels.UpdatePlayerMmrData>> updatePlayerMmr(
        @RequestBody ApiModels.UpdatePlayerMmrRequest request,
        @RequestHeader(value = HEADER_PLAYER_ID, required = false) String authPlayerId,
        @RequestHeader(value = HEADER_PLAYER_TOKEN, required = false) String authToken
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

        try (Connection conn = Database.getConnection()) {
            Database.ensureSchema(conn);

            if (!isAuthenticated(conn, request.id, authPlayerId, authToken)) {
                return unauthorized("Invalid player credentials");
            }

            String createdAt = now;
            try (PreparedStatement readCreatedAt = conn.prepareStatement("""
                SELECT created_at
                FROM player_mmr
                WHERE player_id = ? AND mode = ?
            """)) {
                readCreatedAt.setString(1, request.id);
                readCreatedAt.setString(2, request.mode);

                try (ResultSet rs = readCreatedAt.executeQuery()) {
                    if (rs.next()) {
                        createdAt = rs.getString("created_at");
                    }
                }
            }

            try (PreparedStatement upsert = conn.prepareStatement("""
                INSERT INTO player_mmr (
                    player_id,
                    mode,
                    mmr,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(player_id, mode)
                DO UPDATE SET
                    mmr = excluded.mmr,
                    updated_at = excluded.updated_at
            """)) {
                upsert.setString(1, request.id);
                upsert.setString(2, request.mode);
                upsert.setInt(3, request.mmr);
                upsert.setString(4, createdAt);
                upsert.setString(5, now);
                upsert.executeUpdate();
            }

            ApiModels.UpdatePlayerMmrData data = new ApiModels.UpdatePlayerMmrData();
            data.id = request.id;
            data.mode = request.mode;
            data.mmr = request.mmr;
            data.updatedAt = now;
            return ok(data);

        } catch (Exception e) {
            logServerError("Error updating player MMR", e);
            return serverError("Error updating player MMR");
        }
    }

    private ApiModels.PlayerData fetchPlayerDataById(Connection conn, String id) throws Exception {
        return fetchPlayerData(conn, "id", id);
    }

    private ApiModels.PlayerData fetchPlayerDataByUsername(Connection conn, String username) throws Exception {
        return fetchPlayerData(conn, "username", username);
    }

    private ApiModels.PlayerData fetchPlayerData(Connection conn, String field, String value) throws Exception {
        String sql = """
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
            WHERE %s = ?
        """.formatted(field);

        try (PreparedStatement playerStmt = conn.prepareStatement(sql)) {
            playerStmt.setString(1, value);

            try (ResultSet playerRs = playerStmt.executeQuery()) {
                if (!playerRs.next()) {
                    return null;
                }

                String playerId = playerRs.getString("id");
                Map<String, Integer> mmrMap = new LinkedHashMap<>();
                mmrMap.put("4x4", GameDefaults.DEFAULT_MMR);
                mmrMap.put("4x5", GameDefaults.DEFAULT_MMR);
                mmrMap.put("5x5", GameDefaults.DEFAULT_MMR);

                try (PreparedStatement mmrStmt = conn.prepareStatement("""
                    SELECT mode, mmr
                    FROM player_mmr
                    WHERE player_id = ?
                """)) {
                    mmrStmt.setString(1, playerId);

                    try (ResultSet mmrRs = mmrStmt.executeQuery()) {
                        while (mmrRs.next()) {
                            mmrMap.put(mmrRs.getString("mode"), mmrRs.getInt("mmr"));
                        }
                    }
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
                data.authToken = null;
                return data;
            }
        }
    }

    private boolean hasAnySession(Connection conn, String playerId) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement("""
            SELECT 1
            FROM player_sessions
            WHERE player_id = ?
        """)) {
            stmt.setString(1, playerId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean hasValidSession(Connection conn, String authPlayerId, String authToken) throws Exception {
        if (authPlayerId == null || authPlayerId.isBlank() || authToken == null || authToken.isBlank()) {
            return false;
        }

        try (PreparedStatement stmt = conn.prepareStatement("""
            SELECT 1
            FROM player_sessions
            WHERE player_id = ? AND auth_token = ?
        """)) {
            stmt.setString(1, authPlayerId);
            stmt.setString(2, authToken);

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean isAuthenticated(Connection conn, String requestPlayerId, String authPlayerId, String authToken) throws Exception {
        if (requestPlayerId == null || !requestPlayerId.equals(authPlayerId)) {
            return false;
        }

        return hasValidSession(conn, authPlayerId, authToken);
    }

    private boolean usernameExists(Connection conn, String username) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement("""
            SELECT 1
            FROM players
            WHERE username = ?
        """)) {
            stmt.setString(1, username);

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void upsertSession(Connection conn, String playerId, String authToken, String createdAt, String updatedAt) throws Exception {
        String existingCreatedAt = createdAt;

        try (PreparedStatement read = conn.prepareStatement("""
            SELECT created_at
            FROM player_sessions
            WHERE player_id = ?
        """)) {
            read.setString(1, playerId);
            try (ResultSet rs = read.executeQuery()) {
                if (rs.next()) {
                    existingCreatedAt = rs.getString("created_at");
                }
            }
        }

        try (PreparedStatement write = conn.prepareStatement("""
            INSERT INTO player_sessions (
                player_id,
                auth_token,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, ?)
            ON CONFLICT(player_id)
            DO UPDATE SET
                auth_token = excluded.auth_token,
                updated_at = excluded.updated_at
        """)) {
            write.setString(1, playerId);
            write.setString(2, authToken);
            write.setString(3, existingCreatedAt);
            write.setString(4, updatedAt);
            write.executeUpdate();
        }
    }

    private void cleanupPartialPlayer(Connection conn, String playerId) {
        try (PreparedStatement deleteSession = conn.prepareStatement("DELETE FROM player_sessions WHERE player_id = ?");
             PreparedStatement deleteMmr = conn.prepareStatement("DELETE FROM player_mmr WHERE player_id = ?");
             PreparedStatement deletePlayer = conn.prepareStatement("DELETE FROM players WHERE id = ?")) {
            deleteSession.setString(1, playerId);
            deleteSession.executeUpdate();
            deleteMmr.setString(1, playerId);
            deleteMmr.executeUpdate();
            deletePlayer.setString(1, playerId);
            deletePlayer.executeUpdate();
        } catch (Exception cleanupError) {
            System.err.println("Failed to clean up partial player registration for id=" + playerId);
            cleanupError.printStackTrace();
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    private boolean isUniqueConstraintViolation(SQLException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }

        String lower = message.toLowerCase();
        return lower.contains("unique") || lower.contains("constraint");
    }

    private void logServerError(String context, Exception e) {
        System.err.println(context);
        e.printStackTrace();
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

    private <T> ResponseEntity<ApiResponse<T>> unauthorized(String error) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.failure(error));
    }

    private <T> ResponseEntity<ApiResponse<T>> forbidden(String error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.failure(error));
    }

    private <T> ResponseEntity<ApiResponse<T>> conflict(String error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure(error));
    }

    private <T> ResponseEntity<ApiResponse<T>> serverError(String error) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failure(error));
    }
}
