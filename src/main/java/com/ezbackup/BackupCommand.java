package com.ezbackup;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.Set;
import java.util.function.ToIntBiFunction;
import java.util.function.ToIntFunction;

/**
 * /backup list [page]
 * /backup status
 * /backup cancel
 * /backup help [page]
 * /backup create &lt;name&gt; [algorithm] [level]      (algorithms come from {@link Compression})
 * /backup restore &lt;name&gt; [confirm]
 *
 * Adding a sub-command: add its name to {@link #SUBCOMMANDS} (so nobody can name a backup after it),
 * write a ...Node() method for it, add that method to {@link #backupNode()}, and implement it in {@link BackupManager}.
 */
public final class BackupCommand {

    static final String ROOT = "backup";
    static final String CREATE = "create";
    static final String LIST = "list";
    static final String STATUS = "status";
    static final String CANCEL = "cancel";
    static final String HELP = "help";
    static final String RESTORE = "restore";
    static final String CONFIRM = "confirm";

    /** Words that cannot be used as a backup name because they are sub-commands. */
    static final Set<String> SUBCOMMANDS = Set.of(CREATE, LIST, STATUS, CANCEL, HELP, RESTORE);

    private static final String ARG_NAME = "name";
    private static final String ARG_ALGORITHM = "algorithm";
    private static final String ARG_LEVEL = "level";
    private static final String ARG_PAGE = "page";

    private static final SuggestionProvider<CommandSourceStack> ALGORITHM_SUGGESTIONS = (ctx, builder) -> {
        Compression.commandNames().forEach(builder::suggest);
        return builder.buildFuture();
    };

    /** Suggests the names of the existing backups (for /backup restore). */
    private static final SuggestionProvider<CommandSourceStack> BACKUP_NAME_SUGGESTIONS = (ctx, builder) -> {
        try {
            java.nio.file.Path dir = BackupConfig.backupDirectory();
            if (dir != null && java.nio.file.Files.isDirectory(dir)) {
                for (BackupListing.Listed listed : BackupListing.find(dir.toAbsolutePath().normalize())) {
                    builder.suggest(BackupNames.logicalName(listed.file().getFileName().toString()));
                }
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // no suggestions is fine
        }
        return builder.buildFuture();
    };

    private BackupCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(backupNode());
    }

    /** /backup and everything under it. */
    private static LiteralArgumentBuilder<CommandSourceStack> backupNode() {
        return Commands.literal(ROOT)
                .requires(source -> McCompat.mayBackup(source, BackupLimits.OTHER_PLAYERS_PERMISSION_LEVEL))
                .executes(ctx -> BackupHelp.show(ctx.getSource(), 1))
                .then(pagedNode(LIST, BackupManager::list))
                .then(statusNode())
                .then(cancelNode())
                .then(pagedNode(HELP, BackupHelp::show))
                .then(restoreNode())
                .then(createNode());
    }

    /** /backup list [page] and /backup help [page]: without a page it is page 1. */
    private static LiteralArgumentBuilder<CommandSourceStack> pagedNode(
            String literal, ToIntBiFunction<CommandSourceStack, Integer> action) {
        return Commands.literal(literal)
                // /backup <literal>  (= page 1)
                .executes(ctx -> action.applyAsInt(ctx.getSource(), 1))
                // /backup <literal> <page>
                .then(Commands.argument(ARG_PAGE, IntegerArgumentType.integer(1))
                        .executes(ctx -> action.applyAsInt(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, ARG_PAGE))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> statusNode() {
        return Commands.literal(STATUS)
                .executes(ctx -> BackupManager.status(ctx.getSource()));
    }

    /** /backup restore <name> and /backup restore <name> confirm */
    private static LiteralArgumentBuilder<CommandSourceStack> restoreNode() {
        return Commands.literal(RESTORE)
                .executes(ctx -> {
                    McCompat.sendFailure(ctx.getSource(), "Usage: /" + ROOT + " " + RESTORE + " <name>");
                    return 0;
                })
                .then(Commands.argument(ARG_NAME, StringArgumentType.word())
                        .suggests(BACKUP_NAME_SUGGESTIONS)
                        .executes(ctx -> RestoreManager.restore(ctx.getSource(), name(ctx), false))
                        .then(Commands.literal(CONFIRM)
                                .executes(ctx -> RestoreManager.restore(ctx.getSource(), name(ctx), true))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> cancelNode() {
        return Commands.literal(CANCEL)
                .executes(ctx -> BackupManager.cancel(ctx.getSource()));
    }

    /** /backup create <name> [algorithm] [level] */
    private static LiteralArgumentBuilder<CommandSourceStack> createNode() {
        return Commands.literal(CREATE)
                .executes(ctx -> {
                    McCompat.sendFailure(ctx.getSource(), "Usage: /" + ROOT + " " + CREATE + " <name> [algorithm] [level]");
                    return 0;
                })
                .then(nameNode());
    }

    /** The <name> of /backup create, then the optional algorithm and level. */
    private static RequiredArgumentBuilder<CommandSourceStack, String> nameNode() {
        return Commands.argument(ARG_NAME, StringArgumentType.word())
                // /backup create <name>
                .executes(ctx -> {
                    BackupConfig.Settings defaults = BackupConfig.settings();
                    return BackupManager.start(ctx.getSource(), name(ctx), defaults.algorithm(), defaults.level());
                })
                .then(algorithmNode());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> algorithmNode() {
        return Commands.argument(ARG_ALGORITHM, StringArgumentType.word())
                .suggests(ALGORITHM_SUGGESTIONS)
                // /backup create <name> <algorithm>
                .executes(ctx -> startWith(ctx, Compression::levelWhenUnspecified))
                // /backup create <name> <algorithm> <level>
                .then(Commands.argument(ARG_LEVEL,
                                IntegerArgumentType.integer(Compression.lowestLevel(), Compression.highestLevel()))
                        .executes(ctx -> startWith(ctx, algorithm -> IntegerArgumentType.getInteger(ctx, ARG_LEVEL))));
    }

    /**
     * Starts a backup with the algorithm typed in the command. {@code level} says which level to use for that
     * algorithm (the level argument, or {@link Compression#levelWhenUnspecified()} when none was typed). Returns 0 after telling the player if the
     * algorithm is unknown.
     */
    private static int startWith(CommandContext<CommandSourceStack> ctx, ToIntFunction<Compression> level) {
        Compression algorithm = algorithm(ctx);
        if (algorithm == null) {
            return 0;
        }
        return BackupManager.start(ctx.getSource(), name(ctx), algorithm, level.applyAsInt(algorithm));
    }

    private static String name(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, ARG_NAME);
    }

    /** Reads the algorithm argument; tells the player and returns null if it is not a known algorithm. */
    private static Compression algorithm(CommandContext<CommandSourceStack> ctx) {
        String text = StringArgumentType.getString(ctx, ARG_ALGORITHM);
        Compression algorithm = Compression.parse(text);
        if (algorithm == null) {
            McCompat.sendFailure(ctx.getSource(),
                    "Unknown algorithm '" + text + "'. Use one of: " + String.join(", ", Compression.commandNames()) + ".");
        }
        return algorithm;
    }
}
