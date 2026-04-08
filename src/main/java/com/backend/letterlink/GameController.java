package com.backend.letterlink;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

@RestController
@RequestMapping("/games")
public class GameController {

    private static final String HEADER_PLAYER_ID = "X-Player-Id";
    private static final String HEADER_PLAYER_TOKEN = "X-Player-Token";
    private static final String LETTER_BAG = "eeeeeeeeeeeeaaaaaaaaiiiiiiiiooooooonnnnnnrrrrrrttttttllllssssuuuuddddgggbbccmmppffhhvvwwyykjxqz";
    private static final Random RANDOM = new Random();

    @PostMapping("/start")
    public ResponseEntity<ApiResponse<ApiModels.StartGameData>> startGame(
        @RequestBody(required = false) ApiModels.StartGameRequest request,
        @RequestHeader(value = HEADER_PLAYER_ID, required = false) String authPlayerId,
        @RequestHeader(value = HEADER_PLAYER_TOKEN, required = false) String authToken
    ) {
        try (Connection conn = Database.getConnection()) {
            Database.ensureSchema(conn);

            if (!hasValidSession(conn, authPlayerId, authToken)) {
                return unauthorized("Invalid player credentials");
            }

            if (!WordDictionary.ensureLoaded()) {
                return serviceUnavailable("Word dictionary not configured. " + WordDictionary.statusMessage());
            }

            PlayerSettings settings = fetchPlayerSettings(conn, authPlayerId);
            if (settings == null) {
                return notFound("Player not found");
            }

            String mode = normalizeMode(request == null ? null : request.mode, settings.currentGamemode);
            int boardWidth = request != null && request.boardWidth != null && request.boardWidth > 0
                ? request.boardWidth
                : settings.boardWidth;
            int boardHeight = request != null && request.boardHeight != null && request.boardHeight > 0
                ? request.boardHeight
                : settings.boardHeight;
            boolean ranked = request != null && request.ranked != null
                ? request.ranked
                : "competitive".equals(mode);
            long timeLimitSeconds = request != null && request.timeLimitSeconds != null && request.timeLimitSeconds > 0
                ? Math.min(request.timeLimitSeconds, GameDefaults.MAX_GAME_TIME_LIMIT_SECONDS)
                : GameDefaults.DEFAULT_GAME_TIME_LIMIT_SECONDS;

            String boardError = validateBoardSize(boardWidth, boardHeight);
            if (boardError != null) {
                return badRequest(boardError);
            }

            String now = Instant.now().toString();
            String gameSessionId = UUID.randomUUID().toString();
            String boardLetters = generateBoard(boardWidth, boardHeight);

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    INSERT INTO game_sessions (
                        id,
                        player_id,
                        mode,
                        board_width,
                        board_height,
                        board_letters,
                        ranked,
                        time_limit_seconds,
                        status,
                        started_at,
                        created_at,
                        updated_at
                    )
                    VALUES (
                        '%s',
                        '%s',
                        '%s',
                        %d,
                        %d,
                        '%s',
                        %d,
                        %d,
                        'ACTIVE',
                        '%s',
                        '%s',
                        '%s'
                    )
                """.formatted(
                    escapeSql(gameSessionId),
                    escapeSql(authPlayerId),
                    escapeSql(mode),
                    boardWidth,
                    boardHeight,
                    escapeSql(boardLetters),
                    boolToInt(ranked),
                    timeLimitSeconds,
                    escapeSql(now),
                    escapeSql(now),
                    escapeSql(now)
                ));
            }

            ApiModels.StartGameData data = new ApiModels.StartGameData();
            data.gameSessionId = gameSessionId;
            data.playerId = authPlayerId;
            data.mode = mode;
            data.boardWidth = boardWidth;
            data.boardHeight = boardHeight;
            data.boardLetters = boardLetters;
            data.boardRows = buildBoardRows(boardLetters, boardWidth, boardHeight);
            data.ranked = ranked;
            data.timeLimitSeconds = timeLimitSeconds;
            data.startedAt = now;
            return ok(data);

        } catch (Exception e) {
            return serverError("Error starting game", e);
        }
    }

    @PostMapping("/finish")
    public ResponseEntity<ApiResponse<ApiModels.FinishGameData>> finishGame(
        @RequestBody ApiModels.FinishGameRequest request,
        @RequestHeader(value = HEADER_PLAYER_ID, required = false) String authPlayerId,
        @RequestHeader(value = HEADER_PLAYER_TOKEN, required = false) String authToken
    ) {
        if (request == null || request.gameSessionId == null || request.gameSessionId.isBlank()) {
            return badRequest("gameSessionId is required");
        }

        try (Connection conn = Database.getConnection()) {
            Database.ensureSchema(conn);

            if (!hasValidSession(conn, authPlayerId, authToken)) {
                return unauthorized("Invalid player credentials");
            }

            if (!WordDictionary.ensureLoaded()) {
                return serviceUnavailable("Word dictionary not configured. " + WordDictionary.statusMessage());
            }

            GameSessionRecord session = fetchGameSession(conn, authPlayerId, request.gameSessionId);
            if (session == null) {
                return notFound("Game session not found");
            }
            if (!"ACTIVE".equals(session.status)) {
                return conflict("Game session has already been finished");
            }

            Instant finishedAtInstant = Instant.now();
            long serverElapsedMillis = Math.max(0, Duration.between(Instant.parse(session.startedAt), finishedAtInstant).toMillis());
            boolean timedOut = serverElapsedMillis > (session.timeLimitSeconds + GameDefaults.GAME_FINISH_GRACE_SECONDS) * 1000L;

            List<String> submittedWords = request.words == null ? List.of() : request.words;
            if (submittedWords.size() > 512) {
                return badRequest("Too many submitted words");
            }

            List<String> acceptedWords = new ArrayList<>();
            List<ApiModels.RejectedWordData> rejectedWords = new ArrayList<>();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            int validatedScore = 0;

            for (String rawWord : submittedWords) {
                String word = normalizeWord(rawWord);
                if (word.isEmpty()) {
                    rejectedWords.add(rejected(rawWord, "blank"));
                    continue;
                }
                if (word.length() < 3) {
                    rejectedWords.add(rejected(word, "too-short"));
                    continue;
                }
                if (!isAlpha(word)) {
                    rejectedWords.add(rejected(word, "non-letter-characters"));
                    continue;
                }
                if (!seen.add(word)) {
                    rejectedWords.add(rejected(word, "duplicate"));
                    continue;
                }
                if (!WordDictionary.contains(word)) {
                    rejectedWords.add(rejected(word, "not-in-dictionary"));
                    continue;
                }
                if (!canTraceWord(session.boardLetters, session.boardWidth, session.boardHeight, word)) {
                    rejectedWords.add(rejected(word, "not-on-board"));
                    continue;
                }

                acceptedWords.add(word);
                validatedScore += scoreWordLength(word.length());
            }

            String status = timedOut ? "TIMED_OUT" : "FINISHED";
            boolean countedAsWin = false;
            Integer mmrBefore = null;
            Integer mmrAfter = null;
            int wins = 0;
            int losses = 0;
            String boardMode = GameDefaults.boardMode(session.boardWidth, session.boardHeight);
            int targetScore = GameDefaults.rankedTargetScore(boardMode);
            String finishedAt = finishedAtInstant.toString();

            if (session.ranked && !timedOut) {
                countedAsWin = validatedScore >= targetScore;
                mmrBefore = fetchMmr(conn, authPlayerId, boardMode);
                mmrAfter = Math.max(0, mmrBefore + (countedAsWin ? GameDefaults.RANKED_WIN_MMR_DELTA : GameDefaults.RANKED_LOSS_MMR_DELTA));
                applyRankedResult(conn, authPlayerId, boardMode, mmrAfter, countedAsWin, finishedAt);
            }

            PlayerStats stats = fetchPlayerStats(conn, authPlayerId);
            wins = stats == null ? 0 : stats.wins;
            losses = stats == null ? 0 : stats.losses;

            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    UPDATE game_sessions
                    SET
                        status = '%s',
                        finished_at = '%s',
                        submitted_word_count = %d,
                        accepted_word_count = %d,
                        rejected_word_count = %d,
                        accepted_words_json = '%s',
                        rejected_words_json = '%s',
                        validated_score = %d,
                        counted_as_win = %s,
                        mmr_before = %s,
                        mmr_after = %s,
                        updated_at = '%s'
                    WHERE id = '%s'
                """.formatted(
                    escapeSql(status),
                    escapeSql(finishedAt),
                    submittedWords.size(),
                    acceptedWords.size(),
                    rejectedWords.size(),
                    escapeSql(toJsonStringArray(acceptedWords)),
                    escapeSql(toRejectedWordsJson(rejectedWords)),
                    validatedScore,
                    session.ranked && !timedOut ? String.valueOf(boolToInt(countedAsWin)) : "NULL",
                    mmrBefore == null ? "NULL" : String.valueOf(mmrBefore),
                    mmrAfter == null ? "NULL" : String.valueOf(mmrAfter),
                    escapeSql(finishedAt),
                    escapeSql(session.id)
                ));
            }

