package com.backend.letterlink;

import java.util.Set;

public final class GameDefaults {

    private GameDefaults() {
    }

    public static final boolean DEFAULT_MUSIC_ENABLED = true;
    public static final boolean DEFAULT_SFX_ENABLED = true;
    public static final boolean DEFAULT_VIBRATION_ENABLED = true;

    public static final String DEFAULT_THEME = "Cabin";
    public static final String DEFAULT_MODE = "practice";
    public static final String DEFAULT_GAMEMODE = "Standard";

    public static final int DEFAULT_BOARD_WIDTH = 4;
    public static final int DEFAULT_BOARD_HEIGHT = 4;
    public static final int DEFAULT_MMR = 1000;

    public static final int MIN_USERNAME_LENGTH = 3;
    public static final int MAX_USERNAME_LENGTH = 32;
    public static final int MAX_THEME_LENGTH = 32;
    public static final int MAX_MODE_LENGTH = 32;
    public static final int MAX_GAMEMODE_LENGTH = 64;

    public static final long DEFAULT_GAME_TIME_LIMIT_SECONDS = 180L;
    public static final long MAX_GAME_TIME_LIMIT_SECONDS = 600L;
    public static final long GAME_FINISH_GRACE_SECONDS = 5L;

    public static final int RANKED_WIN_MMR_DELTA = 25;
    public static final int RANKED_LOSS_MMR_DELTA = -20;

    public static final Set<String> ALLOWED_MODES = Set.of(
        "practice",
        "casual",
        "competitive"
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
        return width + "x" + height;
    }

    public static int rankedTargetScore(String boardMode) {
        if ("4x4".equals(boardMode)) {
            return 1200;
        }
        if ("4x5".equals(boardMode)) {
            return 1600;
        }
        if ("5x5".equals(boardMode)) {
            return 2200;
        }
        return 1200;
    }
}
