package eu.amadeos.apps.call.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A line of the chat beside the call: a short text, or a picture or file ({@code file} not null; the text is
 * then the file's name).
 */
public record ChatLine(boolean mine, String text, Instant at, ChatFile file) {
    /** Longer messages are cut; the chat is for short notes and links, files go as files. */
    public static final int MAX_LENGTH = 4000;

    public ChatLine {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(at, "at");
        if (text.length() > MAX_LENGTH) text = text.substring(0, MAX_LENGTH);
    }

    public ChatLine(boolean mine, String text, Instant at) {
        this(mine, text, at, null);
    }

    /** The same line with the file as it stands now. */
    public ChatLine with(ChatFile now) {
        return new ChatLine(mine, text, at, now);
    }

    /** Lines about the same file are one line in the chat. */
    public boolean sameFile(ChatLine other) {
        return file != null && other.file != null && file.id().equals(other.file.id());
    }
}
