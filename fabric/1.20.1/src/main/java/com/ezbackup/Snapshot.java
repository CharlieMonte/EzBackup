package com.ezbackup;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * Makes an exact copy of a world folder (the "snapshot") that the slow compression can read while the
 * game keeps running. This class has no Minecraft dependencies and never modifies the source.
 *
 * Exactly one file is deliberately not copied: {@code session.lock} in the world's top folder. Minecraft keeps
 * that file locked while a world is open (so Windows cannot copy it), and it only stores a lock stamp,
 * not world data. Minecraft recreates it whenever the world is opened.
 *
 * Two folders are skipped as well, because they are this mod's own temporary working space and never world data:
 * {@code .ez-restore} and the restore holding dimension (see {@link RestoreFiles#excludedFromBackup}).
 */
final class Snapshot {

    static final String SKIPPED_ROOT_FILE = "session.lock";

    private Snapshot() {
    }

    private static boolean skipped(Path root, Path file) {
        Path parent = file.getParent();
        return parent != null && parent.equals(root)
                && file.getFileName().toString().equalsIgnoreCase(SKIPPED_ROOT_FILE);
    }

    /** Counts what {@link #copy} would copy, without copying anything. */
    static BackupArchive.Stats scan(Path source) throws IOException {
        long[] totals = new long[3];
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (RestoreFiles.excludedFromBackup(source, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                totals[1]++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                requireRegular(file, attrs);
                if (!skipped(source, file)) {
                    totals[0]++;
                    totals[2] += attrs.size();
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return new BackupArchive.Stats(totals[0], totals[1], totals[2]);
    }

    /**
     * Copies {@code source} into the already-existing empty directory {@code target}, keeping
     * last-modified times. Fails (rather than silently skipping) on anything it cannot copy exactly.
     */
    static BackupArchive.Stats copy(Path source, Path target) throws IOException {
        long[] totals = new long[3];
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (RestoreFiles.excludedFromBackup(source, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!dir.equals(source)) {
                    Files.createDirectory(target.resolve(source.relativize(dir).toString()));
                }
                totals[1]++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                requireRegular(file, attrs);
                if (skipped(source, file)) {
                    return FileVisitResult.CONTINUE;
                }
                Path destination = target.resolve(source.relativize(file).toString());
                Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
                totals[0]++;
                totals[2] += Files.size(destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                // Creating files changed the copy's folder time; put back the original one.
                FileTime modified = Files.getLastModifiedTime(dir);
                Path destination = dir.equals(source) ? target : target.resolve(source.relativize(dir).toString());
                Files.setLastModifiedTime(destination, modified);
                return FileVisitResult.CONTINUE;
            }
        });
        return new BackupArchive.Stats(totals[0], totals[1], totals[2]);
    }

    private static void requireRegular(Path file, BasicFileAttributes attrs) throws IOException {
        if (!attrs.isRegularFile()) {
            throw new IOException("Cannot back up " + file
                    + " because it is a symbolic link or special file, and it could not be copied exactly.");
        }
    }

    /**
     * Deletes a directory tree without following links. Only ever call this on a temporary folder
     * that this mod created.
     */
    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                deleteOne(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                deleteOne(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteOne(Path path) throws IOException {
        try {
            Files.delete(path);
        } catch (IOException first) {
            // Windows refuses to delete read-only files; clear the flag and try once more.
            if (path.toFile().setWritable(true)) {
                Files.delete(path);
            } else {
                throw first;
            }
        }
    }
}
