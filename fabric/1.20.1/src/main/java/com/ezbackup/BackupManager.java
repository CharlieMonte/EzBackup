package com.ezbackup;

import com.ezbackup.BackupJob.Stage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The /backup command handlers and the workflow of one backup. The job's state lives in {@link BackupJob}, finding and
 * formatting the list in {@link BackupListing}. Flow of one backup:
 *
 * <ol>
 *   <li>(server thread) validate the name, refuse duplicates, check free space ({@link #preflight})</li>
 *   <li>(server thread) save the whole world to disk, then copy the world folder into a hidden
 *       ".ez-snapshot-UUID" folder inside the backup directory ({@link #launch}). The game is paused while this
 *       happens, so nothing can change mid-copy. Nothing in the live world is ever modified.</li>
 *   <li>(worker thread) compress the snapshot into ".ez-backup-NAME.partial", verify it, then rename it to its
 *       final name ({@link #runWorker}). Finally the snapshot is deleted.</li>
 * </ol>
 *
 * Version-specific Minecraft calls go through {@link McCompat}; this class only uses the stable parts of the API
 * ({@link CommandSourceStack}, {@link MinecraftServer}).
 */
public final class BackupManager {

    private static final String SNAPSHOT_PREFIX = ".ez-snapshot-";
    private static final String PARTIAL_PREFIX = ".ez-backup-";
    private static final String PARTIAL_SUFFIX = ".partial";
    private static final String WORKER_THREAD_NAME = "EzBackup-Worker";
    private static final String NO_BACKUPS_MESSAGE = "No backups yet.";
    private static final String ALREADY_RUNNING_MESSAGE = "A backup is already in progress.";
    private static final String NO_BACKUP_RUNNING_MESSAGE = "No backup is running.";
    /** How long shutdown() waits for a cancelled worker to clean up. */
    private static final long SHUTDOWN_JOIN_MILLIS = 20_000;

    private static final AtomicReference<BackupJob> CURRENT = new AtomicReference<>();

    /** True once a backup has finished (and verified) since the current world was loaded. */
    private static volatile boolean backedUpSinceLoad;

    private BackupManager() {
    }

    // ---------------------------------------------------------------------------------------------
    // /backup create <name> [algorithm] [level]
    // ---------------------------------------------------------------------------------------------

    /** Must be called on the server thread. Returns 1 if a backup was started, 0 if it was refused. */
    public static int start(CommandSourceStack source, String name, Compression algorithm, int level) {
        if (RestoreManager.isRunning()) {
            return fail(source, RestoreManager.ALREADY_RUNNING_MESSAGE);
        }
        if (CURRENT.get() != null) {
            return fail(source, ALREADY_RUNNING_MESSAGE);
        }
        Preflight preflight = preflight(source.getServer(), name, algorithm, level);
        if (preflight.error() != null) {
            return fail(source, preflight.error());
        }
        return launch(source, name, algorithm, level, preflight) != null ? 1 : 0;
    }

    /**
     * Starts the backup that a confirmed restore makes of the current world first (see
     * {@link BackupConfig#backupBeforeRestore()}). Same as {@link #start}, except that the running restore is not an
     * obstacle. Must be called on the server thread. Returns the job (watch {@link BackupJob#done} and
     * {@link BackupJob#succeeded}), or null after telling the player why no backup was started.
     */
    static BackupJob startBeforeRestore(CommandSourceStack source, String name, Compression algorithm, int level) {
        if (CURRENT.get() != null) {
            fail(source, ALREADY_RUNNING_MESSAGE);
            return null;
        }
        Preflight preflight = preflight(source.getServer(), name, algorithm, level);
        if (preflight.error() != null) {
            fail(source, preflight.error());
            return null;
        }
        return launch(source, name, algorithm, level, preflight);
    }

    /**
     * The result of {@link #preflight}: either everything {@link #launch} needs, or {@code error} (the sentence to show;
     * all other fields are null).
     */
    private record Preflight(Path backupDir, Path worldDir, String worldFolder, BackupArchive.Stats stats,
                             String error) {
        static Preflight failed(String error) {
            return new Preflight(null, null, null, null, error);
        }
    }

    /**
     * Everything that is checked before anything is saved or copied: setting, name, level, backup folder (created if
     * missing), duplicate name, compression library, world folder, free space. The order of the checks decides which
     * message a player sees first, so keep it.
     */
    private static Preflight preflight(MinecraftServer server, String name, Compression algorithm, int level) {
        Path configuredDir = BackupConfig.backupDirectory();
        if (configuredDir == null) {
            return Preflight.failed(BackupConfig.NO_PATH_MESSAGE);
        }
        String nameProblem = BackupNames.validate(name);
        if (nameProblem != null) {
            return Preflight.failed(nameProblem);
        }
        String levelProblem = algorithm.levelProblem(level);
        if (levelProblem != null) {
            return Preflight.failed(levelProblem);
        }

        Path backupDir = configuredDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            return Preflight.failed("Cannot use the backup directory " + backupDir + ": " + e.getMessage());
        }

        // Duplicate check comes first, before anything expensive. The algorithm does not matter.
        String duplicateProblem = duplicateProblem(backupDir, name);
        if (duplicateProblem != null) {
            return Preflight.failed(duplicateProblem);
        }

        try {
            algorithm.ensureAvailable();
        } catch (IOException e) {
            EzBackup.LOGGER.error("Compression library problem", e);
            return Preflight.failed(e.getMessage());
        }

        try {
            Path worldDir = McCompat.worldFolder(server).toAbsolutePath().normalize().toRealPath();
            Path backupReal = backupDir.toRealPath();
            if (backupReal.startsWith(worldDir)) {
                return Preflight.failed("The backup directory must not be inside the world folder.");
            }
            BackupArchive.Stats stats = Snapshot.scan(worldDir);
            long free = Files.getFileStore(backupDir).getUsableSpace();
            long needed = stats.bytes() + BackupLimits.FREE_SPACE_MARGIN_BYTES;
            if (free < needed) {
                return Preflight.failed("Not enough free space on the backup drive: need about "
                        + Formatting.size(needed) + ", have " + Formatting.size(free) + ".");
            }
            return new Preflight(backupDir, worldDir, worldDir.getFileName().toString(), stats, null);
        } catch (IOException e) {
            EzBackup.LOGGER.error("Backup pre-flight check failed", e);
            return Preflight.failed("Backup could not start: " + e.getMessage());
        }
    }

    /**
     * Server-thread part of a backup: registers the job, saves the world, copies it into the snapshot folder and starts
     * the worker thread. If anything fails before the worker has started, the snapshot is deleted and the job cleared.
     * Returns the job, or null (after telling the player) if the backup could not be started.
     */
    private static BackupJob launch(CommandSourceStack source, String name, Compression algorithm, int level,
                              Preflight preflight) {
        MinecraftServer server = source.getServer();
        Path backupDir = preflight.backupDir();
        Path worldDir = preflight.worldDir();

        BackupJob job = new BackupJob(server, source, name, algorithm, level);
        if (!CURRENT.compareAndSet(null, job)) {
            fail(source, ALREADY_RUNNING_MESSAGE);
            return null;
        }

        Path snapshotDir = backupDir.resolve(SNAPSHOT_PREFIX + UUID.randomUUID());
        boolean workerStarted = false;
        try {
            job.say("Starting backup: " + name);
            job.say("Algorithm: " + algorithm.describe(level));
            job.say("Saving the world and copying it. The game will pause until the copy is finished (about "
                    + Formatting.size(preflight.stats().bytes()) + ").");

            job.setStage(Stage.SAVING);
            if (!McCompat.saveWorld(server)) {
                throw new IOException("The server reported that saving the world failed.");
            }

            // The server thread is stuck in this method, so nothing can tick, unload chunks or autosave.
            // The save above has already flushed every pending chunk / entity / POI / player write to disk.
            job.setStage(Stage.COPYING);
            snapshotDir = Files.createDirectory(snapshotDir);
            BackupArchive.Stats snapshotStats = Snapshot.copy(worldDir, snapshotDir);
            job.totalBytes = snapshotStats.bytes();

            job.say("World copied. The game continues now; compressing in the background.");
            Path finalSnapshotDir = snapshotDir;
            String worldFolder = preflight.worldFolder();
            Thread thread = new Thread(
                    () -> runWorker(job, backupDir, finalSnapshotDir, worldFolder, snapshotStats),
                    WORKER_THREAD_NAME);
            thread.setDaemon(true);
            job.thread = thread;
            workerStarted = true;
            thread.start();
            return job;
        } catch (Throwable t) {
            EzBackup.LOGGER.error("Backup snapshot failed", t);
            fail(source, "Backup failed: " + describe(t) + " Nothing was saved.");
            return null;
        } finally {
            if (!workerStarted) {
                deleteSnapshotQuietly(backupDir, snapshotDir);
                CURRENT.compareAndSet(job, null);
            }
        }
    }

    private static void runWorker(BackupJob job, Path backupDir, Path snapshotDir, String worldFolder,
                                  BackupArchive.Stats snapshotStats) {
        Path partial = backupDir.resolve(PARTIAL_PREFIX + job.name + PARTIAL_SUFFIX);
        boolean finished = false;
        try {
            job.setStage(Stage.COMPRESSING);
            BackupArchive.write(snapshotDir, worldFolder, partial, job.name, job.algorithm, job.level, job);

            job.setStage(Stage.VERIFYING);
            job.say("Compression finished. Verifying the backup...");
            BackupArchive.Stats verified =
                    BackupArchive.verify(partial, job.algorithm, snapshotDir, worldFolder, snapshotStats, job);

            job.setStage(Stage.FINISHING);
            if (job.cancelled) {
                throw new CancellationException();
            }
            if (BackupNames.exists(backupDir, job.name)) {
                throw new IOException(BackupNames.DUPLICATE_MESSAGE);
            }
            Path finalPath = backupDir.resolve(BackupNames.fileNameFor(job.name, job.algorithm));
            Files.move(partial, finalPath); // no REPLACE_EXISTING: this can never overwrite anything
            finished = true;
            backedUpSinceLoad = true;
            job.succeeded = true;

            long original = snapshotStats.bytes();
            long compressed = Files.size(finalPath);
            job.say("Backup completed successfully.");
            job.say("Name: " + job.name);
            job.say("Algorithm: " + job.algorithm.describe(job.level));
            job.say("Original size: " + Formatting.size(original));
            job.say("Backup size: " + Formatting.size(compressed));
            job.say("Compression ratio: " + Formatting.ratio(original, compressed));
            job.say("Verified: " + verified.files() + " files, " + verified.dirs() + " folders, all checksums match");
            job.say("Time: " + Formatting.duration(System.nanoTime() - job.startNanos));
            job.say("Location: " + finalPath);
        } catch (CancellationException e) {
            job.fail("Backup cancelled. Nothing was saved.");
        } catch (Throwable t) {
            EzBackup.LOGGER.error("Backup failed", t);
            job.fail("Backup failed: " + describe(t) + " Nothing was saved.");
        } finally {
            if (!finished) {
                try {
                    Files.deleteIfExists(partial);
                } catch (IOException e) {
                    EzBackup.LOGGER.warn("Could not delete temporary file {}", partial, e);
                }
            }
            deleteSnapshotQuietly(backupDir, snapshotDir);
            CURRENT.compareAndSet(job, null);
            job.done = true; // last: by now isRunning() is false and the outcome is final
        }
    }

    // ---------------------------------------------------------------------------------------------
    // /backup status, /backup cancel
    // ---------------------------------------------------------------------------------------------

    public static int status(CommandSourceStack source) {
        BackupJob job = CURRENT.get();
        boolean restoring = RestoreManager.isRunning();
        if (job == null && !restoring) {
            McCompat.sendSuccess(source, NO_BACKUP_RUNNING_MESSAGE);
            return 1;
        }
        if (job != null) {
            job.statusLines().forEach(line -> McCompat.sendSuccess(source, line));
        }
        if (restoring) {
            RestoreManager.statusLines().forEach(line -> McCompat.sendSuccess(source, line));
        }
        return 1;
    }

    /** Cancels the running backup; if only a restore is running, cancels that (while it still can be). */
    public static int cancel(CommandSourceStack source) {
        BackupJob job = CURRENT.get();
        if (job == null) {
            if (RestoreManager.isRunning()) {
                return RestoreManager.cancel(source);
            }
            McCompat.sendSuccess(source, NO_BACKUP_RUNNING_MESSAGE);
            return 1;
        }
        job.cancelled = true;
        McCompat.sendSuccess(source, "Cancelling backup " + job.name + "...");
        return 1;
    }

    /** The sentence shown when the backup directory cannot be read. */
    private static String directoryProblem(Path dir, IOException e) {
        return "Cannot read the backup directory " + dir + ": " + e.getMessage();
    }

    /** Null if no backup called {@code name} (with any algorithm extension) exists in {@code dir}, else the sentence to show. */
    private static String duplicateProblem(Path dir, String name) {
        try {
            return BackupNames.exists(dir, name) ? BackupNames.DUPLICATE_MESSAGE : null;
        } catch (IOException e) {
            return directoryProblem(dir, e);
        }
    }

    /**
     * Checks a name the GUI wants to use: returns an error message if it is not allowed or a backup with that name
     * already exists in the backup folder, otherwise null. Safe to call from any thread.
     */
    public static String nameProblem(String name) {
        String problem = BackupNames.validate(name);
        if (problem != null) {
            return problem;
        }
        Path dir = BackupConfig.backupDirectory();
        if (dir == null) {
            return BackupConfig.NO_PATH_MESSAGE;
        }
        dir = dir.toAbsolutePath().normalize();
        return Files.isDirectory(dir) ? duplicateProblem(dir, name) : null;
    }

    /** True from the moment a backup is started until it has finished, failed or been cancelled. Safe to call from any thread. */
    public static boolean isRunning() {
        return CURRENT.get() != null;
    }

    /** True while a /backup restore is running. Safe to call from any thread. */
    public static boolean isRestoring() {
        return RestoreManager.isRunning();
    }

    /**
     * The names of the backups in the configured backup folder, newest first (empty when no folder is set or it does not
     * exist yet). Used by the restore dropdown of the settings screen; reads file names and times only.
     */
    public static java.util.List<String> backupNames() throws IOException {
        Path dir = BackupConfig.backupDirectory();
        if (dir == null) {
            return java.util.List.of();
        }
        dir = dir.toAbsolutePath().normalize();
        return Files.isDirectory(dir) ? BackupListing.namesNewestFirst(dir) : java.util.List.of();
    }

    /** True if a backup has completed and verified since the current world was loaded. */
    public static boolean hasBackedUpSinceLoad() {
        return backedUpSinceLoad;
    }

    /** Called when a world is loaded: nothing has been backed up yet. */
    public static void worldLoaded() {
        backedUpSinceLoad = false;
    }

    /** Called when the world/server is closing: stop a running backup cleanly so no temporary files are left. */
    public static void shutdown() {
        BackupJob job = CURRENT.get();
        if (job == null) {
            return;
        }
        job.cancelled = true;
        Thread thread = job.thread;
        if (thread != null) {
            try {
                thread.join(SHUTDOWN_JOIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // /backup list
    // ---------------------------------------------------------------------------------------------

    /**
     * Lists the backups, newest first, {@link BackupConfig#listPageSize()} per page, under a "Page X of Y" header.
     * By default each line is just "N. name"; with {@link BackupConfig#listDetails()} on, the algorithm, size and creation time follow the name.
     * Numbers keep counting across pages (page 2 of 10 per page starts at 11). Finding and formatting is {@link BackupListing}.
     */
    public static int list(CommandSourceStack source, int page) {
        Path configuredDir = BackupConfig.backupDirectory();
        if (configuredDir == null) {
            return fail(source, BackupConfig.NO_PATH_MESSAGE);
        }
        Path backupDir = configuredDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(backupDir)) {
            McCompat.sendSuccess(source, NO_BACKUPS_MESSAGE);
            return 1;
        }
        List<BackupListing.Listed> found;
        try {
            found = BackupListing.find(backupDir);
        } catch (IOException e) {
            return fail(source, directoryProblem(backupDir, e));
        }
        if (found.isEmpty()) {
            McCompat.sendSuccess(source, NO_BACKUPS_MESSAGE);
            return 1;
        }

        BackupConfig.Settings settings = BackupConfig.settings();
        int pageCount = BackupListing.pageCount(found.size(), settings.listPageSize());
        String pageProblem = BackupListing.pageProblem(page, pageCount);
        if (pageProblem != null) {
            return fail(source, pageProblem);
        }
        BackupListing.pageLines(found, page, settings.listPageSize(), settings.listDetails())
                .forEach(line -> McCompat.sendSuccess(source, line));
        return 1;
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Deletes the snapshot folder, but only if it really is one of ours directly inside the backup directory. */
    private static void deleteSnapshotQuietly(Path backupDir, Path snapshotDir) {
        try {
            if (snapshotDir != null
                    && backupDir.equals(snapshotDir.getParent())
                    && snapshotDir.getFileName().toString().startsWith(SNAPSHOT_PREFIX)) {
                Snapshot.deleteTree(snapshotDir);
            }
        } catch (IOException e) {
            EzBackup.LOGGER.warn("Could not fully delete temporary snapshot {} - you can delete it manually", snapshotDir, e);
        }
    }

    private static int fail(CommandSourceStack source, String message) {
        McCompat.sendFailure(source, message);
        return 0;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() + "." : message;
    }
}
