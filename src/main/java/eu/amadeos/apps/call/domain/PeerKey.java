package eu.amadeos.apps.call.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Who someone is: the SHA-256 fingerprint of the public key of their computer, as 64 lower-case hex digits. The
 * key is made once per user and computer ({@code IdentityStore}); the name can change, the key cannot.
 */
public record PeerKey(String hex) implements Comparable<PeerKey> {
    public PeerKey {
        Objects.requireNonNull(hex, "hex");
        hex = hex.strip().toLowerCase(Locale.ROOT);
        if (!hex.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("not a key fingerprint: " + hex);
    }

    public static PeerKey of(PublicKey key) {
        return of(key.getEncoded());
    }

    /** The fingerprint of an encoded (X.509 SubjectPublicKeyInfo) public key. */
    public static PeerKey of(byte[] encodedKey) {
        try {
            return new PeerKey(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encodedKey)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True for a well-formed fingerprint, for values read from the network or a file. */
    public static boolean valid(String text) {
        return text != null && text.strip().toLowerCase(Locale.ROOT).matches("[0-9a-f]{64}");
    }

    public byte[] bytes() {
        return HexFormat.of().parseHex(hex);
    }

    /** The first 16 digits in groups of four, for the status bar and tool tips: "3f2a 9c01 ...". */
    public String shortText() {
        return hex.substring(0, 4) + " " + hex.substring(4, 8) + " " + hex.substring(8, 12) + " " + hex.substring(12, 16);
    }

    @Override
    public int compareTo(PeerKey other) {
        return hex.compareTo(other.hex);
    }

    @Override
    public String toString() {
        return hex;
    }
}
