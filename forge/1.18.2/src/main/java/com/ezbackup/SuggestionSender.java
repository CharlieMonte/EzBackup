package com.ezbackup;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Sends ONE text-only message to the developer's Discord server through an incoming webhook. Each type ({@link Tag})
 * has its own webhook in {@link SuggestionConfig}, and each webhook points at its own Discord channel, so the type the
 * player picks decides the channel. Used by client/SuggestionScreen. This class is the public face and the
 * orchestration: text rules, the "is it allowed now" decision, the background thread and the "switched off this
 * session" flags. The work is split into {@link SuggestionLimits} (cooldown and cap), {@link DiscordWebhook} (the HTTP
 * request and JSON body, result codes only) and {@link SuggestionMessages} (every sentence shown to the player).
 *
 * <p>What is sent: the text, the tag the player picked (Suggestion / Bug report / Other) and a footer "EzBackup &lt;version&gt; | Minecraft &lt;version&gt; | Mod loader: &lt;name&gt;". Nothing else:
 * no player name, no world, no files, no screenshots, no logs. The request is one HTTPS POST with a small JSON body.
 * The response body is never read; only the status code is looked at.
 *
 * <p>Character rule: a suggestion may contain only {@code A-Z a-z 0-9}, the space and {@code - _ . ! ?} (see {@link #isAllowedChar},
 * {@link #filterAllowed}). The text box enforces it while typing and pasting; {@link #textProblem} checks it again, so
 * any other caller of {@link #submit} cannot send line breaks or other symbols either.
 *
 * <p>Threading: {@link #submit} returns at once and does the HTTP request on a daemon thread, so the game never
 * waits for Discord. The callback is called on that worker thread; the caller must hand the result over to the
 * game thread itself (the screen uses {@code Minecraft.execute}). Connect and read timeouts are set (see
 * {@link SuggestionConfig}), so a dead connection cannot hang the worker forever.
 *
 * <p>Status codes:
 * <ul>
 *   <li>2xx: {@link Outcome#SENT}.</li>
 *   <li>404, 401, 403: the webhook of THAT type was deleted or is refused. {@link Outcome#UNAVAILABLE}, and that type is
 *       switched off until the game is restarted (in-memory flag only, nothing is saved). The other types keep working.
 *       After that no request is made for that type.</li>
 *   <li>429: {@link Outcome#RATE_LIMITED}. Temporary; the player may try again later.</li>
 *   <li>5xx: {@link Outcome#SERVER_ERROR}. Temporary; the player may try again.</li>
 *   <li>Timeouts, no network, anything else: {@link Outcome#FAILED}. The player may try again.</li>
 * </ul>
 *
 * <p>Limits (client side, in memory only, so they reset when Minecraft is restarted): at most one message every
 * {@link SuggestionConfig#COOLDOWN_SECONDS} seconds, and at most {@link SuggestionConfig#MAX_PER_SESSION}
 * messages per game session. The limits are shared by all types. Only messages Discord actually accepted (2xx) count,
 * so a failed send costs nothing.
 */
public final class SuggestionSender {

    /**
     * What kind of message this is. The player picks it in the dropdown; the default is {@link #OTHER}. Each type
     * has its own webhook URL (and so its own Discord channel) from {@link SuggestionConfig}.
     */
    public enum Tag {
        SUGGESTION("Suggestion", "New EzBackup suggestion", 0x3498DB, SuggestionConfig.SUGGESTION_WEBHOOK),
        BUG_REPORT("Bug report", "New EzBackup bug report", 0xE74C3C, SuggestionConfig.BUG_REPORT_WEBHOOK),
        OTHER("Other", "New EzBackup message (other)", 0x95A5A6, SuggestionConfig.OTHER_WEBHOOK);

        private final String label;
        private final String embedTitle;
        private final int color;
        private final String webhookUrl;

        Tag(String label, String embedTitle, int color, String webhookUrl) {
            this.label = label;
            this.embedTitle = embedTitle;
            this.color = color;
            this.webhookUrl = webhookUrl;
        }

        /** Text shown in the dropdown. */
        public String label() {
            return label;
        }
    }

    /** How a send ended, with the text to show the player (the sentences are in {@link SuggestionMessages}). */
    public enum Outcome {
        SENT(SuggestionMessages.SENT),
        UNAVAILABLE(SuggestionMessages.UNAVAILABLE),
        RATE_LIMITED(SuggestionMessages.RATE_LIMITED),
        SERVER_ERROR(SuggestionMessages.SERVER_ERROR),
        FAILED(SuggestionMessages.FAILED);

        private final String message;

        Outcome(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }

        /** The player-facing outcome for a code from {@link DiscordWebhook}. */
        static Outcome of(DiscordWebhook.Result result) {
            return switch (result) {
                case SENT -> SENT;
                case UNAVAILABLE -> UNAVAILABLE;
                case RATE_LIMITED -> RATE_LIMITED;
                case SERVER_ERROR -> SERVER_ERROR;
                case FAILED -> FAILED;
            };
        }
    }

    private static final String THREAD_NAME = "EzBackup-Suggestion";

    /** Cooldown and session cap, shared by all types (in memory only, so they reset when Minecraft restarts). */
    private static final SuggestionLimits LIMITS = new SuggestionLimits(
            SuggestionConfig.COOLDOWN_SECONDS, SuggestionConfig.MAX_PER_SESSION, System::nanoTime);
    /** Types whose webhook answered 404 / 401 / 403. Lives only in memory, so it resets when Minecraft is restarted. */
    private static final Set<Tag> DISABLED = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean SENDING = new AtomicBoolean();

    private SuggestionSender() {
    }

    // ---------------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------------

    /**
     * False if the webhook of {@code tag} has not been configured yet, or after Discord refused it
     * (404 / 401 / 403) this session. A null tag counts as {@link Tag#OTHER}.
     */
    public static boolean isAvailable(Tag tag) {
        Tag chosen = tag == null ? Tag.OTHER : tag;
        return !DISABLED.contains(chosen) && SuggestionConfig.webhookConfigured(chosen.webhookUrl);
    }

    /** The mod loader this build is made for ("Fabric", "Forge" or "NeoForge"); it is sent with every message. */
    public static String modLoaderName() {
        return McCompat.modLoaderName();
    }

    /** True if at least one type can still be sent (used by the Settings screen to enable its button). */
    public static boolean isAvailable() {
        for (Tag tag : Tag.values()) {
            if (isAvailable(tag)) {
                return true;
            }
        }
        return false;
    }

    /** True while a message is on its way to Discord. Only one is sent at a time. */
    public static boolean isSending() {
        return SENDING.get();
    }

    /** True once {@link SuggestionConfig#MAX_PER_SESSION} messages were sent; locked until Minecraft restarts. */
    public static boolean isCapReached() {
        return LIMITS.isCapReached();
    }

    /** How many more messages may be sent this session. */
    public static int messagesLeft() {
        return LIMITS.messagesLeft();
    }

    /** Whole seconds (rounded up) until the next message may be sent; 0 if one may be sent now. */
    public static int cooldownSecondsLeft() {
        return LIMITS.cooldownSecondsLeft();
    }

    /** Seconds as m:ss, e.g. 600 -> "10:00", 59 -> "0:59". */
    public static String formatCountdown(int seconds) {
        return SuggestionLimits.formatCountdown(seconds);
    }

    /** Null if the limits allow a send right now, otherwise the message to show the player. */
    public static String limitProblem() {
        if (LIMITS.isCapReached()) {
            return SuggestionMessages.capReached(LIMITS.maxPerSession());
        }
        int wait = LIMITS.cooldownSecondsLeft();
        if (wait > 0) {
            return SuggestionMessages.pleaseWait(SuggestionLimits.formatCountdown(wait));
        }
        return null;
    }

    /** The only characters a suggestion may contain: A-Z a-z 0-9, space, - _ . ! ? (the text box and {@link #textProblem} both use this). */
    public static boolean isAllowedChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == ' ' || c == '.' || c == '!' || c == '?'  ;
    }

    /** {@code text} without every character that {@link #isAllowedChar} rejects (the text box uses it for pasted text). */
    public static String filterAllowed(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isAllowedChar(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Cleans text for sending and for the text box: line breaks become {@code \n}, tabs become spaces, and all other
     * control characters and unpaired surrogate halves (half an emoji) are dropped. Everything else is kept as typed.
     */
    public static String sanitize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                out.append('\n');
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++; // "\r\n" is one line break
                }
            } else if (c == '\t') {
                out.append(' ');
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    out.append(c).append(text.charAt(++i)); // a complete pair is one character, keep it
                }
            } else if (c == '\n' || !(Character.isISOControl(c) || Character.isLowSurrogate(c))) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Null if the text may be sent, otherwise the reason it may not (empty, a character the rule does not allow, or
     * too long). The text is cleaned with {@link #sanitize} and stripped first, as {@link #submit} does.
     */
    public static String textProblem(String text) {
        String clean = text == null ? "" : sanitize(text).strip();
        if (clean.isEmpty()) {
            return SuggestionMessages.EMPTY_TEXT;
        }
        if (filterAllowed(clean).length() != clean.length()) {
            return SuggestionMessages.INVALID_CHARACTERS;
        }
        if (clean.length() > SuggestionConfig.MAX_LENGTH) {
            return SuggestionMessages.tooLong(clean.length(), SuggestionConfig.MAX_LENGTH);
        }
        return null;
    }

    /**
     * Starts sending {@code text} in the background, to the channel of {@code tag} (null counts as {@link Tag#OTHER}).
     * Returns null if the send was started (the result then arrives through {@code onDone}, on the worker thread),
     * otherwise a message for the player and nothing was sent. The "unavailable" check happens here, before any
     * network use.
     */
    public static String submit(String text, Tag tag, Consumer<Outcome> onDone) {
        Tag chosen = tag == null ? Tag.OTHER : tag;
        if (!isAvailable(chosen)) {
            return Outcome.UNAVAILABLE.message();
        }
        String problem = textProblem(text);
        if (problem != null) {
            return problem;
        }
        String limit = limitProblem();
        if (limit != null) {
            return limit;
        }
        if (!SENDING.compareAndSet(false, true)) {
            return SuggestionMessages.ALREADY_SENDING;
        }
        String clean = sanitize(text).strip();
        try {
            Thread thread = new Thread(() -> run(clean, chosen, onDone), THREAD_NAME);
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable t) {
            SENDING.set(false);
            EzBackup.LOGGER.warn("Could not start the suggestion thread", t);
            return Outcome.FAILED.message();
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Worker
    // ---------------------------------------------------------------------------------------------

    private static void run(String text, Tag tag, Consumer<Outcome> onDone) {
        Outcome outcome;
        try {
            String footer = "EzBackup " + McCompat.modVersion(EzBackup.MOD_ID)
                    + " | Minecraft " + McCompat.minecraftVersion()
                    + " | Mod loader: " + McCompat.modLoaderName();
            outcome = deliver(tag, text, footer);
            if (outcome == Outcome.SENT) {
                LIMITS.recordSent(); // start the cooldown and count it only when Discord accepted it
            }
        } catch (Throwable t) {
            EzBackup.LOGGER.warn("Suggestion could not be sent: {}", t.getClass().getSimpleName());
            outcome = Outcome.FAILED;
        } finally {
            SENDING.set(false);
        }
        try {
            onDone.accept(outcome);
        } catch (Throwable t) {
            EzBackup.LOGGER.warn("Suggestion result handler failed", t);
        }
    }

    /** Sends one message to the webhook of {@code tag} and applies the "switch off for this session" rule to that type. Not for the game thread. */
    static Outcome deliver(Tag tag, String text, String footer) {
        if (DISABLED.contains(tag)) {
            return Outcome.UNAVAILABLE; // never contact Discord again for this type this session
        }
        byte[] body = DiscordWebhook.buildPayload(tag.embedTitle, tag.color, text, footer);
        Outcome outcome = Outcome.of(DiscordWebhook.post(tag.webhookUrl, body));
        if (outcome == Outcome.UNAVAILABLE) {
            DISABLED.add(tag);
            EzBackup.LOGGER.warn("The webhook for \"{}\" was refused; that type is off until Minecraft restarts.", tag.label);
        }
        return outcome;
    }
}
