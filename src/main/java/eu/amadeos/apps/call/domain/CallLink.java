package eu.amadeos.apps.call.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * A link that starts a call: {@code amadeos-call:<key fingerprint>}, optionally with {@code ?kind=video},
 * {@code audio} or {@code chat}. Address books keep it as the vCard {@code IMPP} of a person, and Contacts opens it
 * to call; the address of the computer is found in the network by the key, so it never goes stale.
 */
public record CallLink(PeerKey key, CallKind kind) {
    public static final String SCHEME = "amadeos-call";

    public static Optional<CallLink> parse(String text) {
        if (text == null) return Optional.empty();
        String t = text.strip();
        if (!t.regionMatches(true, 0, SCHEME + ":", 0, SCHEME.length() + 1)) return Optional.empty();
        String rest = t.substring(SCHEME.length() + 1);
        if (rest.startsWith("//")) rest = rest.substring(2);
        CallKind kind = CallKind.VIDEO;
        int q = rest.indexOf('?');
        if (q >= 0) {
            for (String pair : rest.substring(q + 1).split("&")) {
                if (pair.toLowerCase(Locale.ROOT).startsWith("kind=")) {
                    switch (pair.substring(5).toLowerCase(Locale.ROOT)) {
                        case "audio" -> kind = CallKind.AUDIO;
                        case "chat" -> kind = CallKind.CHAT;
                        default -> kind = CallKind.VIDEO;
                    }
                }
            }
            rest = rest.substring(0, q);
        }
        String hex = rest.replace(" ", "").toLowerCase(Locale.ROOT);
        return PeerKey.valid(hex) ? Optional.of(new CallLink(new PeerKey(hex), kind)) : Optional.empty();
    }

    /** The link as an address book keeps it, without a kind. */
    public static String of(PeerKey key) {
        return SCHEME + ":" + key.hex();
    }

    public String uri() {
        return of(key) + "?kind=" + kind.name().toLowerCase(Locale.ROOT);
    }
}