            ApiModels.FinishGameData data = new ApiModels.FinishGameData();
            data.gameSessionId = session.id;
            data.status = status;
            data.mode = session.mode;
            data.boardWidth = session.boardWidth;
            data.boardHeight = session.boardHeight;
            data.boardLetters = session.boardLetters;
            data.boardRows = buildBoardRows(session.boardLetters, session.boardWidth, session.boardHeight);
            data.ranked = session.ranked;
            data.timedOut = timedOut;
            data.countedAsWin = countedAsWin;
            data.targetScore = targetScore;
            data.validatedScore = validatedScore;
            data.acceptedWordCount = acceptedWords.size();
            data.rejectedWordCount = rejectedWords.size();
            data.acceptedWords = acceptedWords;
            data.rejectedWords = rejectedWords;
            data.wins = wins;
            data.losses = losses;
            data.mmrBefore = mmrBefore;
            data.mmrAfter = mmrAfter;
            data.finishedAt = finishedAt;
            return ok(data);

        } catch (Exception e) {
            return serverError("Error finishing game", e);
        }
    }

    private PlayerSettings fetchPlayerSettings(Connection conn, String playerId) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT current_gamemode, current_board_width, current_board_height
                 FROM players
                 WHERE id = '%s'
             """.formatted(escapeSql(playerId)))) {
            if (!rs.next()) {
                return null;
            }
            PlayerSettings settings = new PlayerSettings();
            settings.currentGamemode = rs.getString("current_gamemode");
            settings.boardWidth = rs.getInt("current_board_width");
            settings.boardHeight = rs.getInt("current_board_height");
            return settings;
        }
    }

    private PlayerStats fetchPlayerStats(Connection conn, String playerId) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT wins, losses
                 FROM players
                 WHERE id = '%s'
             """.formatted(escapeSql(playerId)))) {
            if (!rs.next()) {
                return null;
            }
            PlayerStats stats = new PlayerStats();
            stats.wins = rs.getInt("wins");
            stats.losses = rs.getInt("losses");
            return stats;
        }
    }

    private int fetchMmr(Connection conn, String playerId, String boardMode) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT mmr
                 FROM player_mmr
                 WHERE player_id = '%s' AND mode = '%s'
             """.formatted(escapeSql(playerId), escapeSql(boardMode)))) {
            if (rs.next()) {
                return rs.getInt("mmr");
            }
        }
        return GameDefaults.DEFAULT_MMR;
    }

    private void applyRankedResult(Connection conn, String playerId, String boardMode, int mmrAfter, boolean countedAsWin, String now) throws Exception {
        String existingCreatedAt = now;

        try (Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("""
                SELECT created_at
                FROM player_mmr
                WHERE player_id = '%s' AND mode = '%s'
            """.formatted(escapeSql(playerId), escapeSql(boardMode)))) {
                if (rs.next()) {
                    existingCreatedAt = rs.getString("created_at");
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
                escapeSql(playerId),
                escapeSql(boardMode),
                mmrAfter,
                escapeSql(existingCreatedAt),
                escapeSql(now)
            ));

            stmt.executeUpdate("""
                UPDATE players
                SET wins = wins + %d,
                    losses = losses + %d,
                    updated_at = '%s'
                WHERE id = '%s'
            """.formatted(
                countedAsWin ? 1 : 0,
                countedAsWin ? 0 : 1,
                escapeSql(now),
                escapeSql(playerId)
            ));
        }
    }

    private GameSessionRecord fetchGameSession(Connection conn, String playerId, String gameSessionId) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT id, player_id, mode, board_width, board_height, board_letters, ranked, time_limit_seconds, status, started_at
                 FROM game_sessions
                 WHERE id = '%s' AND player_id = '%s'
             """.formatted(escapeSql(gameSessionId), escapeSql(playerId)))) {
            if (!rs.next()) {
                return null;
            }

            GameSessionRecord session = new GameSessionRecord();
            session.id = rs.getString("id");
            session.playerId = rs.getString("player_id");
            session.mode = rs.getString("mode");
            session.boardWidth = rs.getInt("board_width");
            session.boardHeight = rs.getInt("board_height");
            session.boardLetters = rs.getString("board_letters");
            session.ranked = rs.getInt("ranked") == 1;
            session.timeLimitSeconds = rs.getLong("time_limit_seconds");
            session.status = rs.getString("status");
            session.startedAt = rs.getString("started_at");
            return session;
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

    private String normalizeMode(String requestedMode, String fallback) {
        String mode = requestedMode == null || requestedMode.isBlank() ? fallback : requestedMode;
        if (mode == null || mode.isBlank()) {
            mode = GameDefaults.DEFAULT_GAMEMODE;
        }
        mode = mode.trim().toLowerCase(Locale.ROOT);
        return GameDefaults.ALLOWED_GAME_MODES.contains(mode) ? mode : GameDefaults.DEFAULT_GAMEMODE;
    }

    private String validateBoardSize(int width, int height) {
        if (!GameDefaults.isAllowedBoardSize(width, height)) {
            return "Invalid board size. Allowed sizes are 4x4, 4x5, and 5x5";
        }
        return null;
    }

    private String generateBoard(int width, int height) {
        StringBuilder board = new StringBuilder(width * height);
        for (int i = 0; i < width * height; i++) {
            int index = RANDOM.nextInt(LETTER_BAG.length());
            board.append(LETTER_BAG.charAt(index));
        }
        return board.toString();
    }

    private List<String> buildBoardRows(String boardLetters, int width, int height) {
        List<String> rows = new ArrayList<>();
        if (boardLetters == null) {
            return rows;
        }
        for (int row = 0; row < height; row++) {
            int start = row * width;
            int end = Math.min(start + width, boardLetters.length());
            rows.add(boardLetters.substring(start, end));
        }
        return rows;
    }

    private String normalizeWord(String rawWord) {
        return rawWord == null ? "" : rawWord.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isAlpha(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (!Character.isLetter(word.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean canTraceWord(String boardLetters, int width, int height, String word) {
        if (boardLetters == null || word == null || word.isEmpty()) {
            return false;
        }
        char[] board = boardLetters.toCharArray();
        boolean[] used = new boolean[board.length];
        char first = word.charAt(0);
        for (int i = 0; i < board.length; i++) {
            if (board[i] == first && dfs(board, width, height, word, 0, i, used)) {
                return true;
            }
        }
        return false;
    }

    private boolean dfs(char[] board, int width, int height, String word, int wordIndex, int boardIndex, boolean[] used) {
        if (used[boardIndex] || board[boardIndex] != word.charAt(wordIndex)) {
            return false;
        }
        if (wordIndex == word.length() - 1) {
            return true;
        }

        used[boardIndex] = true;
        int row = boardIndex / width;
        int col = boardIndex % width;

        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) {
                    continue;
                }
                int nextRow = row + dr;
                int nextCol = col + dc;
                if (nextRow < 0 || nextRow >= height || nextCol < 0 || nextCol >= width) {
                    continue;
                }
                int nextIndex = nextRow * width + nextCol;
                if (dfs(board, width, height, word, wordIndex + 1, nextIndex, used)) {
                    used[boardIndex] = false;
                    return true;
                }
            }
        }

        used[boardIndex] = false;
        return false;
    }

    private int scoreWordLength(int length) {
        if (length < 3) {
            return 0;
        }
        if (length == 3) {
            return 100;
        }
        if (length == 4) {
            return 400;
        }
        int extra = length - 4;
        return 400 + (extra * 400) + ((extra * (extra - 1)) * 100 / 2);
    }

    private ApiModels.RejectedWordData rejected(String word, String reason) {
        ApiModels.RejectedWordData rejected = new ApiModels.RejectedWordData();
        rejected.word = word == null ? "" : word;
        rejected.reason = reason;
        return rejected;
    }

    private String toJsonStringArray(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(escapeJson(values.get(i))).append('"');
        }
        return json.append(']').toString();
    }

    private String toRejectedWordsJson(List<ApiModels.RejectedWordData> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            ApiModels.RejectedWordData value = values.get(i);
            json.append("{\"word\":\"")
                .append(escapeJson(value.word))
                .append("\",\"reason\":\"")
                .append(escapeJson(value.reason))
                .append("\"}");
        }
        return json.append(']').toString();
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

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
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

    private <T> ResponseEntity<ApiResponse<T>> conflict(String error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure(error));
    }

    private <T> ResponseEntity<ApiResponse<T>> serviceUnavailable(String error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.failure(error));
    }

    private <T> ResponseEntity<ApiResponse<T>> serverError(String context, Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.failure(context + " | exception=" + e.getClass().getSimpleName() + " | message=" + message));
    }

    private static class PlayerSettings {
        String currentGamemode;
        int boardWidth;
        int boardHeight;
    }

    private static class PlayerStats {
        int wins;
        int losses;
    }

    private static class GameSessionRecord {
        String id;
        String playerId;
        String mode;
        int boardWidth;
        int boardHeight;
        String boardLetters;
        boolean ranked;
        long timeLimitSeconds;
        String status;
        String startedAt;
    }
}
