package eu.amadeos.apps.call.repository;

import eu.amadeos.apps.call.domain.ChatFile;
import eu.amadeos.apps.call.domain.ChatLine;
import eu.amadeos.apps.call.domain.PeerKey;
import eu.amadeos.commons.desktop.PasswordStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatHistoryTest {
    private static final PeerKey BEN = new PeerKey("b".repeat(64));
    private static final SecretKey KEY = new SecretKeySpec(new byte[32], "AES");

    @TempDir
    Path dir;

    @Test
    void linesComeBackWithLineBreaksTabsAndBackslashes() {
        ChatHistory h = new ChatHistory(dir, () -> Optional.of(KEY));
        ChatLine a = new ChatLine(true, "two\nlines\tand \\n", Instant.ofEpochMilli(1000));
        ChatLine b = new ChatLine(false, "Grüße", Instant.ofEpochMilli(2000));
        h.add(BEN, List.of(a));
        h.add(BEN, List.of(b));
        assertEquals(List.of(a, b), h.load(BEN));
        assertTrue(h.has(BEN));
        h.delete(BEN);
        assertEquals(List.of(), h.load(BEN));
    }

    @Test
    void aPictureComesBackWithWhereItIs() {
        ChatHistory h = new ChatHistory(dir, () -> Optional.of(KEY));
        Path where = dir.resolve("Downloads").resolve("amade-call").resolve("floor\tplan.png");
        ChatLine picture = new ChatLine(false, "floor\tplan.png", Instant.ofEpochMilli(3000),
                ChatFile.running("x", "floor\tplan.png", 1234).arrived(where));
        h.add(BEN, List.of(new ChatLine(true, "Look:", Instant.ofEpochMilli(2000)), picture));
        List<ChatLine> back = h.load(BEN);
        assertEquals(2, back.size());
        assertEquals("Look:", back.getFirst().text());
        ChatFile f = back.get(1).file();
        assertEquals("floor\tplan.png", f.name());
        assertEquals(1234, f.size());
        assertEquals(where, f.path());
        assertEquals(ChatFile.Status.DONE, f.status());
        assertTrue(f.picture());
    }

    @Test
    void anotherKeyReadsNothing() {
        new ChatHistory(dir, () -> Optional.of(KEY)).add(BEN, List.of(new ChatLine(true, "x", Instant.EPOCH)));
        SecretKey other = new SecretKeySpec(new byte[32], 0, 32, "AES") {
        };
        byte[] bytes = new byte[32];
        bytes[0] = 1;
        assertEquals(List.of(), new ChatHistory(dir, () -> Optional.of(new SecretKeySpec(bytes, "AES"))).load(BEN));
    }

    @Test
    void withoutAKeyNothingIsWritten() {
        ChatHistory h = new ChatHistory(dir, Optional::empty);
        assertFalse(h.available());
        h.add(BEN, List.of(new ChatLine(true, "x", Instant.EPOCH)));
        assertFalse(Files.exists(dir.resolve(BEN.hex() + ".chat")));
    }

    @Test
    void onlyTheNewestLinesAreKept() {
        ChatHistory h = new ChatHistory(dir, () -> Optional.of(KEY));
        List<ChatLine> many = new java.util.ArrayList<>();
        for (int i = 0; i < ChatHistory.KEEP + 5; i++) many.add(new ChatLine(i % 2 == 0, "line " + i, Instant.ofEpochMilli(i)));
        h.add(BEN, many);
        List<ChatLine> kept = h.load(BEN);
        assertEquals(ChatHistory.KEEP, kept.size());
        assertEquals("line 5", kept.getFirst().text());
    }

    @Test
    void theKeyIsMadeOnceAndOnlyUsedWhenTheKeyringKeepsIt() {
        Map<String, String> keyring = new HashMap<>();
        ChatKey key = new ChatKey(() -> store(keyring, true));
        Optional<SecretKey> first = key.get();
        assertTrue(first.isPresent());
        assertEquals(first, new ChatKey(() -> store(keyring, true)).get(), "the same key after the next login");
        assertTrue(new ChatKey(() -> store(new HashMap<>(), false)).get().isEmpty(), "no keyring, no key");
    }

    private static PasswordStore store(Map<String, String> map, boolean persistent) {
        return new PasswordStore() {
            @Override
            public Optional<String> lookup(String key) {
                return Optional.ofNullable(map.get(key));
            }

            @Override
            public void store(String key, String label, String password) {
                map.put(key, password);
            }

            @Override
            public void delete(String key) {
                map.remove(key);
            }

            @Override
            public boolean isPersistent() {
                return persistent;
            }
        };
    }
}
