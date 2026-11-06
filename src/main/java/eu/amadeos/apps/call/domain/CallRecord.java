package eu.amadeos.apps.call.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** A call in the list of recent calls. {@code length} is zero for calls that did not take place. */
public record CallRecord(PeerKey key, String name, Instant start, Duration length, Direction direction, CallKind kind) {
    public enum Direction { OUTGOING, INCOMING, MISSED }

    public CallRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(kind, "kind");
        name = name == null ? "" : name.strip();
        length = length == null || length.isNegative() ? Duration.ZERO : length;
    }
}
