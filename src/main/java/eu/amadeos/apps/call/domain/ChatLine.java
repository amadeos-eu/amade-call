package eu.amadeos.apps.call.domain;

import java.time.Instant;
import java.util.Objects;

/** A line of the chat beside the call. */
public record ChatLine(boolean mine, String text, Instant at) {
    /** Longer messages are cut; the chat is for short notes and links, not for files. */
    public static final int MAX_LENGTH = 4000;

    public ChatLine {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(at, "at");
        if (text.length() > MAX_LENGTH) text = text.substring(0, MAX_LENGTH);
    }
}
