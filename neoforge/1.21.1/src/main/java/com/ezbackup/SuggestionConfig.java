package com.ezbackup;

/**
 * Constants of the "Send Suggestion" feature (see {@link SuggestionSender} and client/SuggestionScreen).
 * Only the typed text, the type the player picked and the EzBackup and Minecraft versions and the mod loader name are ever sent. Nothing else.
 */
public final class SuggestionConfig {

    private SuggestionConfig() {
    }

    /**
     * The Discord incoming webhook URLs that messages are posted to, ONE PER TYPE: each webhook belongs to one Discord
     * channel, so this is how each type ends up in its own channel. Replace them with your own if you build your own copy.
     * (Discord: Server Settings > Integrations > Webhooks > New Webhook > pick the channel > Copy Webhook URL.)
     * A URL must start with https://. If it does not, that type cannot be sent (the "Send Suggestion" button is greyed
     * out while that type is selected) and no network request is ever made for it. These three lines are the ONLY place
     * the URLs are written.
     * You may point several types at the same URL (for example OTHER_WEBHOOK = SUGGESTION_WEBHOOK) if you want them to
     * share a channel.
     * Note: anything compiled into a mod jar can be read by anyone who has the jar. If a URL is ever abused,
     * delete that webhook in Discord and put a new URL here; released copies then stop sending that type by themselves
     * (Discord answers 404, 401 or 403, and the mod switches that type off for that game session).
     */
    public static final String SUGGESTION_WEBHOOK = "";
    public static final String BUG_REPORT_WEBHOOK = "";
    public static final String OTHER_WEBHOOK = ""; 

    /** Longest allowed suggestion, in characters. */
    public static final int MAX_LENGTH = 1000;

    /** Rate limit: at least this many seconds between two messages (10 minutes). Shared by all types. */
    public static final int COOLDOWN_SECONDS = 600;

    /** Hard cap: messages per game session, all types together. After this the player is locked out until Minecraft is restarted. */
    public static final int MAX_PER_SESSION = 12;

    /** How long to wait for Discord before giving up, in milliseconds. */
    public static final int CONNECT_TIMEOUT_MS = 5000;
    public static final int READ_TIMEOUT_MS = 8000;

    /** True if {@code url} is an https:// address. */
    public static boolean webhookConfigured(String url) {
        return url != null && url.startsWith("https://");
    }
}
