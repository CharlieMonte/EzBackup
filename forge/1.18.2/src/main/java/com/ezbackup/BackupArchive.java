package com.ezbackup;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Writes and verifies backups as standard archives that any normal tool can open. No Minecraft dependencies.
 *
 * <pre>
 *   ZIP     name.zip       a normal ZIP file, every file deflated on its own
 *   ZSTD    name.tar.zst   a normal tar archive (see {@link Tar}) compressed as one Zstandard stream
 * </pre>
 *
 * Whatever the format, the archive holds one top-level folder named like the world folder, with the world
 * inside it, byte for byte exactly as it was on disk. Nothing is parsed, split or re-encoded.
 */
public final class BackupArchive {

    private static final int BUFFER = 1 << 20;
    private static final long PROGRESS_STEP = 4L << 20;

    private BackupArchive() {
    }

    /** Counts of what an archive / snapshot contains. {@code dirs} includes the root folder. */
    public record Stats(long files, long dirs, long bytes) {
    }

    /**
     * What /backup list shows. The archive itself carries no metadata, so this comes from the file;
     * {@code createdMillis} is the file's last-modified time (a finished backup is never written again).
     */
    public record Info(Compression algorithm, long createdMillis, long archiveSize) {
    }

    /** Receives progress and can cancel. Called from the worker thread. */
    public interface Listener {
        /**
         * @param originalBytes file bytes processed so far
         * @param archiveBytes  bytes written to the archive so far, or -1 when not applicable
         * @param files         files processed so far
         */
        void progress(long originalBytes, long archiveBytes, long files);

        boolean cancelled();
    }

    /** Where the writer puts entries. One implementation per archive format. */
    interface Sink {
        void directory(String path, long modifiedMillis) throws IOException;

        /** Starts a file entry and returns the stream its content must be written to. */
        OutputStream beginFile(String path, long modifiedMillis, long size) throws IOException;

        void endFile() throws IOException;

        /** Writes the trailer. Called once, before {@link #close()}. */
        void finish() throws IOException;

        void close() throws IOException;
    }

    /** One entry read back from an archive. */
    private record Entry(String path, boolean directory, InputStream content) {
    }

    /** Reads entries back from an archive, in order. */
    private interface Source {
        /** The next entry, or null after the last one (having checked the archive really ends there). */
        Entry next() throws IOException;

        /** Final structural checks once {@link #next()} returned null. */
        void finish(long entryCount) throws IOException;

        void close() throws IOException;
    }

    // ---------------------------------------------------------------------------------------------
    // Info (used by /backup list)
    // ---------------------------------------------------------------------------------------------

    public static Info readInfo(Path file) throws IOException {
        Compression algorithm = Compression.fromFileName(file.getFileName().toString());
        if (algorithm == null) {
            throw new IOException("Unknown backup file type");
        }
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        return new Info(algorithm, attrs.lastModifiedTime().toMillis(), attrs.size());
    }

    // ---------------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------------

    /**
     * Writes {@code target} from the contents of {@code snapshotRoot}.
     * {@code target} must not exist (it is opened with CREATE_NEW, so nothing is ever overwritten).
     * On any failure the (partial) target is left for the caller to delete.
     */
    public static Stats write(Path snapshotRoot, String worldFolder, Path target, String name,
                              Compression algorithm, int level, Listener listener) throws IOException {
        Stats result;
        try (OutputStream file = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            CountingOutputStream counting = new CountingOutputStream(new BufferedOutputStream(file, BUFFER));
            Sink sink = algorithm.isStream()
                    ? new Tar.Sink(new BufferedOutputStream(algorithm.createEncoder(counting, level), BUFFER))
                    : new ZipSink(counting, level, generator() + " - " + name);
            try {
                Writer writer = new Writer(sink, counting, listener);
                writer.directory(worldFolder, Files.getLastModifiedTime(snapshotRoot).toMillis());
                writer.walk(snapshotRoot, worldFolder);
                result = new Stats(writer.files, writer.dirs, writer.bytes);
                sink.finish();
            } finally {
                // Closing also finishes the compressed stream and flushes everything to the file.
                sink.close();
            }
        }
        if (listener != null) {
            listener.progress(result.bytes(), Files.size(target), result.files());
        }
        return result;
    }

    private static final class Writer {
        private final Sink sink;
        private final CountingOutputStream counting;
        private final Listener listener;
        private final byte[] buffer = new byte[BUFFER];
        long files;
        long dirs;
        long bytes;
        private long lastReported;

