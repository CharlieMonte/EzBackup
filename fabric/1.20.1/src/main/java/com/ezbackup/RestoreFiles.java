package com.ezbackup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Everything /backup restore does to FILES, with no Minecraft dependencies (so it can be unit-tested and reused by a
 * port): the layout of the working folder, which parts of a world are swapped, the swap itself, the rollback and the
 * crash-recovery journal.
 *
 * <h3>Working folder</h3>
 * Everything lives in {@code <world>/.ez-restore/}, INSIDE the world folder, so every move below is a cheap rename on
 * one drive (a move between two drives would have to copy the whole world while the game is paused):
 * <pre>
 *   incoming/       the backup, extracted (worker thread, game still running)
 *   displaced/      the live files that were moved out of the way by the swap (kept until the restore succeeded)
 *   players/        each online player's own data as it was before the restore (the rollback source)
 *   journal.properties   what the swap is doing, so an interrupted restore can be undone at the next start
 * </pre>
 *
 * <h3>Units</h3>
 * The swap works on "units": every top-level entry of the world folder, except that {@code dimensions/} is opened up
 * one level further (namespace / dimension) so other mods' dimensions are swapped one by one and this mod's holding
 * dimension can be left alone. A unit that exists live but not in the backup is moved away (so the world really goes
 * back to the backup); a unit only in the backup is moved in.
 *
 * Never swapped: {@code session.lock}, {@code level.dat} / {@code level.dat_old} (the server applies the backup's
 * level.dat as DATA instead, see McWorldSwap), {@code datapacks} and {@code serverconfig} (settings of this
 * installation, not world state), the working folder and the holding dimension.
 */
final class RestoreFiles {

    static final String WORK_DIR = ".ez-restore";
    static final String INCOMING = "incoming";
    static final String DISPLACED = "displaced";
    static final String PLAYERS = "players";
    static final String JOURNAL = "journal.properties";

    /** The holding dimension's folder, relative to the world folder (namespace "ezbackup", dimension "restore_void"). */
    static final String VOID_DIMENSION_PATH = "dimensions/ezbackup/restore_void";

    /** Units (relative paths, {@code /} separators) the swap never touches. */
    private static final Set<String> NEVER_SWAPPED = Set.of(
            "session.lock", "level.dat", "level.dat_old", "datapacks", "serverconfig", WORK_DIR, VOID_DIMENSION_PATH);

    private static final int MOVE_ATTEMPTS = 5;
    private static final long MOVE_RETRY_MILLIS = 250;

    /** How far the swap got; stored in the journal. */
    enum State {
        /** Extracted and checked; nothing in the live world has been moved. */
        PREPARED,
        /** Moves are in progress (or were interrupted). */
        SWAPPING,
        /** Every move finished; the restore is being completed. */
        SWAPPED,
        /** The restore is complete; only the working folder is left to delete. */
        DONE
    }

    /** What {@link #recover} found and did. */
    enum Recovery { NOTHING, CLEANED_UP, ROLLED_BACK }

    private RestoreFiles() {
    }

    // ---------------------------------------------------------------------------------------------
    // Layout and exclusions
    // ---------------------------------------------------------------------------------------------

    static Path workDir(Path world) {
        return world.resolve(WORK_DIR);
    }

    static Path incoming(Path world) {
        return workDir(world).resolve(INCOMING);
    }

    static Path displaced(Path world) {
        return workDir(world).resolve(DISPLACED);
    }

    static Path players(Path world) {
        return workDir(world).resolve(PLAYERS);
    }

    /** True for the folders {@link Snapshot} must not copy into a backup: the working folder and the holding dimension. */
    static boolean excludedFromBackup(Path worldRoot, Path dir) {
        return dir.equals(worldRoot.resolve(WORK_DIR)) || dir.equals(worldRoot.resolve(VOID_DIMENSION_PATH));
    }

    /**
     * True for archive paths that are not extracted at all: the never-swapped units except {@code level.dat}, which
     * is extracted because its contents are applied as data. {@code relative} is relative to the world folder.
     */
    static boolean skippedWhenExtracting(String relative) {
        for (String unit : NEVER_SWAPPED) {
            if (unit.equals("level.dat")) {
                continue;
            }
            if (relative.equals(unit) || relative.startsWith(unit + "/")) {
                return true;
            }
        }
        // the parent of the holding dimension is harmless, but a stray empty "dimensions/ezbackup" is not worth creating
        return relative.equals("dimensions/ezbackup");
    }

    private static boolean neverSwapped(String unit) {
        return NEVER_SWAPPED.contains(unit);
    }

    // ---------------------------------------------------------------------------------------------
    // Units
    // ---------------------------------------------------------------------------------------------

