package eu.amadeos.apps.call.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Someone in the list of the main window: a computer seen in the network right now, someone called before, or
 * both.
 *
 * @param key      who it is
 * @param name     the name they gave their computer, or the one kept from the last call
 * @param endpoint where they take calls now, or where they took the last one
 * @param nearby   seen in the network at the moment
 * @param verified the safety code was compared with them
 */
public record Person(PeerKey key, String name, Optional<Endpoint> endpoint, boolean nearby, boolean verified) {
    public Person {
        Objects.requireNonNull(key, "key");
        name = name == null || name.isBlank() ? key.shortText() : name.strip();
        endpoint = endpoint == null ? Optional.empty() : endpoint;
    }

    /** Can be called: seen in the network, or at least an address is known. */
    public boolean callable() {
        return endpoint.isPresent();
    }

    /** Two letters for the round picture: "Anna Berger" gives AB, "zephyrus" gives ZE. */
    public String initials() {
        return initials(name);
    }

    public static String initials(String name) {
        String[] words = name.strip().split("[\\s._-]+");
        StringBuilder b = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty() && Character.isLetterOrDigit(w.codePointAt(0))) b.appendCodePoint(w.codePointAt(0));
            if (b.length() >= 2) break;
        }
        if (b.length() < 2) {
            String letters = name.replaceAll("[^\\p{L}\\p{N}]", "");
            b.setLength(0);
            b.append(letters, 0, Math.min(2, letters.length()));
        }
        return b.toString().toUpperCase(java.util.Locale.ROOT);
    }
}