        Writer(Sink sink, CountingOutputStream counting, Listener listener) {
            this.sink = sink;
            this.counting = counting;
            this.listener = listener;
        }

        void directory(String path, long modified) throws IOException {
            sink.directory(path, modified);
            dirs++;
        }

        void walk(Path dir, String relative) throws IOException {
            List<Path> children;
            try (Stream<Path> stream = Files.list(dir)) {
                children = stream.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            }
            for (Path child : children) {
                checkCancelled();
                BasicFileAttributes attrs =
                        Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                String childRelative = relative + "/" + child.getFileName();
                if (attrs.isDirectory()) {
                    directory(childRelative, attrs.lastModifiedTime().toMillis());
                    walk(child, childRelative);
                } else if (attrs.isRegularFile()) {
                    file(child, childRelative, attrs);
                } else {
                    throw new IOException("Unsupported file type in snapshot: " + child);
                }
            }
        }

        void file(Path path, String relative, BasicFileAttributes attrs) throws IOException {
            long size = attrs.size();
            OutputStream out = sink.beginFile(relative, attrs.lastModifiedTime().toMillis(), size);
            long remaining = size;
            try (InputStream in = Files.newInputStream(path)) {
                while (remaining > 0) {
                    int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (n < 0) {
                        throw new IOException("File got shorter while it was being archived: " + path);
                    }
                    out.write(buffer, 0, n);
                    remaining -= n;
                    bytes += n;
                    if (bytes - lastReported >= PROGRESS_STEP) {
                        report();
                    }
                    checkCancelled();
                }
                if (in.read() != -1) {
                    throw new IOException("File got longer while it was being archived: " + path);
                }
            }
            sink.endFile();
            files++;
            report();
        }

        private void report() {
            lastReported = bytes;
            if (listener != null) {
                listener.progress(bytes, counting.count(), files);
            }
        }

        private void checkCancelled() {
            if (listener != null && listener.cancelled()) {
                throw new CancellationException("Backup cancelled");
            }
        }
    }

    /** ZIP: each entry is deflated individually. */
    private static final class ZipSink implements Sink {
        private final ZipOutputStream zip;

        ZipSink(OutputStream out, int level, String comment) {
            zip = new ZipOutputStream(out);
            zip.setLevel(level);
            zip.setComment(comment);
        }

        @Override
        public void directory(String path, long modifiedMillis) throws IOException {
            ZipEntry entry = new ZipEntry(path + "/");
            entry.setLastModifiedTime(FileTime.fromMillis(modifiedMillis));
            zip.putNextEntry(entry);
            zip.closeEntry();
        }

        @Override
        public OutputStream beginFile(String path, long modifiedMillis, long size) throws IOException {
            ZipEntry entry = new ZipEntry(path);
            entry.setLastModifiedTime(FileTime.fromMillis(modifiedMillis));
            zip.putNextEntry(entry);
            return zip;
        }

        @Override
        public void endFile() throws IOException {
            zip.closeEntry();
        }

