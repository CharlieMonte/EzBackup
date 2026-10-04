package com.ezbackup;

/**
 * Every sentence the "Send Suggestion" feature shows the player, in one place (the network and limit classes return
 * codes and numbers only). Change wording here; {@link SuggestionSender.Outcome} and the checks in
 * {@link SuggestionSender} use these.
 */
final class SuggestionMessages {

    private SuggestionMessages() {
    }

    // How a send ended (one per SuggestionSender.Outcome).
    static final String SENT = "Message sent successfully!";
    static final String UNAVAILABLE = "Could not send: the webhook may be inactive. Please check for a newer version of EzBackup "
            + "for your Minecraft version, as newer versions may contain a working webhook.";
    static final String RATE_LIMITED = "Discord is temporarily rate-limiting messages. Please try again later.";
    static final String SERVER_ERROR = "Unable to send: Discord is having temporary problems. Please try again later.";
    static final String FAILED = "No response from the webhook. Check your internet connection and try again. If it keeps failing, "
            + "the webhook may be inactive: check for a newer version of EzBackup for your Minecraft version, "
            + "as newer versions may contain a working webhook.";

    // Why a text or a send is refused.
    static final String EMPTY_TEXT = "Please type a suggestion before sending.";
    static final String INVALID_CHARACTERS =
            "Only the characters A-Z, a-z, 0-9, spaces, - (hyphen), _ (underscore), . (period), ! and ? are allowed.";
    static final String ALREADY_SENDING = "A suggestion is already being sent.";

    static String tooLong(int length, int maxLength) {
        return "Suggestion is too long (" + length + " of " + maxLength + " characters).";
    }

    static String capReached(int maxPerSession) {
        return "Message limit reached (" + maxPerSession + " per session). Restart Minecraft to send more messages.";
    }

    static String pleaseWait(String countdown) {
        return "Please wait " + countdown + " before sending another message.";
    }
}
