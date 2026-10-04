package com.ezbackup;

/**
 * Compile-time limits and fixed values of the backup feature. These are NOT player settings (those are in
 * {@link BackupConfig}); change one here and rebuild. Suggestion-feature constants are in {@link SuggestionConfig}.
 *
 * Minecraft-version settings (game version, NeoForge version, Java version) are in gradle.properties.
 */
public final class BackupLimits {

    private BackupLimits() {
    }

    /** Name written into the comment of every ZIP backup (followed by the mod version). */
    public static final String GENERATOR_NAME = "EzBackup";

    /**
     * Zstd worker threads. 0 = single-threaded, which gives the best compression ratio.
     * Raise it (e.g. 4) to trade a little ratio for a lot of speed.
     */
    public static final int ZSTD_WORKERS = 0;

    /** Longest allowed backup name. */
    public static final int MAX_NAME_LENGTH = 64;

    /** Extra free space (beyond the world size) required on the backup drive before starting. */
    public static final long FREE_SPACE_MARGIN_BYTES = 512L * 1024 * 1024;

    /**
     * The player who owns the single-player / LAN / e4mc world may always run /backup.
     * Anyone else (and command blocks, functions...) needs this permission level.
     */
    public static final int OTHER_PLAYERS_PERMISSION_LEVEL = 4;

    /** Allowed range for the number of backups shown per page of /backup list. */
    public static final int MIN_LIST_PAGE_SIZE = 1;
    public static final int MAX_LIST_PAGE_SIZE = 50;

    // ---- /backup restore ----------------------------------------------------------------------------

    /** How long the player has to type {@code /backup restore <name> confirm} after {@code /backup restore <name>}. */
    public static final long RESTORE_CONFIRM_MILLIS = 60_000;

    /** Server ticks the holding dimension has to stay free of newly arrived players before the world is swapped (20 ticks = 1 s). */
    public static final int RESTORE_SETTLE_TICKS = 40;

    /** Extra free space (beyond the extracted world) required on the world drive before a restore starts. */
    public static final long RESTORE_FREE_SPACE_MARGIN_BYTES = 256L * 1024 * 1024;
}