    /** The swap units that exist under {@code root} (see the class comment), sorted. */
    static List<String> units(Path root) throws IOException {
        Set<String> found = new TreeSet<>();
        if (!Files.isDirectory(root)) {
            return new ArrayList<>(found);
        }
        try (DirectoryStream<Path> top = Files.newDirectoryStream(root)) {
            for (Path entry : top) {
                String name = entry.getFileName().toString();
                if (name.equals("dimensions") && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    try (DirectoryStream<Path> namespaces = Files.newDirectoryStream(entry)) {
                        for (Path ns : namespaces) {
                            if (!Files.isDirectory(ns, LinkOption.NOFOLLOW_LINKS)) {
                                found.add(name + "/" + ns.getFileName());
                                continue;
                            }
                            try (DirectoryStream<Path> dims = Files.newDirectoryStream(ns)) {
                                for (Path dim : dims) {
                                    found.add(name + "/" + ns.getFileName() + "/" + dim.getFileName());
                                }
                            }
                        }
                    }
                } else {
                    found.add(name);
                }
            }
        }
        return new ArrayList<>(found);
    }

    /** Every unit the swap will handle: those in the live world plus those in the backup, minus the never-swapped ones. */
    static List<String> planUnits(Path world, Path incoming) throws IOException {
        Set<String> all = new TreeSet<>(units(world));
        all.addAll(units(incoming));
        all.removeIf(RestoreFiles::neverSwapped);
        return new ArrayList<>(all);
    }

