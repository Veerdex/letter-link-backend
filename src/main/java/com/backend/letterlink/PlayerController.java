package com.backend.letterlink;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/players")
public class PlayerController {

    private static final String HEADER_PLAYER_ID = "X-Player-Id";
    private static final String HEADER_PLAYER_TOKEN = "X-Player-Token";

    private static final boolean DEBUG_ERRORS = readDebugErrorsFlag();
    private static final AtomicLong ERROR_COUNTER = new AtomicLong(1000);

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
            debug("register: opened connection for username=" + username);
            Database.ensureSchema(conn);
            debug("register: schema ensured for username=" + username);

            if (usernameExists(conn, username)) {
                debug("register: username already exists: " + username);
                return conflict("Username already exists");
            }

            try (Statement stmt = conn.createStatement()) {
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
                    escapeSql(playerId),
                    escapeSql(username),
                    boolToInt(GameDefaults.DEFAULT_MUSIC_ENABLED),
                    boolToInt(GameDefaults.DEFAULT_SFX_ENABLED),
                    escapeSql(GameDefaults.DEFAULT_THEME),
                    escapeSql(GameDefaults.DEFAULT_GAMEMODE),
                    GameDefaults.DEFAULT_BOARD_WIDTH,
                    GameDefaults.DEFAULT_BOARD_HEIGHT,
                    escapeSql(now),
                    escapeSql(now)
                ));
                debug("register: inserted player, playerId=" + playerId);
            }

            upsertSession(conn, playerId, authToken, now, now);
            debug("register: session upserted for playerId=" + playerId);

            try (Statement stmt = conn.createStatement()) {
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
                        escapeSql(playerId),
                        escapeSql(mode),
                        GameDefaults.DEFAULT_MMR,
                        escapeSql(now),
                        escapeSql(now)
                    ));
                    debug("register: inserted MMR row mode=" + mode + ", playerId=" + playerId);
                }
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
            return serverError("Error registering player", e);
        } catch (Exception e) {
            return serverError("Error registering player", e);
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
            return serverError("Error bootstrapping session", e);
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
            return serverError("Error getting player data", e);
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
            return serverError("Error getting player by username", e);
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

            try (Statement stmt = conn.createStatement()) {
                int updated = stmt.executeUpdate("""
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
                if (updated == 0) {
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
            return serverError("Error updating player settings", e);
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

            try (Statement stmt = conn.createStatement()) {
                int updated = stmt.executeUpdate("""
                    UPDATE players
                    SET
                        wins = wins + %d,
                        losses = losses + %d,
                        updated_at = '%s'
                    WHERE id = '%s'
                """.formatted(
                    request.winsToAdd,
                    request.lossesToAdd,
                    escapeSql(now),
                    escapeSql(request.id)
                ));
                if (updated == 0) {
                    return notFound("Player not found");
                }
            }

            ApiModels.PlayerData player = fetchPlayerDataById(conn, request.id);
            if (player == null) {
                return notFound("Player not found");
            }

            ApiModels.UpdatePlayerStatsData data = new ApiModels.UpdatePlayerStatsData();
            data.id = player.id;
            data.wins = player.wins;
            data.losses = player.losses;
            data.updatedAt = player.updatedAt;
            return ok(data);

        } catch (Exception e) {
            return serverError("Error updating player stats", e);
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

            String safeId = escapeSql(request.id);
            String safeMode = escapeSql(request.mode);
            String safeNow = escapeSql(now);
            String createdAt = now;

            try (Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("""
                    SELECT created_at
                    FROM player_mmr
                    WHERE player_id = '%s' AND mode = '%s'
                """.formatted(safeId, safeMode))) {
                    if (rs.next()) {
                        createdAt = rs.getString("created_at");
                    }
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
            }

            ApiModels.UpdatePlayerMmrData data = new ApiModels.UpdatePlayerMmrData();
            data.id = request.id;
            data.mode = request.mode;
            data.mmr = request.mmr;
            data.updatedAt = now;
            return ok(data);

        } catch (Exception e) {
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

            try (ResultSet playerRs = playerStmt.executeQuery("""
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
            """.formatted(field, safeValue))) {
                if (!playerRs.next()) {
                    return null;
                }

                String playerId = playerRs.getString("id");
                Map<String, Integer> mmrMap = new LinkedHashMap<>();
                mmrMap.put("4x4", GameDefaults.DEFAULT_MMR);
                mmrMap.put("4x5", GameDefaults.DEFAULT_MMR);
                mmrMap.put("5x5", GameDefaults.DEFAULT_MMR);

                try (ResultSet mmrRs = mmrStmt.executeQuery("""
                    SELECT mode, mmr
                    FROM player_mmr
                    WHERE player_id = '%s'
                """.formatted(escapeSql(playerId)))) {
                    while (mmrRs.next()) {
                        mmrMap.put(mmrRs.getString("mode"), mmrRs.getInt("mmr"));
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
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT 1
                 FROM player_sessions
                 WHERE player_id = '%s'
             """.formatted(escapeSql(playerId)))) {
            return rs.next();
        }
    }

    private boolean hasValidSession(Connection conn, String authPlayerId, String authToken) throws Exception {
        if (authPlayerId == null || authPlayerId.isBlank() || authToken == null || authToken.isBlank()) {
            return false;
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT 1
                 FROM player_sessions
                 WHERE player_id = '%s' AND auth_token = '%s'
             """.formatted(escapeSql(authPlayerId), escapeSql(authToken)))) {
            return rs.next();
        }
    }

    private boolean isAuthenticated(Connection conn, String requestPlayerId, String authPlayerId, String authToken) throws Exception {
        if (requestPlayerId == null || !requestPlayerId.equals(authPlayerId)) {
            return false;
        }

        return hasValidSession(conn, authPlayerId, authToken);
    }

    private boolean usernameExists(Connection conn, String username) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT 1
                 FROM players
                 WHERE username = '%s'
             """.formatted(escapeSql(username)))) {
            return rs.next();
        }
    }

    private void upsertSession(Connection conn, String playerId, String authToken, String createdAt, String updatedAt) throws Exception {
        String existingCreatedAt = createdAt;

        try (Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("""
                SELECT created_at
                FROM player_sessions
                WHERE player_id = '%s'
            """.formatted(escapeSql(playerId)))) {
                if (rs.next()) {
                    existingCreatedAt = rs.getString("created_at");
                }
            }

            stmt.execute("""
                INSERT OR REPLACE INTO player_sessions (
                    player_id,
                    auth_token,
                    created_at,
                    updated_at
                )
                VALUES (
                    '%s',
                    '%s',
                    '%s',
                    '%s'
                )
            """.formatted(
                escapeSql(playerId),
                escapeSql(authToken),
                escapeSql(existingCreatedAt),
                escapeSql(updatedAt)
            ));
        }
    }

    private void cleanupPartialPlayer(Connection conn, String playerId) {
        try (Statement stmt = conn.createStatement()) {
            String safePlayerId = escapeSql(playerId);
            stmt.executeUpdate("DELETE FROM player_sessions WHERE player_id = '%s'".formatted(safePlayerId));
            stmt.executeUpdate("DELETE FROM player_mmr WHERE player_id = '%s'".formatted(safePlayerId));
            stmt.executeUpdate("DELETE FROM players WHERE id = '%s'".formatted(safePlayerId));
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

    private String escapeSql(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }

    private boolean isUniqueConstraintViolation(SQLException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }

        String lower = message.toLowerCase();
        return lower.contains("unique") || lower.contains("constraint");
    }

    private void logServerError(String context, String debugId, Exception e) {
        Throwable root = rootCause(e);
        System.err.println("[" + debugId + "] " + context);
        System.err.println("[" + debugId + "] exception=" + e.getClass().getName() + ", message=" + safeMessage(e));
        if (root != e) {
            System.err.println("[" + debugId + "] root=" + root.getClass().getName() + ", rootMessage=" + safeMessage(root));
        }
        e.printStackTrace();
    }

    private void debug(String message) {
        if (DEBUG_ERRORS) {
            System.err.println("[debug] " + message);
        }
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        return message == null || message.isBlank() ? "<no message>" : message;
    }

    private static boolean readDebugErrorsFlag() {
        String value = System.getenv("LETTERLINK_DEBUG_ERRORS");
        if (value == null || value.isBlank()) {
            return true;
        }
        value = value.trim().toLowerCase();
        return value.equals("1") || value.equals("true") || value.equals("yes") || value.equals("on");
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

    private <T> ResponseEntity<ApiResponse<T>> serverError(String context, Exception e) {
        String debugId = "ERR-" + ERROR_COUNTER.incrementAndGet();
        logServerError(context, debugId, e);

        String error = context + " [" + debugId + "]";
        if (DEBUG_ERRORS) {
            Throwable root = rootCause(e);
            error += " | exception=" + e.getClass().getSimpleName();
            error += " | message=" + safeMessage(e);
            if (root != e) {
                error += " | root=" + root.getClass().getSimpleName();
                error += " | rootMessage=" + safeMessage(root);
            }
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failure(error));
    }

    private <T> ResponseEntity<ApiResponse<T>> serverError(String error) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failure(error));
    }
}
