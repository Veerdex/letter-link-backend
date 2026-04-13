package com.backend.letterlink;

import java.util.List;
import java.util.Map;

public final class ApiModels {

    private ApiModels() {
    }

    public static class RegisterPlayerRequest {
        public String username;
    }

    public static class RegisterPlayerData {
        public String id;
        public String username;
        public String authToken;
    }

    public static class BootstrapSessionRequest {
        public String id;
        public String playerId;
    }

    public static class PlayerData {
        public String id;
        public String username;
        public boolean musicEnabled;
        public boolean sfxEnabled;
        public boolean vibrationEnabled;
        public String theme;
        public String mode;
        public int banAmount;
        public int wins;
        public int losses;
        public String currentGamemode;
        public int currentBoardWidth;
        public int currentBoardHeight;
        public String createdAt;
        public String updatedAt;
        public Map<String, Integer> mmr;
        public String authToken;
    }

    public static class BanAmountData {
        public String id;
        public int banAmount;
        public String updatedAt;
    }

    public static class UpdatePlayerSettingsRequest {
        public String id;
        public boolean musicEnabled;
        public boolean sfxEnabled;
        public boolean vibrationEnabled;
        public String theme;
        public String mode;
        public String currentGamemode;
        public int currentBoardWidth;
        public int currentBoardHeight;
    }

    public static class UpdatePlayerSettingsData {
        public String id;
        public boolean musicEnabled;
        public boolean sfxEnabled;
        public boolean vibrationEnabled;
        public String theme;
        public String mode;
        public String currentGamemode;
        public int currentBoardWidth;
        public int currentBoardHeight;
        public String updatedAt;
    }

    public static class UpdatePlayerStatsRequest {
        public String id;
        public int winsToAdd;
        public int lossesToAdd;
    }

    public static class UpdatePlayerStatsData {
        public String id;
        public int wins;
        public int losses;
        public String updatedAt;
    }

    public static class UpdatePlayerMmrRequest {
        public String id;
        public String mode;
        public int mmr;
    }

    public static class UpdatePlayerMmrData {
        public String id;
        public String mode;
        public int mmr;
        public String updatedAt;
    }

    public static class StartGameRequest {
        public String mode;
        public Integer boardWidth;
        public Integer boardHeight;
        public Boolean ranked;
        public Long timeLimitSeconds;
    }

    public static class StartGameData {
        public String gameSessionId;
        public String playerId;
        public String mode;
        public int boardWidth;
        public int boardHeight;
        public String boardLetters;
        public List<String> boardRows;
        public boolean ranked;
        public long timeLimitSeconds;
        public String startedAt;
    }

    public static class FinishGameRequest {
        public String gameSessionId;
        public List<String> words;
        public List<String> submittedWords;
        public long elapsedMillis;
    }

    public static class RejectedWordData {
        public String word;
        public String reason;
    }

    public static class FinishGameData {
        public String gameSessionId;
        public String status;
        public String mode;
        public int boardWidth;
        public int boardHeight;
        public String boardLetters;
        public List<String> boardRows;
        public boolean ranked;
        public boolean timedOut;
        public boolean countedAsWin;
        public int targetScore;
        public int validatedScore;
        public int acceptedWordCount;
        public int rejectedWordCount;
        public List<String> acceptedWords;
        public List<RejectedWordData> rejectedWords;
        public Integer mmrBefore;
        public Integer mmrAfter;
        public int wins;
        public int losses;
        public String finishedAt;
    }

    public static class QueueForMatchRequest {
        public String id;
        public String mode;
        public String currentGamemode;
        public int boardWidth;
        public int boardHeight;
        public Integer power;
    }

    public static class QueueTicketData {
        public String ticketId;
        public String ticketStatus;
        public String mode;
        public String currentGamemode;
        public int boardWidth;
        public int boardHeight;
        public int mmr;
        public int power;
        public String queuedAt;
        public String updatedAt;
        public String matchId;
    }

    public static class CancelQueueRequest {
        public String ticketId;
    }

    public static class CancelQueueData {
        public String ticketId;
        public boolean cancelled;
        public String ticketStatus;
        public String matchId;
        public String matchStatus;
        public String updatedAt;
    }

    public static class QueueHeartbeatRequest {
        public String ticketId;
    }

    public static class QueueHeartbeatData {
        public String ticketId;
        public String ticketStatus;
        public String matchId;
        public String matchStatus;
        public String updatedAt;
    }

    public static class MatchStatusData {
        public String ticketId;
        public String ticketStatus;
        public String matchId;
        public String matchStatus;
        public boolean playerAcknowledged;
        public boolean bothAcknowledged;
        public boolean ready;
        public String opponentId;
        public String opponentUsername;
        public String mode;
        public String currentGamemode;
        public int boardWidth;
        public int boardHeight;
        public int power;
        public String boardLetters;
        public List<String> boardRows;
        public String updatedAt;
    }

    public static class AcknowledgeMatchRequest {
        public String matchId;
    }

    public static class AcknowledgeMatchData {
        public String matchId;
        public String matchStatus;
        public boolean playerAcknowledged;
        public boolean bothAcknowledged;
        public boolean ready;
        public int power;
        public String boardLetters;
        public List<String> boardRows;
        public String updatedAt;
    }

    public static class AbandonMatchRequest {
        public String matchId;
    }

    public static class AbandonMatchData {
        public String matchId;
        public String matchStatus;
        public int banAmount;
        public String updatedAt;
    }
}
