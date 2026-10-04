package com.ezbackup;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rules for backup names, and the mapping between a name ("before-dragon") and its file
 * ("before-dragon.zip"). Makes no Minecraft calls; its only link to the command side is the reserved sub-command
 * list, {@link BackupCommand#SUBCOMMANDS}.
 */
final class BackupNames {

    /** Shown whenever a backup with the requested name is already in the backup folder. */
    static final String DUPLICATE_MESSAGE = "Backup with that name already exists!";

    private static final Pattern VALID_NAME =
            Pattern.compile("[A-Za-z0-9_-]{1," + BackupLimits.MAX_NAME_LENGTH + "}");

    /** Windows device names: a file called "con" cannot be created there. */
    private static final Set<String> WINDOWS_DEVICE_NAMES = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private BackupNames() {
    }

    /** Returns an error message, or null when the name is acceptable. */
    static String validate(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            return "Backup names may only contain letters, numbers, _ and - (max "
                    + BackupLimits.MAX_NAME_LENGTH + " characters).";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (BackupCommand.SUBCOMMANDS.contains(lower) || WINDOWS_DEVICE_NAMES.contains(lower)) {
            return "That backup name is reserved. Please choose another.";
        }
        return null;
    }

    /** True if any backup with this logical name exists, whatever algorithm it used. Case-insensitive (Windows). */
    static boolean exists(Path backupDir, String name) throws IOException {
        String wanted = name.toLowerCase(Locale.ROOT);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupDir)) {
            for (Path p : stream) {
                String file = p.getFileName().toString().toLowerCase(Locale.ROOT);
                for (Compression c : Compression.values()) {
                    if (file.equals(fileNameFor(wanted, c))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The backup file called {@code name} (any algorithm extension, case-insensitive like {@link #exists}), or null
     * if there is none. Used by /backup restore.
     */
    static Path find(Path backupDir, String name) throws IOException {
        String wanted = name.toLowerCase(Locale.ROOT);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupDir)) {
            for (Path p : stream) {
                String file = p.getFileName().toString().toLowerCase(Locale.ROOT);
                for (Compression c : Compression.values()) {
                    if (file.equals(fileNameFor(wanted, c)) && Files.isRegularFile(p)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    /** "before-dragon" + ZIP -> "before-dragon.zip" (the inverse of {@link #logicalName}). */
    static String fileNameFor(String name, Compression algorithm) {
        return name + algorithm.extension();
    }

    /** "before-dragon.zip" -> "before-dragon". Unknown file names are returned unchanged. */
    static String logicalName(String fileName) {
        Compression c = Compression.fromFileName(fileName);
        return c == null ? fileName : fileName.substring(0, fileName.length() - c.extension().length());
    }
}
