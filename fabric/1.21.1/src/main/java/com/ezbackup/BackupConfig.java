package com.ezbackup;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * The settings the player saved in the in-game "B" screen, and how they are loaded and stored.
 *
 * <p>All nine settings live in ONE immutable {@link Settings} object. Readers get a consistent snapshot with one
 * volatile read ({@link #settings()}); every setter builds a changed copy and saves it. The settings are stored in
 * config/ezbackup.properties (keys: backup_directory, backup_name, default_algorithm, default_level, warn_mid_backup,
 * warn_no_backup, list_details, list_page_size, backup_before_restore; the legacy key warn_on_disconnect is still read). There is NO built-in
 * backup folder; until the player sets one, /backup refuses to run. A bad value in the file is ignored and
 * the factory value is used instead (a bad path, name, algorithm or number is logged; a true/false setting that
 * is neither "true" nor "false" is ignored silently).
 *
 * <p>Compile-time limits are in {@link BackupLimits}; the suggestion feature's constants are in {@link SuggestionConfig}.
 * Minecraft-version settings (game version, Fabric version, Java version) are in gradle.properties.
 *
 * <p>Adding a setting: add a component to {@link Settings} (with its factory value and a {@code with...} method), a key
 * constant, one line in {@link #parse}, one line in {@link #store}, a getter and a setter below.
 */
public final class BackupConfig {

    private BackupConfig() {
    }

    // ---------------------------------------------------------------------------------------------
    // The settings
    // ---------------------------------------------------------------------------------------------

    /**
     * Everything the player can save. Immutable: a change makes a new object. The level is always valid for the
     * algorithm (the setters and {@link #withAlgorithm} keep it so).
     *
     * @param backupDirectory where backups are stored; null = not set
     * @param backupName      file name (no extension) for the GUI "Backup" button; "" = not set (use date and time)
     * @param algorithm       the default algorithm
     * @param level           the default level (ZIP 0-9, Zstd 1-22)
     * @param warnMidBackup   warn before leaving a world while a backup is running
     * @param warnNoBackup    warn before leaving a world not backed up since it was loaded
     * @param listDetails     /backup list also shows algorithm, size and date
     * @param listPageSize    backups per page of /backup list ({@link BackupLimits#MIN_LIST_PAGE_SIZE} to
     *                        {@link BackupLimits#MAX_LIST_PAGE_SIZE})
     * @param backupBeforeRestore a confirmed /backup restore first makes a backup of the current world
     */
    public record Settings(Path backupDirectory, String backupName, Compression algorithm, int level,
                           boolean warnMidBackup, boolean warnNoBackup, boolean listDetails, int listPageSize,
                           boolean backupBeforeRestore) {

        /** Used until the player picks something else. */
        public static final Settings FACTORY = new Settings(null, "", Compression.ZSTD, 3, true, false, false, 10, false);

        Settings withBackupDirectory(Path value) {
            return new Settings(value, backupName, algorithm, level, warnMidBackup, warnNoBackup, listDetails, listPageSize, backupBeforeRestore);
        }

        Settings withBackupName(String value) {
            return new Settings(backupDirectory, value, algorithm, level, warnMidBackup, warnNoBackup, listDetails, listPageSize, backupBeforeRestore);
        }

        /** Changes the algorithm; a level that does not exist for it is moved to the nearest valid one. */
        Settings withAlgorithm(Compression value) {
            int nearest = Math.max(value.minLevel(), Math.min(value.maxLevel(), level));
            return new Settings(backupDirectory, backupName, value, nearest, warnMidBackup, warnNoBackup, listDetails, listPageSize, backupBeforeRestore);
        }

        /** The caller checks that the level is valid for the algorithm. */
        Settings withLevel(int value) {
            return new Settings(backupDirectory, backupName, algorithm, value, warnMidBackup, warnNoBackup, listDetails, listPageSize, backupBeforeRestore);
        }

        Settings withWarnMidBackup(boolean value) {
            return new Settings(backupDirectory, backupName, algorithm, level, value, warnNoBackup, listDetails, listPageSize, backupBeforeRestore);
        }

        Settings withWarnNoBackup(boolean value) {
            return new Settings(backupDirectory, backupName, algorithm, level, warnMidBackup, value, listDetails, listPageSize, backupBeforeRestore);
        }

        Settings withListDetails(boolean value) {
            return new Settings(backupDirectory, backupName, algorithm, level, warnMidBackup, warnNoBackup, value, listPageSize, backupBeforeRestore);
        }

        Settings withListPageSize(int value) {
            return new Settings(backupDirectory, backupName, algorithm, level, warnMidBackup, warnNoBackup, listDetails, value, backupBeforeRestore);
        }

        Settings withBackupBeforeRestore(boolean value) {
            return new Settings(backupDirectory, backupName, algorithm, level, warnMidBackup, warnNoBackup, listDetails, listPageSize, value);
        }
    }

    // Property keys in config/ezbackup.properties.
    private static final String PROPERTY_BACKUP_DIRECTORY = "backup_directory";
    private static final String PROPERTY_BACKUP_NAME = "backup_name";
    private static final String PROPERTY_DEFAULT_ALGORITHM = "default_algorithm";
    private static final String PROPERTY_DEFAULT_LEVEL = "default_level";
    private static final String PROPERTY_WARN_MID_BACKUP = "warn_mid_backup";
    private static final String PROPERTY_WARN_NO_BACKUP = "warn_no_backup";
    private static final String PROPERTY_LIST_DETAILS = "list_details";
    private static final String PROPERTY_LIST_PAGE_SIZE = "list_page_size";
    private static final String PROPERTY_BACKUP_BEFORE_RESTORE = "backup_before_restore";
    /** Name of the first version of the warn_mid_backup setting; still read so old config files keep working. */
    private static final String LEGACY_PROPERTY_WARN_ON_DISCONNECT = "warn_on_disconnect";

    /** Shown whenever a backup is requested but no folder has been chosen. */
    public static final String NO_PATH_MESSAGE =
            "No file path specified to store backups! Open the escape menu, click the B button, and save a folder path.";

    private static volatile Settings current = Settings.FACTORY;
    private static volatile Path configFile;                            // null until load() has been called

    /** One consistent snapshot of all settings. Use it when you need two or more values that belong together. */
    public static Settings settings() {
        return current;
    }

    /** The folder backups are stored in, or null if the player has not chosen one yet. */
    public static Path backupDirectory() {
        return current.backupDirectory();
    }

    /**
     * The file name (without extension) the GUI "Backup" button uses, or "" if none was set, in which case the
     * button names the backup after the current date and time.
     */
    public static String backupName() {
        return current.backupName();
    }

    /** Algorithm used by "/backup create <name>". */
    public static Compression defaultAlgorithm() {
        return current.algorithm();
    }

    /**
     * Level used by "/backup create <name>", and by "/backup create <name> <algorithm>" when that algorithm is the default one
     * (ZIP 0-9, Zstd 1-22). Always valid for {@link #defaultAlgorithm()}.
     */
    public static int defaultLevel() {
        return current.level();
    }

    /** Whether the client should warn before leaving a world while a backup is still running. */
    public static boolean warnMidBackup() {
        return current.warnMidBackup();
    }

    /** Whether the client should warn before leaving a world that has not been backed up since it was loaded. */
    public static boolean warnNoBackup() {
        return current.warnNoBackup();
    }

    /** Whether /backup list also shows algorithm, size and creation time (false = names only). */
    public static boolean listDetails() {
        return current.listDetails();
    }

    /** How many backups one page of /backup list shows (always between MIN_ and MAX_LIST_PAGE_SIZE). */
    public static int listPageSize() {
        return current.listPageSize();
    }

    /** Whether a confirmed /backup restore first makes a backup of the current world (so the restore can be undone). */
    public static boolean backupBeforeRestore() {
        return current.backupBeforeRestore();
    }

    // ---------------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------------

    /** Reads the saved settings (if any) from the given properties file. Call once at startup. */
    public static synchronized void load(Path file) {
        configFile = file;
        current = Settings.FACTORY;
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            EzBackup.LOGGER.error("Could not read " + file, e);
            return;
        }
        current = parse(props, file);
    }

    /** Builds the settings from the file's properties; every bad or missing value falls back to the factory value. */
    private static Settings parse(Properties props, Path file) {
        Settings factory = Settings.FACTORY;
        Path directory = readPath(props, file, PROPERTY_BACKUP_DIRECTORY, factory.backupDirectory());
        String name = readName(props, file, factory.backupName());
        Compression algorithm = readAlgorithm(props, file, factory.algorithm());
        int level = readInt(props, file, PROPERTY_DEFAULT_LEVEL, algorithm.minLevel(), algorithm.maxLevel(),
                factory.level(), "not valid for " + algorithm.displayName());
        boolean warnMid = readBoolean(
                props.getProperty(PROPERTY_WARN_MID_BACKUP, props.getProperty(LEGACY_PROPERTY_WARN_ON_DISCONNECT, "")),
                factory.warnMidBackup());
        boolean warnNo = readBoolean(props.getProperty(PROPERTY_WARN_NO_BACKUP, ""), factory.warnNoBackup());
        boolean details = readBoolean(props.getProperty(PROPERTY_LIST_DETAILS, ""), factory.listDetails());
        int pageSize = readInt(props, file, PROPERTY_LIST_PAGE_SIZE,
                BackupLimits.MIN_LIST_PAGE_SIZE, BackupLimits.MAX_LIST_PAGE_SIZE, factory.listPageSize(),
                "must be between " + BackupLimits.MIN_LIST_PAGE_SIZE + " and " + BackupLimits.MAX_LIST_PAGE_SIZE);
        boolean backupBeforeRestore = readBoolean(props.getProperty(PROPERTY_BACKUP_BEFORE_RESTORE, ""),
                factory.backupBeforeRestore());
        return new Settings(directory, name, algorithm, level, warnMid, warnNo, details, pageSize, backupBeforeRestore);
    }

    private static String readText(Properties props, String key) {
        return props.getProperty(key, "").trim();
    }

    private static void ignored(Path file, String what, String detail) {
        EzBackup.LOGGER.error("Ignoring " + what + " in " + file + ": " + detail);
    }

    private static Path readPath(Properties props, Path file, String key, Path fallback) {
        String saved = readText(props, key);
        if (saved.isEmpty()) {
            return fallback;
        }
        try {
            return Paths.get(saved);
        } catch (InvalidPathException e) {
            ignored(file, "invalid " + key, saved);
            return fallback;
        }
    }

    private static String readName(Properties props, Path file, String fallback) {
        String saved = readText(props, PROPERTY_BACKUP_NAME);
        if (saved.isEmpty()) {
            return fallback;
        }
        String problem = BackupNames.validate(saved);
        if (problem == null) {
            return saved;
        }
        ignored(file, "invalid " + PROPERTY_BACKUP_NAME, problem);
        return fallback;
    }

    private static Compression readAlgorithm(Properties props, Path file, Compression fallback) {
        String text = readText(props, PROPERTY_DEFAULT_ALGORITHM);
        if (text.isEmpty()) {
            return fallback;
        }
        Compression algorithm = Compression.parse(text);
        if (algorithm == null) {
            ignored(file, "unknown " + PROPERTY_DEFAULT_ALGORITHM, text);
            return fallback;
        }
        return algorithm;
    }

    /** A whole number between min and max; {@code rangeReason} is logged when the number is outside that range. */
    private static int readInt(Properties props, Path file, String key, int min, int max, int fallback,
                               String rangeReason) {
        String text = readText(props, key);
        if (text.isEmpty()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(text);
            if (value >= min && value <= max) {
                return value;
            }
            ignored(file, key + " " + value, rangeReason);
        } catch (NumberFormatException e) {
            ignored(file, "invalid " + key, text);
        }
        return fallback;
    }

    /** "true" / "false" (any case) -> that value; anything else -> the fallback. */
    private static boolean readBoolean(String text, boolean fallback) {
        String trimmed = text.trim();
        if (trimmed.equalsIgnoreCase("true")) {
            return true;
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return false;
        }
        return fallback;
    }

    // ---------------------------------------------------------------------------------------------
    // Changing and saving. Every setter returns null on success, otherwise a message for the player
    // (and then nothing has changed).
    // ---------------------------------------------------------------------------------------------

    /** Validates, applies and saves a folder path typed by the player. */
    public static synchronized String setBackupDirectory(String raw) {
        String text = raw == null ? "" : raw.trim();
        // Windows "Copy as path" wraps the path in quotes.
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        if (text.isEmpty()) {
            return "Enter a file path first.";
        }
        Path path;
        try {
            path = Paths.get(text);
        } catch (InvalidPathException e) {
            return "That is not a valid file path.";
        }
        if (Files.exists(path) && !Files.isDirectory(path)) {
            return "That path is a file, not a folder.";
        }
        return store(current.withBackupDirectory(path));
    }

    /**
     * Validates, applies and saves the backup file name used by the GUI "Backup" button. An empty name clears it
     * (backups are then named after the date and time).
     */
    public static synchronized String setBackupName(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (!text.isEmpty()) {
            String problem = BackupNames.validate(text);
            if (problem != null) {
                return problem;
            }
        }
        return store(current.withBackupName(text));
    }

    /**
     * Changes the default algorithm. If the current default level does not exist for the new algorithm it is
     * moved to the nearest valid one.
     */
    public static synchronized String setDefaultAlgorithm(Compression algorithm) {
        return store(current.withAlgorithm(algorithm));
    }

    /** Changes the default level (must be valid for the default algorithm). */
    public static synchronized String setDefaultLevel(int level) {
        String problem = current.algorithm().levelProblem(level);
        if (problem != null) {
            return problem;
        }
        return store(current.withLevel(level));
    }

    /** Turns the "warn if a backup is running when I leave" setting on or off. */
    public static synchronized String setWarnMidBackup(boolean warn) {
        return store(current.withWarnMidBackup(warn));
    }

    /** Turns the "warn if I have not backed up since loading this world" setting on or off. */
    public static synchronized String setWarnNoBackup(boolean warn) {
        return store(current.withWarnNoBackup(warn));
    }

    /** Turns the extra information (algorithm, size, date) in /backup list on or off. */
    public static synchronized String setListDetails(boolean details) {
        return store(current.withListDetails(details));
    }

    /** Sets how many backups one /backup list page shows (1-50). */
    public static synchronized String setListPageSize(int size) {
        if (size < BackupLimits.MIN_LIST_PAGE_SIZE || size > BackupLimits.MAX_LIST_PAGE_SIZE) {
            return "Backups per page must be between " + BackupLimits.MIN_LIST_PAGE_SIZE + " and "
                    + BackupLimits.MAX_LIST_PAGE_SIZE + ".";
        }
        return store(current.withListPageSize(size));
    }

    /** Turns the automatic backup of the current world before a restore on or off. */
    public static synchronized String setBackupBeforeRestore(boolean backup) {
        return store(current.withBackupBeforeRestore(backup));
    }

    /** Writes every setting to the properties file; only when that works do the new settings replace the old ones. */
    private static String store(Settings next) {
        Path file = configFile;
        if (file == null) {
            return "Settings are not ready yet.";
        }
        Properties props = new Properties();
        props.setProperty(PROPERTY_BACKUP_DIRECTORY, next.backupDirectory() == null ? "" : next.backupDirectory().toString());
        props.setProperty(PROPERTY_BACKUP_NAME, next.backupName());
        props.setProperty(PROPERTY_DEFAULT_ALGORITHM, next.algorithm().commandName());
        props.setProperty(PROPERTY_DEFAULT_LEVEL, Integer.toString(next.level()));
        props.setProperty(PROPERTY_WARN_MID_BACKUP, Boolean.toString(next.warnMidBackup()));
        props.setProperty(PROPERTY_WARN_NO_BACKUP, Boolean.toString(next.warnNoBackup()));
        props.setProperty(PROPERTY_LIST_DETAILS, Boolean.toString(next.listDetails()));
        props.setProperty(PROPERTY_LIST_PAGE_SIZE, Integer.toString(next.listPageSize()));
        props.setProperty(PROPERTY_BACKUP_BEFORE_RESTORE, Boolean.toString(next.backupBeforeRestore()));
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                props.store(writer, "EzBackup settings");
            }
        } catch (IOException e) {
            EzBackup.LOGGER.error("Could not save " + file, e);
            return "Could not save the setting: " + e.getMessage();
        }
        current = next;
        return null;
    }
}
