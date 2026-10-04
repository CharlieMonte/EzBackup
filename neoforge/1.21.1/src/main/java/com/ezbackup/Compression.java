package com.ezbackup;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The compression algorithms a backup can use, and the standard file type each one produces:
 *
 * <ul>
 *   <li>ZIP    -> "name.zip"      an ordinary ZIP file (deflate), opens in any unzip tool</li>
 *   <li>ZSTD   -> "name.tar.zst"  an ordinary tar archive compressed with Zstandard</li>
 * </ul>
 *
 * ZIP compresses each file inside the archive itself (see {@link BackupArchive}), so it has no stream
 * encoder/decoder here. Zstd compresses the tar stream as a whole.
 *
 * Each constant owns its behaviour. The public methods ensureAvailable(), createEncoder() and createDecoder() do the
 * common work (wrapping errors, checking the level); a stream algorithm overrides the three hooks loadLibrary(),
 * openEncoder() and openDecoder() in its constant body. ZIP overrides nothing: its hooks do nothing / refuse.
 *
 * To add a stream algorithm: add ONE enum constant with a body that overrides the three hooks (and pass stream = true).
 * Commands, hints and error messages pick it up automatically. Keep the calls into the algorithm's library in a small
 * nested class like {@link ZstdSupport}, so that a missing library only fails inside ensureAvailable() (which turns
 * the failure into a readable message) and never while this enum is being loaded.
 */
public enum Compression {

    ZIP("zip", "ZIP", ".zip", 0, 9, false),
    ZSTD("zstd", "Zstd", ".tar.zst", 1, 22, true) {
        @Override
        void loadLibrary() throws IOException {
            ZstdSupport.load();
        }

        @Override
        OutputStream openEncoder(OutputStream out, int level) throws IOException {
            return ZstdSupport.encoder(out, level);
        }

        @Override
        InputStream openDecoder(InputStream in) throws IOException {
            return ZstdSupport.decoder(in);
        }
    };

    private final String commandName;
    private final String displayName;
    private final String extension;
    private final int minLevel;
    private final int maxLevel;
    private final boolean stream;

    Compression(String commandName, String displayName, String extension, int minLevel, int maxLevel, boolean stream) {
        this.commandName = commandName;
        this.displayName = displayName;
        this.extension = extension;
        this.minLevel = minLevel;
        this.maxLevel = maxLevel;
        this.stream = stream;
    }

    /** What the player types: zip, zstd. */
    public String commandName() {
        return commandName;
    }

    /** Human friendly name: ZIP, Zstd. */
    public String displayName() {
        return displayName;
    }

    /** File name suffix including the dot, e.g. ".zip" or ".tar.zst". */
    public String extension() {
        return extension;
    }

    public int minLevel() {
        return minLevel;
    }

    public int maxLevel() {
        return maxLevel;
    }

    /** The strongest (slowest, smallest) level of this algorithm. */
    public int strongestLevel() {
        return maxLevel;
    }

    /**
     * Level to use when the player names this algorithm without a level: the saved default level if this is the saved
     * default algorithm, otherwise the strongest level. (Not the same as {@code BackupConfig.defaultLevel()}, which is
     * the level the player saved for the default algorithm.)
     */
    public int levelWhenUnspecified() {
        BackupConfig.Settings saved = BackupConfig.settings(); // one snapshot: algorithm and level belong together
        return this == saved.algorithm() ? saved.level() : strongestLevel();
    }

    public boolean validLevel(int level) {
        return level >= minLevel && level <= maxLevel;
    }

    /** Null if {@code level} exists for this algorithm, otherwise the sentence to show the player. */
    public String levelProblem(int level) {
        if (validLevel(level)) {
            return null;
        }
        return displayName + " level must be between " + minLevel + " and " + maxLevel + ".";
    }

    /** "Zstd 22" */
    public String describe(int level) {
        return displayName + " " + level;
    }

    /** All command names in declaration order: [zip, zstd]. The command code builds its hints from this. */
    public static List<String> commandNames() {
        return Arrays.stream(values()).map(Compression::commandName).toList();
    }

