package com.backend.letterlink;

import java.util.Set;

public final class GameDefaults {

    private GameDefaults() {
    }

    public static final boolean DEFAULT_MUSIC_ENABLED = true;
    public static final boolean DEFAULT_SFX_ENABLED = true;
    public static final boolean DEFAULT_VIBRATION_ENABLED = true;
    public static final String DEFAULT_THEME = "default";
    public static final String DEFAULT_GAMEMODE = "casual";
    public static final int DEFAULT_BOARD_WIDTH = 4;
    public static final int DEFAULT_BOARD_HEIGHT = 4;
    public static final int DEFAULT_MMR = 1000;

    public static final int MIN_USERNAME_LENGTH = 3;
    public static final int MAX_USERNAME_LENGTH = 32;
    public static final int MAX_THEME_LENGTH = 32;

    public static final long DEFAULT_GAME_TIME_LIMIT_SECONDS = 90L;
    public static final long MAX_GAME_TIME_LIMIT_SECONDS = 300L;
    public static final long GAME_FINISH_GRACE_SECONDS = 5L;

    public static final int RANKED_WIN_MMR_DELTA = 25;
    public static final int RANKED_LOSS_MMR_DELTA = -20;

    public static final Set<String> ALLOWED_GAME_MODES = Set.of(
        "casual",
        "competitive",
        "practice"
    );

    public static final Set<String> ALLOWED_MMR_MODES = Set.of(
        "4x4",
        "4x5",
        "5x5"
    );

    public static boolean isAllowedBoardSize(int width, int height) {
        return (width == 4 && height == 4)
            || (width == 4 && height == 5)
            || (width == 5 && height == 5);
    }

    public static String boardMode(int width, int height) {
        if (width == 4 && height == 4) {
            return "4x4";
        }
        if (width == 4 && height == 5) {
            return "4x5";
        }
        if (width == 5 && height == 5) {
            return "5x5";
        }
        throw new IllegalArgumentException("Unsupported board size: " + width + "x" + height);
    }

    public static int rankedTargetScore(String boardMode) {
        if (boardMode == null) {
            return 1200;
        }
        switch (boardMode) {
            case "4x4":
                return 1200;
            case "4x5":
                return 1600;
            case "5x5":
                return 2200;
            default:
                return 1200;
        }
    }
}
