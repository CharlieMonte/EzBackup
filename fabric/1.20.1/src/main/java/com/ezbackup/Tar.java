package com.ezbackup;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A small, standard POSIX tar (ustar + pax) writer and reader, used under Zstd so that
 * "name.tar.zst" opens with normal tools (tar, 7-Zip, ...). No Minecraft dependencies.
 *
 * Only directories and regular files are supported, which is all a world folder contains. Names that do not
 * fit the classic 100/155 character ustar fields (or are not plain ASCII) and sizes above 8 GiB use a pax
 * extended header, exactly as GNU tar and bsdtar do.
 */
final class Tar {

    static final int BLOCK = 512;
    private static final long MAX_OCTAL_SIZE = 077777777777L; // 11 octal digits

    private Tar() {
    }

    // ---------------------------------------------------------------------------------------------
    // Writer
    // ---------------------------------------------------------------------------------------------

    /** Writes entries to {@code out}. Closing it writes nothing extra; call finish() first. */
    static final class Sink implements BackupArchive.Sink {
        private final OutputStream out;
        private long fileRemaining;
        private final OutputStream content = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (len > fileRemaining) {
                    throw new IOException("More data than the tar entry size");
                }
                out.write(b, off, len);
                fileRemaining -= len;
            }
        };

        Sink(OutputStream out) {
            this.out = out;
        }

        @Override
        public void directory(String path, long modifiedMillis) throws IOException {
            writeHeader(path + "/", '5', 0, modifiedMillis);
        }

        @Override
        public OutputStream beginFile(String path, long modifiedMillis, long size) throws IOException {
            writeHeader(path, '0', size, modifiedMillis);
            fileRemaining = size;
            return content;
        }

        @Override
        public void endFile() throws IOException {
            if (fileRemaining != 0) {
                throw new IOException("Less data than the tar entry size");
            }
            // Pad the content to a whole number of blocks. The size is known from the header we just wrote.
            out.write(new byte[padding(paddedSize)], 0, padding(paddedSize));
        }

        private long paddedSize;

        private void writeHeader(String path, char type, long size, long modifiedMillis) throws IOException {
            paddedSize = size;
            byte[] nameBytes = path.getBytes(StandardCharsets.UTF_8);
            boolean ascii = nameBytes.length == path.length();

            String name = null;
            String prefix = "";
            if (ascii && nameBytes.length <= 100) {
                name = path;
            } else if (ascii) {
                // Split at a '/' so that prefix <= 155 and name <= 100 characters.
                for (int i = path.indexOf('/'); i >= 0; i = path.indexOf('/', i + 1)) {
                    String head = path.substring(0, i);
                    String tail = path.substring(i + 1);
                    if (head.length() <= 155 && !tail.isEmpty() && tail.length() <= 100) {
                        prefix = head;
                        name = tail;
                        break;
                    }
                }
            }

            boolean paxPath = name == null;
            boolean paxSize = size > MAX_OCTAL_SIZE;
            if (paxPath || paxSize) {
                ByteArrayOutputStream records = new ByteArrayOutputStream();
                if (paxPath) {
                    records.write(paxRecord("path", path));
                }
                if (paxSize) {
                    records.write(paxRecord("size", Long.toString(size)));
                }
                byte[] data = records.toByteArray();
                out.write(header("PaxHeader/" + fallbackName(path), "", 'x', data.length, modifiedMillis, false));
                out.write(data);
                out.write(new byte[padding(data.length)]);
            }
            if (paxPath) {
                name = fallbackName(path);
            }
            out.write(header(name, prefix, type, paxSize ? 0 : size, modifiedMillis, type == '5'));
        }

        @Override
        public void finish() throws IOException {
            out.write(new byte[BLOCK * 2]);
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    /** ASCII-only, at most 90 characters: what goes in the classic name field when a pax header carries the real name. */
    private static String fallbackName(String path) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.length() && sb.length() < 90; i++) {
            char c = path.charAt(i);
            sb.append(c >= 0x20 && c < 0x7F ? c : '_');
        }
        return sb.toString();
    }

    private static int padding(long size) {
        return (int) ((BLOCK - size % BLOCK) % BLOCK);
    }

    private static byte[] paxRecord(String key, String value) {
        byte[] body = (" " + key + "=" + value + "\n").getBytes(StandardCharsets.UTF_8);
        int digits = 1;
        while (Integer.toString(body.length + digits).length() != digits) {
            digits++;
        }
        byte[] length = Integer.toString(body.length + digits).getBytes(StandardCharsets.US_ASCII);
        byte[] record = new byte[length.length + body.length];
        System.arraycopy(length, 0, record, 0, length.length);
        System.arraycopy(body, 0, record, length.length, body.length);
        return record;
    }

    private static byte[] header(String name, String prefix, char type, long size, long modifiedMillis, boolean dir) {
        byte[] h = new byte[BLOCK];
        put(h, 0, 100, name);
        putOctal(h, 100, 8, dir ? 0755 : 0644);
        putOctal(h, 108, 8, 0);
        putOctal(h, 116, 8, 0);
        putOctal(h, 124, 12, size);
        putOctal(h, 136, 12, Math.max(0, Math.floorDiv(modifiedMillis, 1000L)));
        Arrays.fill(h, 148, 156, (byte) ' ');
        h[156] = (byte) type;
        put(h, 257, 6, "ustar");
        h[263] = '0';
        h[264] = '0';
        putOctal(h, 329, 8, 0);
        putOctal(h, 337, 8, 0);
        put(h, 345, 155, prefix);

        long sum = 0;
        for (byte b : h) {
            sum += b & 0xFF;
        }
        byte[] chk = String.format("%06o", sum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(chk, 0, h, 148, 6);
        h[154] = 0;
        h[155] = ' ';
        return h;
    }

    private static void put(byte[] h, int offset, int length, String text) {
        byte[] b = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, offset, Math.min(b.length, length));
    }

    /** Octal digits, zero padded, NUL terminated, filling {@code length} bytes. */
    private static void putOctal(byte[] h, int offset, int length, long value) {
        String digits = String.format("%0" + (length - 1) + "o", value);
        put(h, offset, length - 1, digits);
        h[offset + length - 1] = 0;
    }

    // ---------------------------------------------------------------------------------------------
    // Reader
    // ---------------------------------------------------------------------------------------------

    static final class Reader {
        record Item(String path, boolean directory, InputStream content) {
        }

        private final InputStream in;
        private Bounded current;

        Reader(InputStream in) {
            this.in = in;
        }

        /** The next file or directory, or null at the end-of-archive marker (nothing may follow it). */
        Item next() throws IOException {
            if (current != null) {
                current.drain();
                current = null;
            }
            String paxPath = null;
            long paxSize = -1;
            while (true) {
                byte[] h = readBlock();
                if (h == null) {
                    throw new EOFException("Archive ended before its end marker (it is truncated)");
                }
                if (isZero(h)) {
                    byte[] second = readBlock();
                    if (second == null || !isZero(second)) {
                        throw new IOException("Corrupt archive: bad end marker");
                    }
                    if (in.read() != -1) {
                        throw new IOException("Unexpected data after the end of the archive");
                    }
                    return null;
                }
                verifyChecksum(h);
                char type = (char) (h[156] & 0xFF);
                long size = parseNumber(h, 124, 12);

                if (type == 'x') {
                    byte[] data = readFully(size);
                    skip(padding(size));
                    for (String[] kv : parsePax(data)) {
                        if (kv[0].equals("path")) {
                            paxPath = kv[1];
                        } else if (kv[0].equals("size")) {
                            paxSize = Long.parseLong(kv[1]);
                        }
                    }
                    continue;
                }
                if (type != '0' && type != 0 && type != '5') {
                    throw new IOException("Unsupported tar entry type '" + type + "'");
                }

                String path = paxPath != null ? paxPath : joinName(h);
                boolean dir = type == '5';
                if (dir && path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                if (paxSize >= 0) {
                    size = paxSize;
                }
                if (dir) {
                    return new Item(path, true, InputStream.nullInputStream());
                }
                current = new Bounded(size);
                return new Item(path, false, current);
            }
        }

        private String joinName(byte[] h) {
            String name = string(h, 0, 100);
            String prefix = string(h, 345, 155);
            return prefix.isEmpty() ? name : prefix + "/" + name;
        }

        private static String string(byte[] h, int offset, int length) {
            int end = offset;
            while (end < offset + length && h[end] != 0) {
                end++;
            }
            return new String(h, offset, end - offset, StandardCharsets.UTF_8);
        }

        private static long parseNumber(byte[] h, int offset, int length) throws IOException {
            if ((h[offset] & 0x80) != 0) { // base-256, written by some tools for huge values
                long value = h[offset] & 0x7F;
                for (int i = 1; i < length; i++) {
                    value = (value << 8) | (h[offset + i] & 0xFF);
                }
                return value;
            }
            String text = string(h, offset, length).trim();
            if (text.isEmpty()) {
                return 0;
            }
            try {
                return Long.parseLong(text, 8);
            } catch (NumberFormatException e) {
                throw new IOException("Corrupt archive: bad number in header");
            }
        }

        private static void verifyChecksum(byte[] h) throws IOException {
            long expected = parseNumber(h, 148, 8);
            long sum = 0;
            for (int i = 0; i < BLOCK; i++) {
                sum += (i >= 148 && i < 156) ? ' ' : (h[i] & 0xFF);
            }
            if (sum != expected) {
                throw new IOException("Corrupt archive: header checksum mismatch");
            }
        }

        private static boolean isZero(byte[] block) {
            for (byte b : block) {
                if (b != 0) {
                    return false;
                }
            }
            return true;
        }

        private static java.util.List<String[]> parsePax(byte[] data) throws IOException {
            java.util.List<String[]> result = new java.util.ArrayList<>();
            int pos = 0;
            while (pos < data.length) {
                int space = pos;
                while (space < data.length && data[space] != ' ') {
                    space++;
                }
                int length;
                try {
                    length = Integer.parseInt(new String(data, pos, space - pos, StandardCharsets.US_ASCII));
                } catch (NumberFormatException e) {
                    throw new IOException("Corrupt archive: bad pax header");
                }
                if (length <= 0 || pos + length > data.length) {
                    throw new IOException("Corrupt archive: bad pax header");
                }
                String kv = new String(data, space + 1, pos + length - space - 2, StandardCharsets.UTF_8);
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    result.add(new String[]{kv.substring(0, eq), kv.substring(eq + 1)});
                }
                pos += length;
            }
            return result;
        }

        /** Reads exactly one block, or returns null if the stream is already at its end. */
        private byte[] readBlock() throws IOException {
            byte[] block = new byte[BLOCK];
            int read = 0;
            while (read < BLOCK) {
                int n = in.read(block, read, BLOCK - read);
                if (n < 0) {
                    if (read == 0) {
                        return null;
                    }
                    throw new EOFException("Archive ended in the middle of a header (it is truncated)");
                }
                read += n;
            }
            return block;
        }

        private byte[] readFully(long length) throws IOException {
            if (length < 0 || length > (1 << 20)) {
                throw new IOException("Corrupt archive: bad header size");
            }
            byte[] data = new byte[(int) length];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) {
                    throw new EOFException("Archive ended in the middle of a header (it is truncated)");
                }
                read += n;
            }
            return data;
        }

        private void skip(long count) throws IOException {
            byte[] trash = new byte[8192];
            while (count > 0) {
                int n = in.read(trash, 0, (int) Math.min(trash.length, count));
                if (n < 0) {
                    throw new EOFException("Archive ended in the middle of an entry (it is truncated)");
                }
                count -= n;
            }
        }

        /** The content of one file: ends after exactly {@code size} bytes, then the block padding is skipped. */
        private final class Bounded extends InputStream {
            private final long size;
            private long remaining;

            Bounded(long size) {
                this.size = size;
                this.remaining = size;
            }

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (remaining == 0) {
                    return -1;
                }
                int n = in.read(b, off, (int) Math.min(len, remaining));
                if (n < 0) {
                    throw new EOFException("Archive ended in the middle of a file (it is truncated)");
                }
                remaining -= n;
                if (remaining == 0) {
                    Reader.this.skip(padding(size));
                }
                return n;
            }

            void drain() throws IOException {
                byte[] trash = new byte[8192];
                while (remaining > 0) {
                    read(trash, 0, trash.length);
                }
                // A zero-length file never calls read(), so its (zero) padding needs no skipping.
            }
        }
    }
}
