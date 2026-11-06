package eu.amadeos.apps.call.domain;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainTest {
    static final PeerKey A = new PeerKey("0".repeat(63) + "1");
    static final PeerKey B = new PeerKey("f".repeat(64));

    @Test
    void aKeyIsTheSha256OfThePublicKey() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PeerKey key = PeerKey.of(pair.getPublic());
        assertEquals(64, key.hex().length());
        assertEquals(key, PeerKey.of(pair.getPublic().getEncoded()));
        assertEquals(32, key.bytes().length);
    }

    @Test
    void keysAreReadInAnyCaseAndCheckedForShape() {
        assertEquals(B, new PeerKey("F".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new PeerKey("abc"));
        assertThrows(IllegalArgumentException.class, () -> new PeerKey("g".repeat(64)));
        assertTrue(PeerKey.valid(" " + "a".repeat(64) + " "));
        assertFalse(PeerKey.valid(null));
        assertFalse(PeerKey.valid("a".repeat(63)));
    }

    @Test
    void theShortTextShowsSixteenDigitsInGroups() {
        assertEquals("ffff ffff ffff ffff", B.shortText());
    }

    @Test
    void theSafetyCodeIsTheSameOnBothSides() {
        SafetyCode ab = SafetyCode.of(A, B);
        SafetyCode ba = SafetyCode.of(B, A);
        assertEquals(ab, ba);
        assertEquals(6, ab.groups().size());
        ab.groups().forEach(g -> assertTrue(g.matches("\\d{5}"), g));
    }

    @Test
    void theSafetyCodeChangesWithEitherKey() {
        PeerKey c = new PeerKey("e".repeat(64));
        assertNotEquals(SafetyCode.of(A, B), SafetyCode.of(A, c));
        assertEquals(SafetyCode.of(A, B).groups().subList(0, 3), SafetyCode.of(A, c).groups().subList(0, 3));
    }

    @Test
    void theSafetyCodeHalfIsTheKeysFirstBytesAsDigits() {
        // 0x00..01 is 1 in the first 64 bits? no: the first 8 bytes of A are zero.
        assertEquals(List.of("00000", "00000", "00000"), SafetyCode.half(A));
        // 0xffffffffffffffff = 18446744073709551615, its last 15 digits
        assertEquals(List.of("74407", "37095", "51615"), SafetyCode.half(B));
        assertEquals("00000 00000 00000 74407 37095 51615", SafetyCode.of(B, A).toString());
    }

    @Test
    void addressesAreReadWithAndWithoutPort() {
        assertEquals(Optional.of(new Endpoint("192.168.1.20", Endpoint.DEFAULT_PORT)), Endpoint.parse("192.168.1.20"));
        assertEquals(Optional.of(new Endpoint("anna-pc.local", 47475)), Endpoint.parse(" anna-pc.local:47475 "));
        assertEquals(Optional.of(new Endpoint("fe80::1", 5000)), Endpoint.parse("[fe80::1]:5000"));
        assertEquals(Optional.of(new Endpoint("fe80::1", Endpoint.DEFAULT_PORT)), Endpoint.parse("fe80::1"));
        assertEquals(Optional.of(new Endpoint("fe80::1", Endpoint.DEFAULT_PORT)), Endpoint.parse("[fe80::1]"));
    }

    @Test
    void brokenAddressesAreRefused() {
        for (String bad : new String[]{null, "", "  ", "a b", "host:x", "host:0", "host:70000", "[fe80::1", "[::1]x"}) {
            assertEquals(Optional.empty(), Endpoint.parse(bad), bad);
        }
    }

    @Test
    void addressesAreWrittenAsTheyAreTyped() {
        assertEquals("192.168.1.20", new Endpoint("192.168.1.20", Endpoint.DEFAULT_PORT).toString());
        assertEquals("anna-pc.local:5000", new Endpoint("anna-pc.local", 5000).toString());
        assertEquals("[fe80::1]:5000", new Endpoint("[fe80::1]", 5000).toString());
        assertEquals(Endpoint.parse("[fe80::1]:5000"), Endpoint.parse(new Endpoint("fe80::1", 5000).toString()));
    }

    @Test
    void initialsComeFromTheWordsOfTheName() {
        assertEquals("AB", Person.initials("Anna Berger"));
        assertEquals("RE", Person.initials("Ralf Eichinger (zephyrus)"));
        assertEquals("ZE", Person.initials("zephyrus"));
        assertEquals("ÉL", Person.initials("élodie lambert"));
        assertEquals("X", Person.initials("x"));
        assertEquals("AB", Person.initials("anna.berger"));
    }

    @Test
    void aPersonWithoutNameShowsTheirKey() {
        Person p = new Person(B, " ", null, false, false);
        assertEquals(B.shortText(), p.name());
        assertFalse(p.callable());
        assertTrue(new Person(B, "Ben", Optional.of(new Endpoint("10.0.0.2", 1234)), true, false).callable());
    }

    @Test
    void callRecordsAndChatLinesAreCleanedUp() {
        CallRecord r = new CallRecord(A, null, Instant.EPOCH, Duration.ofSeconds(-3), CallRecord.Direction.MISSED,
                CallKind.AUDIO);
        assertEquals("", r.name());
        assertEquals(Duration.ZERO, r.length());
        ChatLine line = new ChatLine(true, "x".repeat(ChatLine.MAX_LENGTH + 10), Instant.EPOCH);
        assertEquals(ChatLine.MAX_LENGTH, line.text().length());
        assertTrue(CallKind.VIDEO.video());
        assertFalse(CallKind.AUDIO.video());
    }
}
