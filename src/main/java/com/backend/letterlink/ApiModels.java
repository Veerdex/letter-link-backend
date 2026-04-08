package com.backend.letterlink;

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
        public String theme;
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

    public static class UpdatePlayerSettingsRequest {
        public String id;
        public boolean musicEnabled;
        public boolean sfxEnabled;
        public String theme;
        public String currentGamemode;
        public int currentBoardWidth;
        public int currentBoardHeight;
    }

    public static class UpdatePlayerSettingsData {
        public String id;
        public boolean musicEnabled;
        public boolean sfxEnabled;
        public String theme;
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
}
