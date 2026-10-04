package com.ezbackup;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * The Discord side of "Send Suggestion": builds the JSON body and does ONE HTTPS POST to an incoming webhook. No Minecraft classes
 * (java.base plus the mod's logger). It returns result CODES only; the sentences shown to the player are in {@link SuggestionMessages}, and
 * the rules about when to send, the session switch-off and the thread are in {@link SuggestionSender}.
 *
 * <p>Fixed rules: redirects are not followed, only the status code is read (never the response body), the User-Agent
 * holds no player data, timeouts come from {@link SuggestionConfig}, and the webhook URL is never logged (only the type of
 * an exception is).
 */
final class DiscordWebhook {

    /** What a POST ended with, as a code. {@link SuggestionSender.Outcome} adds the player-facing text. */
    enum Result {
        /** 2xx. */
        SENT,
        /** 404 / 401 / 403: the webhook was deleted or is refused. */
        UNAVAILABLE,
        /** 429. */
        RATE_LIMITED,
        /** 5xx. */
        SERVER_ERROR,
        /** Timeouts, no network, redirects, other status codes, any exception. */
        FAILED
    }

    /** A real User-Agent: some services reject Java's default one. Contains no player information. */
    private static final String USER_AGENT = "EzBackup-Suggestions";

    private DiscordWebhook() {
    }

    /** One HTTPS POST. Only the status code is read. Never throws. */
    static Result post(String webhookUrl, byte[] body) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(SuggestionConfig.CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(SuggestionConfig.READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false); // never forward the text somewhere else
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            return resultFor(connection.getResponseCode());
        } catch (IOException | RuntimeException e) {
            // Only the exception type is logged: messages can contain the webhook URL, which is a secret.
            EzBackup.LOGGER.warn("Suggestion could not be sent: {}", e.getClass().getSimpleName());
            return Result.FAILED;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static Result resultFor(int status) {
        if (status >= 200 && status < 300) {
            return Result.SENT;
        }
        if (status == 404 || status == 401 || status == 403) {
            return Result.UNAVAILABLE;
        }
        if (status == 429) {
            return Result.RATE_LIMITED;
        }
        if (status >= 500 && status < 600) {
            return Result.SERVER_ERROR;
        }
        EzBackup.LOGGER.warn("The suggestion webhook answered with HTTP {}", status);
        return Result.FAILED;
    }

    // ---------------------------------------------------------------------------------------------
    // Request body
    // ---------------------------------------------------------------------------------------------

    /**
     * The JSON body: one embed holding the text; its title and colour show the tag (the caller passes both). {@code allowed_mentions.parse = []} stops Discord from pinging
     * anybody, even if the text contains "@everyone" or role mentions.
     */
    static byte[] buildPayload(String title, int color, String text, String footer) {
        String json = "{\"embeds\":[{\"title\":" + jsonString(title)
                + ",\"color\":" + color
                + ",\"description\":" + jsonString(text)
                + ",\"footer\":{\"text\":" + jsonString(footer) + "}}]"
                + ",\"allowed_mentions\":{\"parse\":[]}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** A JSON string literal, quotes included. Unpaired surrogates (not valid in UTF-8) become U+FFFD. */
    static String jsonString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else if (Character.isHighSurrogate(c)
                            && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                        out.append(c).append(value.charAt(++i)); // a valid pair stays as it is
                    } else if (Character.isSurrogate(c)) {
                        out.append('\uFFFD');
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
