package com.ezbackup;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds the backups in the backup folder and turns one page of them into text lines for {@code /backup list}.
 * Pure Java: it sends nothing and knows no Minecraft classes; {@link BackupManager#list} sends the lines.
 */
final class BackupListing {

    private static final DateTimeFormatter LIST_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private BackupListing() {
    }

    /** One backup file found by /backup list. {@code info} is null if the file could not be read. */
    record Listed(Path file, BackupArchive.Info info) {
        long sortKey() {
            return info == null ? Long.MIN_VALUE : info.createdMillis();
        }
    }

    /**
     * Every backup file directly inside {@code backupDir}, newest first (equal times or unreadable files fall back
     * to name order so pages are stable). Hidden files (temporary snapshots / partial files) and files without a known
     * algorithm extension are ignored.
     */
    static List<Listed> find(Path backupDir) throws IOException {
        List<Listed> found = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupDir)) {
            for (Path p : stream) {
                String fileName = p.getFileName().toString();
                if (!fileName.startsWith(".") && Compression.fromFileName(fileName) != null
                        && Files.isRegularFile(p)) {
                    BackupArchive.Info info;
                    try {
                        info = BackupArchive.readInfo(p);
                    } catch (IOException e) {
                        info = null;
                    }
                    found.add(new Listed(p, info));
                }
            }
        }
        found.sort(Comparator.comparingLong(Listed::sortKey).reversed()
                .thenComparing(l -> l.file().getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return found;
    }

    /**
     * The logical names of the backups in {@code backupDir}, newest first by file modification time (cheap: no archive is
     * opened, unlike {@link #find}). Same file rules as {@link #find}. Used by the restore dropdown of the settings screen.
     */
    static List<String> namesNewestFirst(Path backupDir) throws IOException {
        record Named(String name, long modified) {
        }
        List<Named> found = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupDir)) {
            for (Path p : stream) {
                String fileName = p.getFileName().toString();
                if (!fileName.startsWith(".") && Compression.fromFileName(fileName) != null && Files.isRegularFile(p)) {
                    long modified;
                    try {
                        modified = Files.getLastModifiedTime(p).toMillis();
                    } catch (IOException e) {
                        modified = Long.MIN_VALUE;
                    }
                    found.add(new Named(BackupNames.logicalName(fileName), modified));
                }
            }
        }
        found.sort(Comparator.comparingLong(Named::modified).reversed()
                .thenComparing(Named::name, String.CASE_INSENSITIVE_ORDER));
        List<String> names = new ArrayList<>();
        for (Named n : found) {
            names.add(n.name());
        }
        return names;
    }

    static int pageCount(int entries, int pageSize) {
        return (entries + pageSize - 1) / pageSize;
    }

    /** Null if {@code page} exists, otherwise the sentence to show. */
    static String pageProblem(int page, int pageCount) {
        if (page > pageCount) {
            return "Page " + page + " does not exist. There "
                    + (pageCount == 1 ? "is only 1 page" : "are only " + pageCount + " pages") + ".";
        }
        return null;
    }

    /**
     * The lines of one page: the "Page X of Y" header, then "N. name" per backup (numbers keep counting across pages);
     * with {@code details} the algorithm, size and creation time follow the name. The page must exist.
     */
    static List<String> pageLines(List<Listed> found, int page, int pageSize, boolean details) {
        int first = (page - 1) * pageSize;
        int last = Math.min(found.size(), first + pageSize);
        List<String> lines = new ArrayList<>();
        lines.add("Page " + page + " of " + pageCount(found.size(), pageSize));
        for (int i = first; i < last; i++) {
            Listed entry = found.get(i);
            String line = (i + 1) + ". " + BackupNames.logicalName(entry.file().getFileName().toString());
            if (details) {
                if (entry.info() == null) {
                    line += "  (unreadable)";
                } else {
                    line += "  -  " + entry.info().algorithm().displayName()
                            + ", " + Formatting.size(entry.info().archiveSize())
                            + ", created " + LIST_TIME.format(Instant.ofEpochMilli(entry.info().createdMillis()));
                }
            }
            lines.add(line);
        }
        return lines;
    }
}
