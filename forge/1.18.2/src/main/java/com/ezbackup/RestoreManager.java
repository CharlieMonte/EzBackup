package com.ezbackup;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * /backup restore &lt;name&gt; [confirm]: puts a backup back as the live world while the server keeps running (so
 * an e4mc / LAN session and every player connection stay up).
 *
 * <p>The restore is a state machine driven from the server tick ({@link #tick}); the only slow work (unpacking the
 * archive) runs on a worker thread while the game is still being played. Order of events:
 * <ol>
 *   <li>{@code restore <name>} validates and asks for {@code confirm}; {@code confirm} starts the job.
 *       If "Auto Backup When Restoring" is on ({@link BackupConfig#backupBeforeRestore()}) the current world is backed up
 *       first (SAFETY_BACKUP, a normal backup named {@code pre-restore-<date>_<time>}); if that backup fails or is
 *       cancelled the restore stops with nothing changed. Otherwise no backup is made and the restore cannot be undone
 *       afterwards. Then the job goes to unpacking.</li>
 *   <li>EXTRACTING / VALIDATING: the archive is unpacked into {@code <world>/.ez-restore/incoming} and checked.
 *       A bad archive stops here, with nothing changed.</li>
 *   <li>EVACUATING: each player's data is copied aside, then they are moved to the empty holding dimension (a small
 *       invisible barrier floor over void). Players keep their game mode throughout.</li>
 *   <li>SWAPPING (one server tick, cannot be cancelled): save, close all dimensions, move the files, apply the
 *       backup's world-wide level.dat values, rebuild the dimensions, bring the players back (0.01 blocks higher than
 *       saved, so nobody sinks into the block they stood on). Any failure undoes everything ({@link #rollbackSwap}).</li>
 *   <li>FINISHING: remove the holding dimension, delete the temporary files.</li>
 * </ol>
 * The file work is in {@link RestoreFiles} (pure Java), everything that touches Minecraft internals is in
 * {@link McWorldSwap}. Only one backup OR restore can run at a time.
 */
final class RestoreManager {

    static final String ALREADY_RUNNING_MESSAGE = "A restore is already in progress.";

    private static final AtomicReference<RestoreJob> CURRENT = new AtomicReference<>();
    /** Name of the backup made before a restore: this prefix plus the date and time. */
    private static final String SAFETY_BACKUP_PREFIX = "pre-restore-";
    private static final DateTimeFormatter SAFETY_BACKUP_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter CREATED_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final int SHUTDOWN_JOIN_MILLIS = 20_000;
    /** Ticks to wait for disconnected players to leave the holding dimension before it is closed anyway (20 = 1 s). */
    private static final int MAX_FINISH_WAIT_TICKS = 200;
    private static final long WIPE_TIMEOUT_MILLIS = 60_000;

    private record Pending(String name, String sourceKey, long expiresMillis) {
    }

    private static volatile Pending pending;

    /** Players disconnected because the backup has no data for them: their files are deleted once they are gone (UUID -> give-up time). */
    private static final Map<UUID, Long> WIPES = new ConcurrentHashMap<>();

    private RestoreManager() {
    }

    static boolean isRunning() {
        return CURRENT.get() != null;
    }

    /** True while there is anything for {@link #tick} to do (a restore running, or players waiting to have their files reset). */
    static boolean needsTick() {
        return CURRENT.get() != null || !WIPES.isEmpty();
    }

    static List<String> statusLines() {
        RestoreJob job = CURRENT.get();
        return job == null ? List.of() : job.statusLines();
    }

    // ---------------------------------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------------------------------

    static int restore(CommandSourceStack source, String name, boolean confirm) {
        MinecraftServer server = source.getServer();
        if (isRunning()) {
            return fail(source, ALREADY_RUNNING_MESSAGE);
        }
        if (BackupManager.isRunning()) {
            return fail(source, "A backup is in progress. Wait until it has finished (or /backup cancel), then try again.");
        }
        String unavailable = McWorldSwap.unavailableReason();
        if (unavailable != null) {
            return fail(source, unavailable);
        }
        Path configured = BackupConfig.backupDirectory();
        if (configured == null) {
            return fail(source, BackupConfig.NO_PATH_MESSAGE);
        }
        String nameProblem = BackupNames.validate(name);
        if (nameProblem != null) {
            return fail(source, nameProblem);
        }

        Path backupDir = configured.toAbsolutePath().normalize();
        Path archive;
        Compression algorithm;
        BackupArchive.Info info;
        Path world;
        try {
            archive = Files.isDirectory(backupDir) ? BackupNames.find(backupDir, name) : null;
            if (archive == null) {
                return fail(source, "There is no backup named \"" + name + "\". Use /backup list to see your backups.");
            }
            algorithm = Compression.fromFileName(archive.getFileName().toString());
            algorithm.ensureAvailable();
            info = BackupArchive.readInfo(archive);
            world = McCompat.worldFolder(server).toAbsolutePath().normalize().toRealPath();
            if (Files.exists(RestoreFiles.workDir(world))) {
                return fail(source, "Files of an earlier restore are still in the world folder (" + RestoreFiles.WORK_DIR
                        + "). Restart the server once to clean them up, then try again.");
            }
            long worldBytes = Snapshot.scan(world).bytes();
            long needed = Math.max(worldBytes, info.archiveSize()) + BackupLimits.RESTORE_FREE_SPACE_MARGIN_BYTES;
            long free = Files.getFileStore(world).getUsableSpace();
            if (free < needed) {
                return fail(source, "Not enough free space on the world's drive: the backup is unpacked there first, need about "
                        + Formatting.size(needed) + ", have " + Formatting.size(free) + ".");
            }
        } catch (IOException e) {
            EzBackup.LOGGER.error("Restore pre-flight check failed", e);
            return fail(source, "Restore could not start: " + e.getMessage());
        }

        String key = sourceKey(source);
        if (!confirm) {
            pending = new Pending(name, key, System.currentTimeMillis() + BackupLimits.RESTORE_CONFIRM_MILLIS);
            McCompat.sendSuccess(source, "Restore backup \"" + name + "\" (" + algorithm.displayName() + ", "
                    + Formatting.size(info.archiveSize()) + ", made " + CREATED_TIME.format(Instant.ofEpochMilli(info.createdMillis())) + ")?");
            McCompat.sendSuccess(source, "- The ENTIRE current world is replaced by the backup. The server stays up.");
            if (BackupConfig.backupBeforeRestore()) {
                McCompat.sendSuccess(source, "- The current world is backed up first (named " + SAFETY_BACKUP_PREFIX
                        + "<date>_<time>), so this can be undone. If that backup fails, nothing is restored.");
            } else {
                McCompat.sendWarning(source, "NO backup of the current world is made first: this cannot be undone unless you already have a backup of it.");
            }
            McCompat.sendSuccess(source, "Type /backup restore " + name + " confirm within "
                    + BackupLimits.RESTORE_CONFIRM_MILLIS / 1000 + " seconds to continue.");
            return 1;
        }

        Pending p = pending;
        pending = null;
        if (p == null || !p.name().equalsIgnoreCase(name) || !p.sourceKey().equals(key)
                || System.currentTimeMillis() > p.expiresMillis()) {
            return fail(source, "Nothing to confirm. Type /backup restore " + name + " first, then confirm within "
                    + BackupLimits.RESTORE_CONFIRM_MILLIS / 1000 + " seconds.");
        }
        return begin(source, name, archive, algorithm, world);
    }

    static int cancel(CommandSourceStack source) {
        RestoreJob job = CURRENT.get();
        if (job == null) {
            McCompat.sendSuccess(source, "No restore is running.");
            return 1;
        }
        if (!job.cancellable()) {
            return fail(source, "The restore is already swapping the world and can no longer be cancelled.");
        }
        job.cancelled = true;
        McCompat.sendSuccess(source, "Cancelling the restore. Nothing has been changed.");
        return 1;
    }

    /**
     * Confirmed: creates the job, opens the holding dimension (which also proves the world-swap machinery works before
     * anything else is done) and starts unpacking the archive. Returns 0 (nothing changed) if another restore is
     * running or the holding dimension could not be created.
     */
    private static int begin(CommandSourceStack source, String name, Path archive, Compression algorithm, Path world) {
        MinecraftServer server = source.getServer();
        RestoreJob job = new RestoreJob(server, source, name, archive, algorithm, world);
        if (!CURRENT.compareAndSet(null, job)) {
            return fail(source, ALREADY_RUNNING_MESSAGE);
        }
        try {
            McWorldSwap.openVoidLevel(server); // proves the machinery works before anything else is done
        } catch (Throwable t) {
            EzBackup.LOGGER.error("Could not create the holding dimension", t);
            CURRENT.compareAndSet(job, null);
            return fail(source, "Restore could not start: the holding dimension could not be created (" + describe(t)
                    + "). Nothing was changed.");
        }
        if (BackupConfig.backupBeforeRestore()) {
            job.say("Restore of \"" + name + "\" started.");
            return startSafetyBackup(source, job) ? 1 : 0;
        }
        job.say("Restore of \"" + name + "\" started (no safety backup of the current world is made).");
        startExtraction(job);
        return 1;
    }

    /**
     * First step when "Auto Backup When Restoring" is on: starts a normal backup of the current world with the default
     * algorithm and level. The restore waits for it in {@link #safetyBackupTick}. If the backup cannot even be started
     * (no space, name taken, ...) the restore is stopped here with nothing changed; returns false then.
     */
    private static boolean startSafetyBackup(CommandSourceStack source, RestoreJob job) {
        BackupConfig.Settings defaults = BackupConfig.settings();
        String safetyName = SAFETY_BACKUP_PREFIX + LocalDateTime.now().format(SAFETY_BACKUP_TIME);
        job.setStage(RestoreJob.Stage.SAFETY_BACKUP);
        job.say("First backing up the current world as \"" + safetyName + "\" ("
                + defaults.algorithm().describe(defaults.level()) + ").");
        BackupJob backup = BackupManager.startBeforeRestore(source, safetyName, defaults.algorithm(), defaults.level());
        if (backup == null) {
            abort(job, "Restore stopped: the backup of the current world could not be started. Nothing was changed.");
            return false;
        }
        job.safetyBackup = backup;
        job.safetyName = safetyName;
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Server tick: the state machine
    // ---------------------------------------------------------------------------------------------

    static void tick(MinecraftServer server) {
        tickWipes(server);
        RestoreJob job = CURRENT.get();
        if (job == null) {
            return;
        }
        try {
            switch (job.stage) {
                case SAFETY_BACKUP -> safetyBackupTick(job);
                case EXTRACTING, VALIDATING -> {
                    if (!job.extractDone) {
                        return;
                    }
                    if (job.extractError != null) {
                        abort(job, job.extractError + " Nothing was changed.");
                    } else if (job.cancelled) {
                        quietly(() -> RestoreFiles.discard(job.world));
                        abort(job, "Restore cancelled. Nothing was changed.");
                    } else {
                        job.setStage(RestoreJob.Stage.EVACUATING);
                        job.say("Backup unpacked and checked. Moving players to the holding dimension...");
                    }
                }
                case EVACUATING -> evacuationTick(server, job);
                case FINISHING -> finishingTick(server, job);
            }
        } catch (Throwable t) {
            EzBackup.LOGGER.error("Restore failed unexpectedly", t);
            if (job.stage == RestoreJob.Stage.SWAPPING || job.filesSwapped) {
                rollbackSwap(job, t);
            } else {
                abort(job, "The restore failed: " + describe(t) + " Nothing was changed.");
            }
        }
    }

    /**
     * Waits for the backup of the current world. Only a backup that finished and verified lets the restore go on; a
     * failed or cancelled one (or a cancelled restore) ends it here, before anything has been changed.
     */
    private static void safetyBackupTick(RestoreJob job) {
        BackupJob backup = job.safetyBackup;
        if (job.cancelled) {
            backup.cancelled = true; // a running backup stops itself and deletes its partial file
        }
        if (!backup.done) {
            return;
        }
        if (job.cancelled || backup.cancelled) {
            abort(job, "Restore cancelled. Nothing was changed.");
        } else if (!backup.succeeded) {
            abort(job, "Restore stopped because the backup of the current world failed. Nothing was changed.");
        } else {
            job.say("The current world was backed up as \"" + job.safetyName + "\".");
            job.setStage(RestoreJob.Stage.EXTRACTING);
            startExtraction(job);
        }
    }

    private static void startExtraction(RestoreJob job) {
        job.say("Unpacking \"" + job.name + "\"... (the game keeps running)");
        Thread thread = new Thread(() -> extract(job), "EzBackup-Restore-Extract");
        thread.setDaemon(true);
        job.thread = thread;
        thread.start();
    }

    /** Worker thread: unpack and check the archive. Touches only files in the working folder, never the game. */
    private static void extract(RestoreJob job) {
        Path world = job.world;
        try {
            Path incoming = RestoreFiles.incoming(world);
            Files.createDirectories(incoming);
            BackupArchive.Stats stats = BackupArchive.extract(job.archive, job.algorithm, incoming,
                    RestoreFiles::skippedWhenExtracting, job);
            job.setStage(RestoreJob.Stage.VALIDATING);
            RestoreFiles.validateIncoming(incoming);
            RestoreFiles.markPrepared(world);
            job.say("Unpacked " + stats.files() + " files (" + Formatting.size(stats.bytes()) + ").");
        } catch (CancellationException e) {
            job.extractError = "Restore cancelled.";
            quietly(() -> RestoreFiles.discard(world));
        } catch (Throwable t) {
            EzBackup.LOGGER.error("Restore extraction failed", t);
            job.extractError = "The backup could not be unpacked: " + describe(t) + ".";
            quietly(() -> RestoreFiles.discard(world));
        } finally {
            job.extractDone = true;
        }
    }

    private static void evacuationTick(MinecraftServer server, RestoreJob job) throws Exception {
        if (job.cancelled) {
            abort(job, "Restore cancelled. Nothing was changed.");
            return;
        }
        boolean moved = false;
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            if (McWorldSwap.isInVoid(player)) {
                continue;
            }
            UUID id = player.getUUID();
            if (!job.durable.containsKey(id)) {
                McCompat.returnHeldItems(player); // a held / crafting-grid item is not part of the saved player data
                CompoundTag saved = McWorldSwap.savePlayer(player);
                job.durable.put(id, saved);
                try {
                    McWorldSwap.writePlayerFile(RestoreFiles.players(job.world).resolve(id + ".dat"), saved);
                } catch (IOException e) {
                    EzBackup.LOGGER.warn("Could not write the pre-restore copy of {}", player.getGameProfile().getName(), e);
                }
            }
            McWorldSwap.evacuate(server, player);
            McCompat.tellPlayer(player, "Restoring the world from backup \"" + job.name + "\". Please wait a moment...");
            moved = true;
        }
        job.settleTicks = moved ? 0 : job.settleTicks + 1;
        if (job.settleTicks >= BackupLimits.RESTORE_SETTLE_TICKS) {
            swapWorld(server, job);
        }
    }

    /** The critical section: runs in one server tick. See the class comment. */
    private static void swapWorld(MinecraftServer server, RestoreJob job) {
        job.setStage(RestoreJob.Stage.SWAPPING);
        job.swapStartNanos = System.nanoTime();
        Path world = job.world;
        try {
            job.oldLevelData = McWorldSwap.snapshotLevelData(server);
            if (!McCompat.saveWorld(server)) {
                throw new IOException("the world could not be saved");
            }
            McWorldSwap.closeRealLevels(server, job.specs);
            List<String> units = RestoreFiles.planUnits(world, RestoreFiles.incoming(world));
            job.filesSwapped = true; // from the first move on, a failure must be undone, not just abandoned
            RestoreFiles.swap(world, units);

            CompoundTag data = McWorldSwap.readLevelDat(RestoreFiles.incoming(world).resolve("level.dat"));
            if (data == null) {
                throw new IOException("the backup's level.dat could not be read");
            }
            McWorldSwap.applyLevelData(server, data);
            McWorldSwap.rebuildLevels(server, job.specs);
            returnPlayersAfterSwap(server, job, data);
        } catch (Throwable t) {
            EzBackup.LOGGER.error("Swapping the world failed", t);
            if (job.specs.isEmpty() && !job.filesSwapped) {
                abort(job, "The restore failed: " + describe(t) + " Nothing was changed.");
            } else {
                rollbackSwap(job, t);
            }
            return;
        }
        quietly(() -> RestoreFiles.markDone(world));
        EzBackup.LOGGER.info("[restore {}] the world was swapped in {} ms", job.name,
                (System.nanoTime() - job.swapStartNanos) / 1_000_000);
        job.finishWaitTicks = 0;
        job.setStage(RestoreJob.Stage.FINISHING);
    }

    /** Players come back with the data the BACKUP has for them; players it knows nothing about are reset. */
    private static void returnPlayersAfterSwap(MinecraftServer server, RestoreJob job, CompoundTag levelData) {
        CompoundTag hostTag = levelData.contains("Player", 10) ? levelData.getCompound("Player") : null;
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            UUID id = player.getUUID();
            boolean host = server.isSingleplayerOwner(player.getGameProfile());
            CompoundTag tag = null;
            Path file = RestoreFiles.playerDataFile(job.world, id);
            if (Files.isRegularFile(file)) {
                tag = McWorldSwap.readPlayerFile(file);
            }
            if (tag == null && host && hostTag != null && !hostTag.isEmpty()) {
                tag = hostTag.copy(); // a single-player world keeps its owner's data inside level.dat
            }
            if (tag != null) {
                McWorldSwap.returnPlayer(server, player, tag);
                McCompat.tellPlayer(player, "The world was restored from backup \"" + job.name + "\".");
            } else if (host) {
                McWorldSwap.resetPlayer(player);
                McWorldSwap.returnPlayer(server, player, null);
                McCompat.tellPlayer(player, "The world was restored from backup \"" + job.name
                        + "\". You were not in that backup, so you start fresh (items kept in mod-specific slots may remain).");
            } else {
                job.kicked++;
                WIPES.put(id, System.currentTimeMillis() + WIPE_TIMEOUT_MILLIS);
                McCompat.disconnect(player, "The world was restored from backup \"" + job.name
                        + "\" and you were not in it. Rejoin to start fresh.");
            }
        }
    }

    private static void finishingTick(MinecraftServer server, RestoreJob job) {
        if (job.cleanupStarted) {
            return;
        }
        // Disconnected players leave the holding dimension a moment after the kick; closing it under them would break their logout.
        boolean waiting = false;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (McWorldSwap.isInVoid(player)) {
                waiting = true;
            }
        }
        if (waiting && job.finishWaitTicks++ < MAX_FINISH_WAIT_TICKS) {
            return;
        }
        McWorldSwap.removeVoidLevel(server);
        Path world = job.world;
        quietly(() -> RestoreFiles.deleteVoidDimension(world));
        job.say("Restore completed: the world is now backup \"" + job.name + "\".");
        if (job.safetyName != null) {
            job.say("The previous world was backed up as \"" + job.safetyName + "\"; restore that backup to undo this.");
        } else {
            job.say("No backup of the previous world was made, so this restore cannot be undone.");
        }
        if (job.kicked > 0) {
            job.say(job.kicked + " player(s) who were not in the backup were disconnected; they start fresh when they rejoin.");
        }
        job.say("Time: " + Formatting.duration(System.nanoTime() - job.startNanos));
        // Deleting the old world's files can take a while: not on the server thread. The restore counts as running until it is done.
        Thread cleanup = new Thread(() -> {
            try {
                RestoreFiles.discard(world);
            } catch (IOException e) {
                EzBackup.LOGGER.warn("Could not delete the temporary restore files in {}", RestoreFiles.workDir(world), e);
            } finally {
                CURRENT.compareAndSet(job, null);
            }
        }, "EzBackup-Restore-Cleanup");
        job.thread = cleanup;
        job.cleanupStarted = true;
        cleanup.start();
    }

    // ---------------------------------------------------------------------------------------------
    // Failure handling
    // ---------------------------------------------------------------------------------------------

    /** Ends a restore that has not touched the world yet: players go back where they were, temporary files are deleted. */
    private static void abort(RestoreJob job, String message) {
        MinecraftServer server = job.server;
        Path world = job.world;
        try {
            returnPlayersFromVoid(server, job);
        } catch (Throwable t) {
            EzBackup.LOGGER.error("Could not return all players after the restore stopped", t);
        }
        McWorldSwap.removeVoidLevel(server);
        quietly(() -> RestoreFiles.restorePlayerCopies(world));
        quietly(() -> RestoreFiles.deleteVoidDimension(world));
        quietly(() -> RestoreFiles.discard(world));
        CURRENT.compareAndSet(job, null);
        job.fail(message);
    }

    /** Undoes a swap that failed half way: files, world data, dimensions and players all go back to how they were. */
    private static void rollbackSwap(RestoreJob job, Throwable cause) {
        MinecraftServer server = job.server;
        Path world = job.world;
        job.fail("The restore failed: " + describe(cause) + ". Putting the previous world back...");
        try {
            if (!job.specs.isEmpty()) {
                McWorldSwap.closeRealLevels(server, new ArrayList<>());
            }
            RestoreFiles.rollback(world);
            RestoreFiles.restorePlayerCopies(world);
            if (job.oldLevelData != null) {
                McWorldSwap.applyLevelData(server, job.oldLevelData);
            }
            if (!job.specs.isEmpty()) {
                McWorldSwap.rebuildLevels(server, job.specs);
            }
            returnPlayersFromVoid(server, job);
            McWorldSwap.removeVoidLevel(server);
            RestoreFiles.deleteVoidDimension(world);
            RestoreFiles.discard(world);
            CURRENT.compareAndSet(job, null);
            job.fail("The restore was undone: the world is as it was before. Nothing was lost.");
        } catch (Throwable t) {
            EzBackup.LOGGER.error("Undoing the failed restore failed too", t);
            CURRENT.compareAndSet(job, null);
            job.fail("Undoing the failed restore ALSO failed (" + describe(t) + "). The files on disk are still the previous world. "
                    + "Stop the server and start it again (the previous world is restored automatically). "
                    + "Do not play on this server until then.");
        }
    }

    /** Players in the holding dimension go back with the data copied before the restore. */
    private static void returnPlayersFromVoid(MinecraftServer server, RestoreJob job) {
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            if (!McWorldSwap.isInVoid(player)) {
                continue;
            }
            UUID id = player.getUUID();
            McWorldSwap.returnPlayer(server, player, job.durable.get(id));
            McCompat.tellPlayer(player, "The restore was stopped; you are back.");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Events from EzBackup
    // ---------------------------------------------------------------------------------------------

    /** Server about to start (the world's files are not opened yet): finish or undo a restore that was interrupted by a crash or a stop. */
    static void recoverAtStart(MinecraftServer server) {
        try {
            Path world = McCompat.worldFolder(server).toAbsolutePath().normalize();
            switch (RestoreFiles.recover(world)) {
                case ROLLED_BACK -> EzBackup.LOGGER.warn(
                        "A restore was interrupted while swapping the world. The previous world has been put back; run /backup restore again if you still want it.");
                case CLEANED_UP -> EzBackup.LOGGER.info("Removed the leftover files of an earlier restore.");
                case NOTHING -> {
                }
            }
        } catch (IOException e) {
            EzBackup.LOGGER.error("Could not clean up after an interrupted restore", e);
        }
    }

    /** Server stopping: stop a restore that has not swapped yet (bringing players back first), wait for one that is cleaning up. */
    static void shutdown() {
        RestoreJob job = CURRENT.get();
        if (job == null) {
            return;
        }
        job.cancelled = true;
        Thread thread = job.thread;
        if (job.stage == RestoreJob.Stage.FINISHING) {
            join(thread);
            return;
        }
        if (job.stage == RestoreJob.Stage.EXTRACTING || job.stage == RestoreJob.Stage.VALIDATING) {
            join(thread);
        }
        if (job.cancellable()) {
            abort(job, "Restore cancelled because the server is stopping. Nothing was changed.");
        }
    }

    private static void join(Thread thread) {
        if (thread != null) {
            try {
                thread.join(SHUTDOWN_JOIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void tickWipes(MinecraftServer server) {
        if (WIPES.isEmpty()) {
            return;
        }
        Path world = McCompat.worldFolder(server).toAbsolutePath().normalize();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : WIPES.entrySet()) {
            UUID id = entry.getKey();
            if (server.getPlayerList().getPlayer(id) == null) {
                // They are gone; their logout saved the waiting state, so delete every file that holds it.
                quietly(() -> RestoreFiles.deletePlayerFiles(world, id));
                WIPES.remove(id);
            } else if (now > entry.getValue()) {
                EzBackup.LOGGER.warn("Player {} did not disconnect after the restore; their files were not reset", id);
                WIPES.remove(id);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static String sourceKey(CommandSourceStack source) {
        return source.getEntity() != null ? source.getEntity().getUUID().toString() : "console";
    }

    private static int fail(CommandSourceStack source, String message) {
        McCompat.sendFailure(source, message);
        return 0;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private interface IoTask {
        void run() throws IOException;
    }

    private static void quietly(IoTask task) {
        try {
            task.run();
        } catch (IOException e) {
            EzBackup.LOGGER.warn("Restore clean-up step failed", e);
        }
    }
}
