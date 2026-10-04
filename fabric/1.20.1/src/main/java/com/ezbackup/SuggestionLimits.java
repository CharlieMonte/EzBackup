package com.ezbackup;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * The client-side rate limit of "Send Suggestion": at least {@code cooldownSeconds} between two messages and at most
 * {@code maxPerSession} messages. Only messages Discord accepted are recorded ({@link #recordSent}), so a failed send
 * costs nothing. In memory only. Pure Java; the clock is injected so the cooldown can be tested without waiting.
 * The sentences shown to the player are in {@link SuggestionMessages}; this class only has numbers.
 */
final class SuggestionLimits {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final int cooldownSeconds;
    private final int maxPerSession;
    private final LongSupplier nanoClock;
    private final AtomicInteger sentCount = new AtomicInteger();
    /** Clock value of the last accepted message; only meaningful when sentCount > 0. */
    private volatile long lastSentNanos;

    SuggestionLimits(int cooldownSeconds, int maxPerSession, LongSupplier nanoClock) {
        this.cooldownSeconds = cooldownSeconds;
        this.maxPerSession = maxPerSession;
        this.nanoClock = nanoClock;
    }

    int maxPerSession() {
        return maxPerSession;
    }

    /** True once {@code maxPerSession} messages were recorded. */
    boolean isCapReached() {
        return sentCount.get() >= maxPerSession;
    }

    /** How many more messages may be sent. */
    int messagesLeft() {
        return Math.max(0, maxPerSession - sentCount.get());
    }

    /** Whole seconds (rounded up) until the next message may be sent; 0 if one may be sent now. */
    int cooldownSecondsLeft() {
        if (sentCount.get() == 0) {
            return 0;
        }
        long remainingNanos = cooldownSeconds * NANOS_PER_SECOND - (nanoClock.getAsLong() - lastSentNanos);
        return remainingNanos <= 0 ? 0 : (int) ((remainingNanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND);
    }

    /** Starts the cooldown and counts the message. Call only when Discord accepted it. */
    void recordSent() {
        lastSentNanos = nanoClock.getAsLong();
        sentCount.incrementAndGet();
    }

    /** Seconds as m:ss, e.g. 600 -> "10:00", 59 -> "0:59". */
    static String formatCountdown(int seconds) {
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }
}
