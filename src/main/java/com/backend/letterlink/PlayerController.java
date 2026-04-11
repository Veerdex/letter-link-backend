package com.backend.letterlink;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/players")
public class PlayerController {

    private static final String HEADER_PLAYER_ID = "X-Player-Id";
    private static final String HEADER_PLAYER_TOKEN = "X-Player-Token";
    private static final AtomicInteger ERROR_COUNTER = new AtomicInteger(1000);
    private static final SecureRandom RANDOM = new SecureRandom();

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<ApiModels.RegisterPlayerData>> registerPlayer(
        @RequestBody(required = false) ApiModels.RegisterPlayerRequest request
    ) {
        String username = request == null ? null : request.username;
        String validationError = validateUsername(username);
        if (validationError != null) {
            return badRequest(validationError);
        }

        String playerId = UUID.randomUUID().toString();
        String authToken = generateAuthToken();
        String now = Instant.now().toString();

        try (Connection conn = Database.getConnection();
             Statement stmt = conn.createStatement()) {

            Database.ensureSchema(conn);

            stmt.execute("""
                INSERT INTO players (
                    id,
                    username,
                    music_enabled,
                    sfx_enabled,
                    vibration_enabled,
                    theme,
                    mode,
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
                    %d,
                    '%s',
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
                boolToInt(GameDefaults.DEFAULT_VIBRATION_ENABLED),
                escapeSql(GameDefaults.DEFAULT_THEME),
                escapeSql(GameDefaults.DEFAULT_MODE),
                escapeSql(GameDefaults.DEFAULT_GAMEMODE),
                GameDefaults.DEFAULT_BOARD_WIDTH,
                GameDefaults.DEFAULT_BOARD_HEIGHT,
                escapeSql(now),
                escapeSql(now)
            ));

            for (String mmrMode : GameDefaults.ALLOWED_MMR_MODES) {
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
                    escapeSql(mmrMode),
                    GameDefaults.DEFAULT_MMR,
                    escapeSql(now),
                    escapeSql(now)
                ));
            }

            upsertSession(stmt, playerId, authToken, now);

            ApiModels.RegisterPlayerData data = new ApiModels.RegisterPlayerData();
            data.id = playerId;
            data.username = username;
            data.authToken = authToken;

            return ok(data);

        } catch (Exception e) {
            return serverError("Error registering player", e);
        }
    }

    @PostMapping("/bootstrap-session")
    public ResponseEntity<ApiResponse<ApiModels.PlayerData>> bootstrapSession(
        @RequestBody(required = false) ApiModels.BootstrapSessionRequest request
    ) {
        String playerId = request == null ? null : firstNonBlank(request.id, request.playerId);
        String validationError = validatePlayerId(playerId);
        if (validationError != null) {
            return badRequest(validationError);
        }

        String now = Instant.now().toString();

        try (Connection conn = Database.getConnection();
             Statement stmt = conn.createStatement()) {

            Database.ensureSchema(conn);

            ApiModels.PlayerData player = fetchPlayerDataById(conn, playerId);
            if (player == null) {
                return notFound("Player not found");
            }

            String authToken = fetchSessionToken(conn, playerId);
            if (isBlank(authToken)) {
                authToken = generateAuthToken();
                upsertSession(stmt, playerId, authToken, now);
            }

            player.authToken = authToken;
            return ok(player);

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

            if (!hasValidSession(conn, authPlayerId, authToken)) {
                return unauthorized("Invalid player credentials");
            }

            if (!id.equals(authPlayerId)) {
                return unauthorized("Player id does not match authenticated session");
            }

            ApiModels.PlayerData player = fetchPlayerDataById(conn, id);
            if (player == null) {
                return notFound("Player not found");
            }

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
                return unauthorized("You may only fetch your own player data");
            }

            return ok(player);

        } catch (Exception e) {
            return serverError("Error getting player by username", e);
        }
    }

    @PostMapping("/update-settings")
    public ResponseEntity<ApiResponse<ApiModels.UpdatePlayerSettingsData>> updatePlayerSettings(
        @RequestBody(required = false) ApiModels.UpdatePlayerSettingsRequest request,
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

        if (!request.id.equals(authPlayerId)) {
            return unauthorized("Player id does not match authenticated session");
        }

        String themeError = validateTheme(request.theme);
        if (themeError != null) {
            return badRequest(themeError);
        }

        String modeError = validateMode(request.mode);
        if (modeError != null) {
            return badRequest(modeError);
        }

        String gamemodeError = validateGamemode(request.currentGamemode);
        if (gamemodeError != null) {
            return badRequest(gamemodeError);
        }

        String boardError = validateBoardSize(request.currentBoardWidth, request.currentBoardHeight);
        if (boardError != null) {
            return badRequest(boardError);
        }

        String now = Instant.now().toString();

        try (Connection conn = Database.getConnection();
             Statement stmt = conn.createStatement()) {

            Database.ensureSchema(conn);

            if (!hasValidSession(conn, authPlayerId, authToken)) {
                return unauthorized("Invalid player credentials");
            }

            ApiModels.PlayerData existing = fetchPlayerDataById(conn, request.id);
            if (existing == null) {
                return notFound("Player not found");
            }

            String normalizedMode = request.mode.trim().toLowerCase(Locale.ROOT);

            stmt.executeUpdate("""
                UPDATE players
                SET
                    music_enabled = %d,
                    sfx_enabled = %d,
                    vibration_enabled = %d,
                    theme = '%s',
                    mode = '%s',
                    current_gamemode = '%s',
                    current_board_width = %d,
                    current_board_height = %d,
                    updated_at = '%s'
                WHERE id = '%s'
            """.formatted(
                boolToInt(request.musicEnabled),
                boolToInt(request.sfxEnabled),
                boolToInt(request.vibrationEnabled),
                escapeSql(request.theme),
                escapeSql(normalizedMode),
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
            data.vibrationEnabled = request.vibrationEnabled;
            data.theme = request.theme;
            data.mode = normalizedMode;
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
        @RequestBody(required = false) ApiModels.UpdatePlayerStatsRequest request,
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

        if (!request.id.equals(authPlayerId)) {
            return unauthorized("Player id does not match authenticated session");
        }

        if (request.winsToAdd < 0 || request.lossesToAdd < 0) {
            return badRequest("winsToAdd and lossesToAdd must be 0 or greater");
        }

        String now = Instant.now().toString();

        try (Connection conn = Database.getConnection();
             Statement stmt = conn.createStatement()) {

            Database.ensureSchema(conn);

            if (!hasValidSession(conn, authPlayerId, authToken)) {
                return unauthorized("Invalid player credentials");
            }

            int rows = stmt.executeUpdate("""
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

            if (rows == 0) {
                return notFound("Player not found");
            }

            ApiModels.PlayerData player = fetchPlayerDataById(conn, request.id);
            ApiModels.UpdatePlayerStatsData data = new ApiModels.UpdatePlayerStatsData();
            data.id = request.id;
            data.wins = player == null ? 0 : player.wins;
            data.losses = player == null ? 0 : player.losses;
            data.updatedAt = now;

            return ok(data);

        } catch (Exception e) {
            return serverError("Error updating player stats", e);
        }
    }

    @PostMapping("/update-mmr")
    public ResponseEntity<ApiResponse<ApiModels.UpdatePlayerMmrData>> updatePlayerMmr(
        @RequestBody(required = false) ApiModels.UpdatePlayerMmrRequest request,
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

        if (!request.id.equals(authPlayerId)) {
            return unauthorized("Player id does not match authenticated session");
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

            Database.ensureSchema(conn);

            if (!hasValidSession(conn, authPlayerId, authToken)) {
                return unauthorized("Invalid player credentials");
            }

            ApiModels.PlayerData existingPlayer = fetchPlayerDataById(conn, request.id);
            if (existingPlayer == null) {
                return notFound("Player not found");
            }

            String createdAt = now;

            try (ResultSet rs = readStmt.executeQuery("""
                SELECT created_at
                FROM player_mmr
                WHERE player_id = '%s' AND mode = '%s'
            """.formatted(escapeSql(request.id), escapeSql(request.mode)))) {
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
                escapeSql(request.id),
                escapeSql(request.mode),
                request.mmr,
                escapeSql(createdAt),
                escapeSql(now)
            ));

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
        try (Statement playerStmt = conn.createStatement();
             Statement mmrStmt = conn.createStatement()) {

            ResultSet playerRs = playerStmt.executeQuery("""
                SELECT
                    id,
                    username,
                    music_enabled,
                    sfx_enabled,
                    vibration_enabled,
                    theme,
                    mode,
                    wins,
                    losses,
                    current_gamemode,
                    current_board_width,
                    current_board_height,
                    created_at,
                    updated_at
                FROM players
                WHERE %s = '%s'
            """.formatted(field, escapeSql(value)));

            if (!playerRs.next()) {
                return null;
            }

            String playerId = playerRs.getString("id");

            Map<String, Integer> mmrMap = new LinkedHashMap<String, Integer>();
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
            data.vibrationEnabled = playerRs.getInt("vibration_enabled") == 1;
            data.theme = playerRs.getString("theme");
            data.mode = playerRs.getString("mode");
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

    private boolean hasValidSession(Connection conn, String authPlayerId, String authToken) throws Exception {
        if (isBlank(authPlayerId) || isBlank(authToken)) {
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

    private String fetchSessionToken(Connection conn, String playerId) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT auth_token
                 FROM player_sessions
                 WHERE player_id = '%s'
             """.formatted(escapeSql(playerId)))) {
            if (rs.next()) {
                return rs.getString("auth_token");
            }
            return null;
        }
    }

    private void upsertSession(Statement stmt, String playerId, String authToken, String now) throws Exception {
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
                COALESCE(
                    (SELECT created_at FROM player_sessions WHERE player_id = '%s'),
                    '%s'
                ),
                '%s'
            )
        """.formatted(
            escapeSql(playerId),
            escapeSql(authToken),
            escapeSql(playerId),
            escapeSql(now),
            escapeSql(now)
        ));
    }

    private String generateAuthToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);

        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            builder.append(String.format("%02x", bytes[i]));
        }
        return builder.toString();
    }

