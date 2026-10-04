package com.ezbackup;

import net.minecraft.commands.CommandSourceStack;

import java.util.ArrayList;
import java.util.List;

/**
 * /backup help [page]
 *
 * Page 1 lists every command with a one-line description. Each following page is a short help text for one command,
 * in the same order as the list on page 1. Every page starts with a "Page X of Y" header.
 *
 * Adding a command: add one entry to {@link #commands()}; the page count and page 1 update by themselves.
 */
public final class BackupHelp {

    /** Printed before a command's usage on its own help page. */
    private static final String COMMAND_FORMAT_PREFIX = "Command format: ";
    /** Printed before every detail line (one bullet style for all). */
    private static final String BULLET = "- ";

    /**
     * One command: how it is typed (e.g. "/backup list [page]", no prefix), a one-line summary, and the detail lines of
     * its own help page (without the bullet; {@link #show} adds it).
     */
    private record Entry(String usage, String summary, List<String> details) {
    }

    private static List<Entry> commands() {
        String algorithms = String.join(" | ", Compression.commandNames());
        List<String> algorithmLines = new ArrayList<>();
        for (Compression algorithm : Compression.values()) {
            algorithmLines.add(algorithm.displayName() + ", level "
                    + algorithm.minLevel() + "-" + algorithm.maxLevel());
        }

        BackupConfig.Settings defaults = BackupConfig.settings();

        return List.of(
                /** /backup help 2 */
                new Entry("/" + BackupCommand.ROOT + " " + BackupCommand.CREATE + " <n> [" + algorithms + "] [level]",
                        "Create a backup named <n>.",
                        List.of("Backs up the current world under the name <n> (Only letters, numbers, _ and - are allowed characters for <n>).",
                                "[algorithm] and [level] are optional: " + algorithms + ", and a level for that algorithm. Higher levels take more time to compress and decompress but compact the backup file more.",
                                "Algorithms: " + String.join("; ", algorithmLines) + ".",
                                "Without explicit declarations, your default values are used: [" + defaults.algorithm().describe(defaults.level()) + "]")),
                /** /backup help 3 */
                new Entry("/" + BackupCommand.ROOT + " " + BackupCommand.LIST + " [page]",
                        "List your backups.",
                        List.of("Shows [" + BackupConfig.listPageSize() + "] backups per page.",
                                "/" + BackupCommand.ROOT + " " + BackupCommand.LIST + " 2 shows the second page of backups.",
                                "Algorithm, size and date can be added with the list switch in the EzBackup user interface.")),
                /** /backup help 4 */
                new Entry("/" + BackupCommand.ROOT + " " + BackupCommand.STATUS,
                        "Show the progress of the running backup.",
                        List.of("Shows what the Ezbackup is doing and its progress.",
                                "Otherwise, states no backup is running.")),
                /** /backup help 5 */
                new Entry("/" + BackupCommand.ROOT + " " + BackupCommand.CANCEL,
                        "Cancel the running EzBackup process.",
                        List.of("Stops the running backup/restoration and removes its unfinished files.",
                                "Backups that already finished are never touched.")),
                /** /backup help 6 */
                new Entry("/" + BackupCommand.ROOT + " " + BackupCommand.RESTORE + " <n>",
                        "Replace the world with backup <n>.",
                        List.of("Puts backup <n> back as the live world without stopping the server.",
                                "Type /" + BackupCommand.ROOT + " " + BackupCommand.RESTORE + " <n> " + BackupCommand.CONFIRM + " within "
                                        + BackupLimits.RESTORE_CONFIRM_MILLIS / 1000 + " seconds to confirm.",
                                BackupConfig.backupBeforeRestore()
                                        ? "The current world is backed up first (\"Auto Backup When Restoring\" is on); if that backup fails, nothing is restored."
                                        : "No backup of the current world is made first, so a restore cannot be undone unless you already have a backup of it.",
                                "/" + BackupCommand.ROOT + " " + BackupCommand.STATUS + " shows progress, /" + BackupCommand.ROOT + " " + BackupCommand.CANCEL
                                        + " cancels until the world swap starts.")),
                /** /backup help 7 */
                new Entry("/" + BackupCommand.ROOT + " " + BackupCommand.HELP + " [page]",
                        "Show the help page for different EzBackup commands.",
                        List.of("Page 1 lists every command; each next page explains one command.",
                                "/" + BackupCommand.ROOT + " " + BackupCommand.HELP + " 2 shows help for the first command."))
        );
    }

    private BackupHelp() {
    }

    public static int show(CommandSourceStack source, int page) {
        List<Entry> commands = commands();
        int pageCount = 1 + commands.size();
        if (page > pageCount) {
            McCompat.sendFailure(source, "Page " + page + " does not exist. There are only " + pageCount + " pages.");
            return 0;
        }
        McCompat.sendSuccess(source, "Page " + page + " of " + pageCount);
        if (page == 1) {
            for (Entry entry : commands) {
                McCompat.sendSuccess(source, entry.usage() + " - " + entry.summary());
            }
            McCompat.sendSuccess(source,
                    "- Open the EzBackup user interface by clicking on the button in the lower right corner of the pause menu.");
        } else {
            Entry entry = commands.get(page - 2);
            McCompat.sendSuccess(source, COMMAND_FORMAT_PREFIX + entry.usage());
            for (String line : entry.details()) {
                McCompat.sendSuccess(source, BULLET + line);
            }
        }
        return 1;
    }
}
