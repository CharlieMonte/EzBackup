package com.ezbackup;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * State of the one running backup. It is also the progress listener for the archive writer / verifier, sends the
 * progress messages to the player (from any thread) and knows how to describe itself for {@code /backup status}.
 *
 * <p>Created and started by {@link BackupManager}. The volatile fields are shared between the worker thread and the
 * server thread: the worker writes the progress fields and the server thread reads them (status), while the server
 * thread sets {@code cancelled} (cancel, shutdown) and the worker reads it.
 */
final class BackupJob implements BackupArchive.Listener {

    /** The steps of a backup, as shown by /backup status. */
    enum Stage {
        STARTING("Starting"),
        SAVING("Saving world"),
        COPYING("Copying world"),
        COMPRESSING("Compressing"),
        VERIFYING("Verifying"),
        FINISHING("Finishing");

        final String label;

        Stage(String label) {
            this.label = label;
        }
    }

    /** Compression progress is announced in this many equal steps (10 steps = every 10 %). */
    private static final int PROGRESS_STEPS = 10;
    private static final int PERCENT_PER_STEP = 100 / PROGRESS_STEPS;

    final MinecraftServer server;
    final CommandSourceStack source;
    final String name;
    final Compression algorithm;
    final int level;
    final long startNanos = System.nanoTime();

    volatile Stage stage = Stage.STARTING;
    volatile long stageStartNanos = System.nanoTime();
    volatile long totalBytes;
    volatile long originalDone;
    volatile long archiveBytes;
    volatile long filesDone;
    volatile boolean cancelled;
    /** Set by the worker once the backup has ended, whatever the outcome (and after the job has been cleared). */
    volatile boolean done;
    /** True only if the backup was written, verified and moved to its final name. Read it after {@link #done}. */
    volatile boolean succeeded;
    volatile Thread thread;
    private int lastReportedStep;

    BackupJob(MinecraftServer server, CommandSourceStack source, String name, Compression algorithm, int level) {
        this.server = server;
        this.source = source;
        this.name = name;
        this.algorithm = algorithm;
        this.level = level;
    }

    void setStage(Stage newStage) {
        stage = newStage;
        stageStartNanos = System.nanoTime();
        originalDone = 0;
        lastReportedStep = 0;
    }

    /** The lines /backup status prints for this job (name, algorithm, stage and, while working, progress / speed / ETA). */
    List<String> statusLines() {
        Stage currentStage = stage;
        long total = totalBytes;
        long done = originalDone;
        int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
        double seconds = Math.max(0.001, (System.nanoTime() - stageStartNanos) / 1e9);
        double bytesPerSecond = done / seconds;

        List<String> lines = new ArrayList<>();
        lines.add("Backup: " + name);
        lines.add("Algorithm: " + algorithm.describe(level));
        lines.add("Stage: " + currentStage.label);
        if (currentStage == Stage.COMPRESSING || currentStage == Stage.VERIFYING) {
            lines.add("Progress: " + percent + "%");
            lines.add("Original: " + Formatting.size(done) + " of " + Formatting.size(total));
            if (currentStage == Stage.COMPRESSING) {
                lines.add("Compressed: " + Formatting.size(archiveBytes));
            }
            lines.add("Files: " + filesDone);
            if (done > 0 && total > done) {
                lines.add("Speed: " + Formatting.size((long) bytesPerSecond) + "/s");
                lines.add("ETA: " + Formatting.duration((long) ((total - done) / bytesPerSecond * 1e9)));
            }
        }
        return lines;
    }

    @Override
    public void progress(long originalBytes, long archiveBytesSoFar, long files) {
        originalDone = originalBytes;
        if (archiveBytesSoFar >= 0) {
            archiveBytes = archiveBytesSoFar;
        }
        filesDone = files;
        if (stage == Stage.COMPRESSING && totalBytes > 0) {
            int step = (int) Math.min(PROGRESS_STEPS, originalBytes * PROGRESS_STEPS / totalBytes);
            if (step > lastReportedStep && step < PROGRESS_STEPS) {
                lastReportedStep = step;
                say("Compressing: " + (step * PERCENT_PER_STEP) + "% (" + Formatting.size(originalBytes) + " of "
                        + Formatting.size(totalBytes) + " read, " + Formatting.size(archiveBytes) + " written)");
            }
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
        EzBackup.LOGGER.info("[backup {}] {}", name, message);
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
        // Run at once when already on the server thread (a command can start a backup and report from the same tick,
        // and queueing would put the message after later output); otherwise hand it over, the worker must not touch the game.
        if (server.isSameThread()) {
            task.run();
        } else {
            server.execute(task);
        }
    }
}
