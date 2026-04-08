package com.backend.letterlink;

import java.util.Set;

public final class GameDefaults {

    private GameDefaults() {
    }

    public static final boolean DEFAULT_MUSIC_ENABLED = true;
    public static final boolean DEFAULT_SFX_ENABLED = true;
    public static final String DEFAULT_THEME = "default";
    public static final String DEFAULT_GAMEMODE = "casual";
    public static final int DEFAULT_BOARD_WIDTH = 4;
    public static final int DEFAULT_BOARD_HEIGHT = 4;
    public static final int DEFAULT_MMR = 1000;

    public static final int MIN_USERNAME_LENGTH = 3;
    public static final int MAX_USERNAME_LENGTH = 32;
    public static final int MAX_THEME_LENGTH = 32;

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
}