        @Override
        public void finish() throws IOException {
            zip.finish();
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Verifying
    // ---------------------------------------------------------------------------------------------

    /**
     * Reads the whole archive back (decompressing it) and compares it with the snapshot it was made from:
     * every entry must exist in the snapshot, every file must have the same size and the same SHA-256 as the
     * snapshot file, no entry may appear twice, the archive must end properly (not truncated), and the totals
     * must equal {@code expected}. Throws IOException describing the first problem found.
     */
    public static Stats verify(Path archive, Compression algorithm, Path snapshotRoot, String worldFolder,
                               Stats expected, Listener listener) throws IOException {
        long files = 0;
        long dirs = 0;
        long bytes = 0;
        long lastReported = 0;
        byte[] buffer = new byte[BUFFER];
        MessageDigest archiveDigest = sha256();
        MessageDigest diskDigest = sha256();
        Set<String> seen = new HashSet<>();

        Source source = algorithm.isStream() ? openTar(archive, algorithm) : openZip(archive);
        try {
            Entry entry;
            while ((entry = source.next()) != null) {
                if (listener != null && listener.cancelled()) {
                    throw new CancellationException("Backup cancelled");
                }
                String path = entry.path();
                if (!seen.add(path)) {
                    throw new IOException("Path appears twice in the archive: " + path);
                }
                Path onDisk = snapshotPath(snapshotRoot, worldFolder, path);
                if (entry.directory()) {
                    if (!Files.isDirectory(onDisk, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Directory in archive is missing from the snapshot: " + path);
                    }
                    dirs++;
                    continue;
                }
                if (!Files.isRegularFile(onDisk, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("File in archive is missing from the snapshot: " + path);
                }

                archiveDigest.reset();
                long size = 0;
                InputStream in = entry.content();
                int n;
                while ((n = in.read(buffer)) > 0) {
                    archiveDigest.update(buffer, 0, n);
                    size += n;
                    bytes += n;
                    if (listener != null && bytes - lastReported >= PROGRESS_STEP) {
                        lastReported = bytes;
                        listener.progress(bytes, -1, files);
                    }
                }
                if (n == 0) {
                    throw new IOException("Unreadable data in " + path);
                }

                if (Files.size(onDisk) != size) {
                    throw new IOException("Size mismatch for " + path + ": archive has " + size
                            + " bytes, snapshot has " + Files.size(onDisk));
                }
                diskDigest.reset();
                try (InputStream disk = Files.newInputStream(onDisk)) {
                    while ((n = disk.read(buffer)) > 0) {
                        diskDigest.update(buffer, 0, n);
                    }
                }
                if (!MessageDigest.isEqual(archiveDigest.digest(), diskDigest.digest())) {
                    throw new IOException("Checksum mismatch in " + path);
                }
                files++;
            }
            source.finish(files + dirs);
        } catch (ZipException e) {
            throw new IOException("Corrupt ZIP archive: " + e.getMessage(), e);
        } finally {
            source.close();
        }

        Stats counted = new Stats(files, dirs, bytes);
        if (expected != null && !expected.equals(counted)) {
            throw new IOException("Archive contents " + counted + " do not match the snapshot " + expected);
        }
        if (listener != null) {
            listener.progress(bytes, -1, files);
        }
        return counted;
    }

    // ---------------------------------------------------------------------------------------------
    // Extracting (used by /backup restore)
    // ---------------------------------------------------------------------------------------------

    /**
     * Unpacks {@code archive} into {@code targetRoot}, which must be an existing empty directory. The archive's one
     * top-level folder (named after the world folder when the backup was made, which may differ from the world's
     * current name) is dropped, so {@code targetRoot} ends up holding the world's own files ({@code level.dat},
     * {@code region}, ...).
     *
     * <p>Safety: every path is checked like in {@link #snapshotPath} (no {@code ..}, no empty parts, no backslash,
     * colon or NUL, nothing outside the single top-level folder), nothing may appear twice, and nothing is ever
     * overwritten (files are opened with CREATE_NEW). Entries for which {@code skip} (given the path relative to the
     * world folder, with {@code /} separators) returns true are read past but not written. Damage in the archive
     * (bad ZIP CRC, bad tar header checksum, Zstandard frame checksum, truncation) ends in an IOException; the
     * caller deletes the partly filled {@code targetRoot}.
     *
     * @return what was written; {@code dirs} does not count the dropped top-level folder
     */
    public static Stats extract(Path archive, Compression algorithm, Path targetRoot, Predicate<String> skip,
                                Listener listener) throws IOException {
        long files = 0;
        long dirs = 0;
        long bytes = 0;
        long entries = 0;
        long lastReported = 0;
        byte[] buffer = new byte[BUFFER];
        Set<String> seen = new HashSet<>();
        String top = null;

        Source source = algorithm.isStream() ? openTar(archive, algorithm) : openZip(archive);
        try {
            Entry entry;
            while ((entry = source.next()) != null) {
                if (listener != null && listener.cancelled()) {
                    throw new CancellationException("Restore cancelled");
                }
                entries++;
                String path = entry.path();
                if (!seen.add(path)) {
                    throw new IOException("Path appears twice in the archive: " + path);
                }
                String[] parts = path.split("/", -1);
                if (top == null) {
                    top = parts[0];
                    if (top.isEmpty() || !entry.directory() || parts.length != 1) {
                        throw new IOException("The archive does not start with the world folder, so it is not a backup made by EzBackup.");
                    }
                    continue; // the world folder itself
                }
                if (!parts[0].equals(top) || parts.length < 2) {
                    throw new IOException("Archive path outside the world folder: " + path);
                }
                Path target = targetRoot;
                StringBuilder relative = new StringBuilder();
                for (int i = 1; i < parts.length; i++) {
                    String part = parts[i];
                    if (part.isEmpty() || part.equals(".") || part.equals("..")
                            || part.indexOf('\\') >= 0 || part.indexOf(':') >= 0 || part.indexOf('\0') >= 0) {
                        throw new IOException("Unsafe path in archive: " + path);
                    }
                    target = target.resolve(part);
                    relative.append(i > 1 ? "/" : "").append(part);
                }
                boolean skipped = skip != null && skip.test(relative.toString());

                if (entry.directory()) {
                    if (!skipped) {
                        Files.createDirectories(target);
                        dirs++;
                    }
                    continue;
                }
                if (skipped) {
                    continue; // the reader moves past the unread content by itself
                }
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    InputStream in = entry.content();
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                        bytes += n;
                        if (listener != null && bytes - lastReported >= PROGRESS_STEP) {
                            lastReported = bytes;
                            listener.progress(bytes, -1, files);
                            if (listener.cancelled()) {
                                throw new CancellationException("Restore cancelled");
                            }
                        }
                    }
                    if (n == 0) {
                        throw new IOException("Unreadable data in " + path);
                    }
                }
                files++;
            }
            if (top == null) {
                throw new IOException("The archive is empty.");
            }
            source.finish(entries);
        } catch (ZipException e) {
            throw new IOException("Corrupt ZIP archive: " + e.getMessage(), e);
        } finally {
            source.close();
        }
        if (listener != null) {
            listener.progress(bytes, -1, files);
        }
        return new Stats(files, dirs, bytes);
    }

    private static Source openZip(Path archive) throws IOException {
        // ZipInputStream checks each entry's CRC-32 while reading it.
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive), BUFFER));
        return new Source() {
            @Override
            public Entry next() throws IOException {
                ZipEntry entry = zip.getNextEntry();
                if (entry == null) {
                    return null;
                }
                String name = entry.getName();
                boolean dir = entry.isDirectory();
                return new Entry(dir ? name.substring(0, name.length() - 1) : name, dir, zip);
            }

            @Override
            public void finish(long entryCount) throws IOException {
                // Reading the entries one after another never looks at the central directory at the end of
                // the file, so a file cut off there would pass. ZipFile reads exactly that part.
                try (ZipFile file = new ZipFile(archive.toFile())) {
                    if (file.size() != entryCount) {
                        throw new IOException("ZIP index lists " + file.size() + " entries but " + entryCount
                                + " were read (the file is damaged)");
                    }
                }
            }

            @Override
            public void close() throws IOException {
                zip.close();
            }
        };
    }

    private static Source openTar(Path archive, Compression algorithm) throws IOException {
        InputStream raw = new BufferedInputStream(Files.newInputStream(archive), BUFFER);
        InputStream decoded;
        try {
            decoded = new BufferedInputStream(algorithm.createDecoder(raw), BUFFER);
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
        Tar.Reader reader = new Tar.Reader(decoded);
        return new Source() {
            @Override
            public Entry next() throws IOException {
                Tar.Reader.Item item = reader.next();
                return item == null ? null : new Entry(item.path(), item.directory(), item.content());
            }

            @Override
            public void finish(long entryCount) {
                // Tar.Reader already insisted on the end-of-archive marker and nothing after it.
            }

            @Override
            public void close() throws IOException {
                decoded.close();
            }
        };
    }

    private static Path snapshotPath(Path snapshotRoot, String worldFolder, String archivePath) throws IOException {
        if (archivePath.equals(worldFolder)) {
            return snapshotRoot;
        }
        String prefix = worldFolder + "/";
        if (!archivePath.startsWith(prefix)) {
            throw new IOException("Archive path outside the world folder: " + archivePath);
        }
        Path result = snapshotRoot;
        for (String part : archivePath.substring(prefix.length()).split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IOException("Unsafe path in archive: " + archivePath);
            }
            result = result.resolve(part);
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------------

    /** "EzBackup 1.0.0": the version comes from the jar manifest, i.e. from mod_version in gradle.properties. */
    private static String generator() {
        String version = BackupArchive.class.getPackage().getImplementationVersion();
        return BackupLimits.GENERATOR_NAME + " " + (version != null ? version : "dev");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    /** Counts the bytes that pass through (used to report the compressed size so far). */
    private static final class CountingOutputStream extends FilterOutputStream {
        private volatile long count;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        long count() {
            return count;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }
}
