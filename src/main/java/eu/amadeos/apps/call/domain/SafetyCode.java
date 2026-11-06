package eu.amadeos.apps.call.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The numbers both people compare to be sure that nobody sits between them: six groups of five digits, the
 * first three from one key and the last three from the other, in the same order on both computers. Numbers
 * instead of words, so that two people with different languages read the same thing.
 *
 * <p>Each half carries about 50 bits of its key's fingerprint. Someone in the middle would need a key of their
 * own whose fingerprint gives the same 15 digits as the real one; that is a second preimage of 50 bits, which
 * cannot be found while a call rings.
 */
public record SafetyCode(List<String> groups) {
    public static final int GROUPS = 6;
    private static final BigInteger HALF = BigInteger.TEN.pow(15);

    public SafetyCode {
        groups = List.copyOf(groups);
        if (groups.size() != GROUPS) throw new IllegalArgumentException("six groups expected");
    }

    public static SafetyCode of(PeerKey a, PeerKey b) {
        PeerKey first = a.compareTo(b) <= 0 ? a : b;
        PeerKey second = first == a ? b : a;
        List<String> groups = new ArrayList<>(half(first));
        groups.addAll(half(second));
        return new SafetyCode(groups);
    }

    /** The first 64 bits of the fingerprint as 15 digits in three groups. */
    static List<String> half(PeerKey key) {
        byte[] bytes = Arrays.copyOf(key.bytes(), 8);
        String digits = String.format("%015d", new BigInteger(1, bytes).mod(HALF));
        return List.of(digits.substring(0, 5), digits.substring(5, 10), digits.substring(10, 15));
    }

    @Override
    public String toString() {
        return String.join(" ", groups);
    }
}