    private String validateUsername(String username) {
        if (isBlank(username)) {
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
        if (isBlank(id)) {
            return "Player id is required";
        }
        return null;
    }

    private String validateTheme(String theme) {
        if (isBlank(theme)) {
            return "Theme is required";
        }

        if (theme.length() > GameDefaults.MAX_THEME_LENGTH) {
            return "Theme must be " + GameDefaults.MAX_THEME_LENGTH + " characters or less";
        }

        return null;
    }

    private String validateMode(String mode) {
        if (isBlank(mode)) {
            return "mode is required";
        }

        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        if (!GameDefaults.ALLOWED_MODES.contains(normalized)) {
            return "Invalid mode";
        }

        return null;
    }

    private String validateGamemode(String gamemode) {
        if (isBlank(gamemode)) {
            return "currentGamemode is required";
        }

        if (gamemode.length() > GameDefaults.MAX_GAMEMODE_LENGTH) {
            return "currentGamemode must be " + GameDefaults.MAX_GAMEMODE_LENGTH + " characters or less";
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
        if (isBlank(mode)) {
            return "mode is required";
        }

        if (!GameDefaults.ALLOWED_MMR_MODES.contains(mode)) {
            return "Invalid MMR mode";
        }

        return null;
    }

    private String firstNonBlank(String a, String b) {
        if (!isBlank(a)) {
            return a.trim();
        }
        if (!isBlank(b)) {
            return b.trim();
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

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    private <T> ResponseEntity<ApiResponse<T>> badRequest(String error) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(error));
    }

    private <T> ResponseEntity<ApiResponse<T>> unauthorized(String error) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.failure(error));
    }

    private <T> ResponseEntity<ApiResponse<T>> notFound(String error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure(error));
    }

    private <T> ResponseEntity<ApiResponse<T>> serverError(String context, Exception e) {
        int errorId = ERROR_COUNTER.incrementAndGet();

        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }

        String error = context
            + " [ERR-" + errorId + "]"
            + " | exception=" + e.getClass().getSimpleName()
            + " | message=" + safeMessage(e);

        if (root != e) {
            error += " | root=" + root.getClass().getSimpleName()
                + " | rootMessage=" + safeMessage(root);
        }

        e.printStackTrace();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failure(error));
    }

    private String safeMessage(Throwable t) {
        return t.getMessage() == null ? "<no message>" : t.getMessage();
    }
}