    /** Throws if {@code incoming} does not look like an extracted Minecraft world. */
    static void validateIncoming(Path incoming) throws IOException {
        if (!Files.isRegularFile(incoming.resolve("level.dat"))) {
            throw new IOException("The backup has no level.dat, so it is not a Minecraft world.");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Players
    // ---------------------------------------------------------------------------------------------

    static Path playerDataFile(Path root, UUID id) {
        return root.resolve("playerdata").resolve(id + ".dat");
    }

    /** Deletes a player's data, advancement and statistics files under {@code root} (the live world). Missing files are fine. */
    static void deletePlayerFiles(Path root, UUID id) throws IOException {
        Files.deleteIfExists(playerDataFile(root, id));
        Files.deleteIfExists(root.resolve("advancements").resolve(id + ".json"));
        Files.deleteIfExists(root.resolve("stats").resolve(id + ".json"));
    }

    // ---------------------------------------------------------------------------------------------
    // The swap, rollback, recovery
    // ---------------------------------------------------------------------------------------------

    /**
     * Moves the live units out into {@code displaced/} and the backup's units in. The server must have closed every
     * dimension first (open region files cannot be moved on Windows). The journal is written BEFORE the first move
     * and updated at the end, so an interruption at any point can be undone by {@link #rollback}.
     */
    static void swap(Path world, List<String> units) throws IOException {
        Path incoming = incoming(world);
        Path displaced = displaced(world);
        Files.createDirectories(displaced);

        List<String> flags = new ArrayList<>();
        for (String unit : units) {
            boolean live = Files.exists(world.resolve(unit), LinkOption.NOFOLLOW_LINKS);
            boolean inc = Files.exists(incoming.resolve(unit), LinkOption.NOFOLLOW_LINKS);
            flags.add((live ? "L" : "") + (inc ? "I" : "") + (!live && !inc ? "-" : ""));
        }
        writeJournal(world, State.SWAPPING, units, flags);

        for (int i = 0; i < units.size(); i++) {
            String unit = units.get(i);
            String f = flags.get(i);
            Path live = world.resolve(unit);
            if (f.contains("L")) {
                Path to = displaced.resolve(unit);
                Files.createDirectories(to.getParent());
                move(live, to);
            }
            if (f.contains("I")) {
                Files.createDirectories(live.getParent());
                move(incoming.resolve(unit), live);
            }
        }
        writeJournal(world, State.SWAPPED, units, flags);
    }

    /**
     * Undoes a swap that was interrupted or failed: puts every displaced unit back and removes the units that were
     * moved in from the backup. Safe to run on a swap that never started and to run twice.
     */
    static void rollback(Path world) throws IOException {
        Journal journal = readJournal(world);
        if (journal == null) {
            return;
        }
        Path displaced = displaced(world);
        for (int i = 0; i < journal.units.size(); i++) {
            String unit = journal.units.get(i);
            String f = journal.flags.get(i);
            Path live = world.resolve(unit);
            if (f.contains("L")) {
                Path back = displaced.resolve(unit);
                if (Files.exists(back, LinkOption.NOFOLLOW_LINKS)) {
                    Snapshot.deleteTree(live); // whatever was moved in from the backup (or nothing)
                    Files.createDirectories(live.getParent());
                    move(back, live);
                }
                // else: this unit was never moved away, the live one is still the original
            } else if (f.contains("I")) {
                // there was no live unit; anything there now came from the backup
                Snapshot.deleteTree(live);
            }
        }
    }

    /**
     * Marks the restore complete in the journal. From here on an interruption only leaves a working folder to delete
     * (instead of being rolled back at the next start). Quick; call it before the slow {@link #discard}.
     */
    static void markDone(Path world) throws IOException {
        Journal journal = readJournal(world);
        if (journal != null) {
            writeJournal(world, State.DONE, journal.units, journal.flags);
        }
    }

    /**
     * Copies each saved pre-restore player file in {@code players/} back over the live {@code playerdata/} file. Used
     * when a restore is undone or interrupted: a player who disconnected while waiting in the holding dimension had
     * that waiting state saved, and this puts their real data back.
     */
    static void restorePlayerCopies(Path world) throws IOException {
        Path copies = players(world);
        if (!Files.isDirectory(copies)) {
            return;
        }
        Path live = world.resolve("playerdata");
        Files.createDirectories(live);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(copies, "*.dat")) {
            for (Path copy : stream) {
                Files.copy(copy, live.resolve(copy.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Deletes the working folder without any file having been swapped (cancelled or failed before the swap). */
    static void discard(Path world) throws IOException {
        Snapshot.deleteTree(workDir(world));
    }

    /**
     * Run at server start, BEFORE the world's dimensions are opened: finishes cleaning up after a restore that was
     * interrupted. A swap that did not complete is rolled back (the world then is exactly as it was before the
     * restore; the player can simply run the restore again); a completed one only needs its working folder deleted.
     * Also removes a leftover holding dimension folder.
     */
    static Recovery recover(Path world) throws IOException {
        deleteVoidDimension(world);
        Path work = workDir(world);
        if (!Files.exists(work, LinkOption.NOFOLLOW_LINKS)) {
            return Recovery.NOTHING;
        }
        Journal journal = readJournal(world);
        Recovery result = Recovery.CLEANED_UP;
        if (journal != null && (journal.state == State.SWAPPING || journal.state == State.SWAPPED)) {
            rollback(world);
            result = Recovery.ROLLED_BACK;
        }
        if (journal == null || journal.state != State.DONE) {
            restorePlayerCopies(world);
        }
        Snapshot.deleteTree(work);
        return result;
    }

    /** Deletes the holding dimension's folder and its (then empty) parent. */
    static void deleteVoidDimension(Path world) throws IOException {
        Path dir = world.resolve(VOID_DIMENSION_PATH);
        Snapshot.deleteTree(dir);
        Path parent = dir.getParent();
        if (parent != null && Files.isDirectory(parent)) {
            try (DirectoryStream<Path> rest = Files.newDirectoryStream(parent)) {
                if (!rest.iterator().hasNext()) {
                    Files.delete(parent);
                }
            }
        }
    }

    /** {@code Files.move} with a few retries: virus scanners and indexers on Windows briefly lock freshly written files. */
    private static void move(Path from, Path to) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MOVE_ATTEMPTS; attempt++) {
            try {
                Files.move(from, to);
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(MOVE_RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IOException("Could not move " + from + " to " + to + ": " + (last == null ? "interrupted" : last.getMessage()), last);
    }

    // ---------------------------------------------------------------------------------------------
    // Journal
    // ---------------------------------------------------------------------------------------------

    private record Journal(State state, List<String> units, List<String> flags) {
    }

    private static void writeJournal(Path world, State state, List<String> units, List<String> flags) throws IOException {
        Properties p = new Properties();
        p.setProperty("state", state.name());
        p.setProperty("count", Integer.toString(units.size()));
        for (int i = 0; i < units.size(); i++) {
            p.setProperty("unit." + i, flags.get(i) + ":" + units.get(i));
        }
        Path work = workDir(world);
        Files.createDirectories(work);
        Path tmp = work.resolve(JOURNAL + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "EzBackup restore journal - do not edit");
        }
        Files.move(tmp, work.resolve(JOURNAL), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Writes the "nothing moved yet" journal, so a crash after extraction is recognised (and cleaned up) at next start. */
    static void markPrepared(Path world) throws IOException {
        writeJournal(world, State.PREPARED, Collections.emptyList(), Collections.emptyList());
    }

    private static Journal readJournal(Path world) throws IOException {
        Path file = workDir(world).resolve(JOURNAL);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        State state;
        try {
            state = State.valueOf(p.getProperty("state", ""));
        } catch (IllegalArgumentException e) {
            throw new IOException("The restore journal " + file + " is damaged.");
        }
        int count;
        try {
            count = Integer.parseInt(p.getProperty("count", "0"));
        } catch (NumberFormatException e) {
            throw new IOException("The restore journal " + file + " is damaged.");
        }
        List<String> units = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String line = p.getProperty("unit." + i);
            int colon = line == null ? -1 : line.indexOf(':');
            if (colon < 0) {
                throw new IOException("The restore journal " + file + " is damaged.");
            }
            flags.add(line.substring(0, colon));
            units.add(line.substring(colon + 1));
        }
        return new Journal(state, units, flags);
    }
}