    /** Lowest level any algorithm accepts (the command's numeric argument uses this as its minimum). */
    public static int lowestLevel() {
        return Arrays.stream(values()).mapToInt(Compression::minLevel).min().orElse(0);
    }

    /** Highest level any algorithm accepts (the command's numeric argument uses this as its maximum). */
    public static int highestLevel() {
        return Arrays.stream(values()).mapToInt(Compression::maxLevel).max().orElse(0);
    }

    /** Case-insensitive lookup by command name; null if unknown. */
    public static Compression parse(String text) {
        if (text == null) {
            return null;
        }
        String wanted = text.toLowerCase(Locale.ROOT);
        for (Compression c : values()) {
            if (c.commandName.equals(wanted)) {
                return c;
            }
        }
        return null;
    }

    /** Finds the algorithm whose extension the file name ends with (case-insensitive); null if none. */
    public static Compression fromFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (Compression c : values()) {
            if (lower.endsWith(c.extension)) {
                return c;
            }
        }
        return null;
    }

    /** True for algorithms that compress a whole tar stream (Zstd); false for ZIP, which compresses per file. */
    public boolean isStream() {
        return stream;
    }

    /**
     * Makes sure the library (and its native code) for this algorithm can really be used.
     * Throws a readable IOException instead of NoClassDefFoundError / UnsatisfiedLinkError.
     */
    public final void ensureAvailable() throws IOException {
        try {
            loadLibrary();
        } catch (Throwable t) {
            throw new IOException(displayName + " support could not be loaded: " + t, t);
        }
    }

    /**
     * Wraps {@code out} in a compressing stream (stream algorithms only). Closing the result finishes the stream
     * and closes {@code out}.
     */
    public final OutputStream createEncoder(OutputStream out, int level) throws IOException {
        if (!validLevel(level)) {
            throw new IllegalArgumentException(displayName + " level must be " + minLevel + "-" + maxLevel);
        }
        return openEncoder(out, level);
    }

    /** Wraps {@code in} in a decompressing stream (stream algorithms only). */
    public final InputStream createDecoder(InputStream in) throws IOException {
        return openDecoder(in);
    }

    // ---------------------------------------------------------------------------------------------
    // Hooks. A stream algorithm overrides these in its constant body; the defaults are right for ZIP.
    // ---------------------------------------------------------------------------------------------

    /** Loads the algorithm's library and native code so problems show up now. Default: nothing to load. */
    void loadLibrary() throws IOException {
    }

    /** The level was already checked. Default: this algorithm is not a stream algorithm. */
    OutputStream openEncoder(OutputStream out, int level) throws IOException {
        throw new IllegalStateException(displayName + " is compressed per file, not as a stream");
    }

    /** Default: this algorithm is not a stream algorithm. */
    InputStream openDecoder(InputStream in) throws IOException {
        throw new IllegalStateException(displayName + " is compressed per file, not as a stream");
    }

    /**
     * The only code that touches the zstd-jni classes. It is a separate class on purpose: it is loaded the first time
     * one of these methods runs (inside ensureAvailable()'s try block for the first call), not when Compression is
     * loaded, so a missing library cannot break the whole enum.
     */
    private static final class ZstdSupport {

        private ZstdSupport() {
        }

        /** Creating a stream forces zstd-jni to load its native library. */
        static void load() throws IOException {
            new ZstdOutputStream(OutputStream.nullOutputStream(), 1).close();
        }

        static OutputStream encoder(OutputStream out, int level) throws IOException {
            ZstdOutputStream zstd = new ZstdOutputStream(out, level);
            // A checksum in the Zstandard frame lets /backup restore detect a damaged archive while extracting it
            // (the decoder verifies it by itself). Older archives without one still restore, just with weaker checking.
            zstd.setChecksum(true);
            if (BackupLimits.ZSTD_WORKERS > 0) {
                zstd.setWorkers(BackupLimits.ZSTD_WORKERS);
            }
            return zstd;
        }

        static InputStream decoder(InputStream in) throws IOException {
            return new ZstdInputStream(in);
        }
    }
}
