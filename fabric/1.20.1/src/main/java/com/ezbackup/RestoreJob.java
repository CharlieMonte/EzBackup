package com.ezbackup;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * State of the one running restore, the counterpart of {@link BackupJob}. It is also the progress listener of the
 * archive extraction, sends the progress messages (from any thread) and describes itself for /backup status.
 *
 * <p>Threads: the extraction worker writes the volatile progress / result fields; everything else is only touched on
 * the server thread (the state machine in {@link RestoreManager} runs from the server tick).
 */
final class RestoreJob implements BackupArchive.Listener {

    /** The steps of a restore, as shown by /backup status. */
    enum Stage {
        /** Only when "Auto Backup When Restoring" is on: the current world is backed up first. */
        SAFETY_BACKUP("Backing up the current world first"),
        EXTRACTING("Extracting the backup"),
        VALIDATING("Checking the extracted backup"),
        EVACUATING("Moving players to the holding dimension"),
        /** From here on the restore cannot be cancelled. */
        SWAPPING("Swapping the world"),
        FINISHING("Finishing");

        final String label;

        Stage(String label) {
            this.label = label;
        }
    }

    /** Extraction progress is announced at most this often. */
    private static final long PROGRESS_MESSAGE_NANOS = 5_000_000_000L;

    final MinecraftServer server;
    final CommandSourceStack source;
    final String name;
    final Path archive;
    final Compression algorithm;
    final Path world;
    final long startNanos = System.nanoTime();

    volatile Stage stage = Stage.EXTRACTING;
    volatile boolean cancelled;
    volatile boolean extractDone;
    /** Null if extraction and validation succeeded. */
    volatile String extractError;
    volatile long extractedBytes;
    volatile long extractedFiles;
    volatile Thread thread;
    private long lastMessageNanos = System.nanoTime();

    // ---- server thread only ----
    /** Every player's own data before the restore (the rollback source), by UUID. */
    final Map<UUID, CompoundTag> durable = new HashMap<>();
    /** The dimensions as they were before the swap (filled when they are closed). */
    final List<McWorldSwap.LevelSpec> specs = new ArrayList<>();
    CompoundTag oldLevelData;
    int settleTicks;
    int finishWaitTicks;
    boolean cleanupStarted;
    boolean filesSwapped;
    long swapStartNanos;
    int kicked;
    /** The backup of the current world made before the restore; null when that setting was off. */
    BackupJob safetyBackup;
    /** Name of that backup (null when none is made). */
    String safetyName;

    RestoreJob(MinecraftServer server, CommandSourceStack source, String name, Path archive, Compression algorithm,
               Path world) {
        this.server = server;
        this.source = source;
        this.name = name;
        this.archive = archive;
        this.algorithm = algorithm;
        this.world = world;
    }

    void setStage(Stage next) {
        stage = next;
    }

    boolean cancellable() {
        Stage s = stage;
        return s != Stage.SWAPPING && s != Stage.FINISHING;
    }

    List<String> statusLines() {
        Stage current = stage;
        List<String> lines = new ArrayList<>();
        lines.add("Restore: " + name);
        lines.add("Stage: " + current.label);
        if (current == Stage.EXTRACTING) {
            lines.add("Extracted: " + Formatting.size(extractedBytes) + " in " + extractedFiles + " files");
        }
        lines.add("Time so far: " + Formatting.duration(System.nanoTime() - startNanos));
        if (!cancellable()) {
            lines.add("This can no longer be cancelled.");
        }
        return lines;
    }

    @Override
    public void progress(long originalBytes, long archiveBytes, long files) {
        extractedBytes = originalBytes;
        extractedFiles = files;
        long now = System.nanoTime();
        if (stage == Stage.EXTRACTING && now - lastMessageNanos >= PROGRESS_MESSAGE_NANOS) {
            lastMessageNanos = now;
            say("Extracting: " + Formatting.size(originalBytes) + " so far, " + files + " files");
        }
    }

    @Override
    public boolean cancelled() {
        return cancelled;
    }

    /** Sends a chat/console message; safe to call from any thread. */
    void say(String message) {
        send(message, false);
    }

    void fail(String message) {
        send(message, true);
    }

    private void send(String message, boolean error) {
        EzBackup.LOGGER.info("[restore {}] {}", name, message);
        Runnable task = () -> {
            try {
                if (error) {
                    McCompat.sendFailure(source, message);
                } else {
                    McCompat.sendSuccess(source, message);
                }
            } catch (Throwable ignored) {
                // The player may have left; the message is in the log anyway.
            }
        };
        if (server.isSameThread()) {
            task.run();
        } else {
            server.execute(task);
        }
    }
}
