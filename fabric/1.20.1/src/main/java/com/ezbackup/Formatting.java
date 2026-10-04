package com.ezbackup;

import java.util.Locale;

/** Human-readable numbers for chat messages. Pure Java, no Minecraft dependencies. */
final class Formatting {

    private Formatting() {
    }

    /** 1536 -> "1.50 KB" */
    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }

    /** "3.41:1", or "n/a" when the compressed size is unknown. */
    static String ratio(long original, long compressed) {
        if (compressed <= 0) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.2f:1", (double) original / compressed);
    }

    /** Nanoseconds as a readable time: 3725 s (3.725e12 ns) -> "1h 2m 5s" */
    static String duration(long nanos) {
        long seconds = Math.max(0, nanos / 1_000_000_000L);
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return h + "h " + m + "m " + s + "s";
        }
        if (m > 0) {
            return m + "m " + s + "s";
        }
        return s + "s";
    }
}
