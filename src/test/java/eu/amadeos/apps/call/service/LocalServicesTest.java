package eu.amadeos.apps.call.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The key file, the autostart entry, the single-instance socket and the ring tones. */
class LocalServicesTest {
    @TempDir
    Path dir;

    @Test
    void theKeyIsMadeOnceAndOnlyTheUserCanReadIt() throws Exception {
        IdentityStore store = new IdentityStore(dir.resolve("call"));
        var first = IdentityStore.key(store.load());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file())));
        assertEquals(first, IdentityStore.key(new IdentityStore(dir.resolve("call")).load()));
        Files.delete(store.file());
        assertNotEquals(first, IdentityStore.key(store.load()));
    }

    @Test
    void autostartWritesAndRemovesTheEntry() throws Exception {
        Autostart autostart = new Autostart(dir);
        assertFalse(autostart.enabled());
        autostart.set(true);
        assertTrue(autostart.enabled());
        String entry = Files.readString(dir.resolve("autostart/amade-call.desktop"));
        assertTrue(entry.contains("Exec=/usr/bin/amade-call --background"), entry);
        autostart.set(false);
        assertFalse(autostart.enabled());
        autostart.set(false);
    }

    @Test
    void aSecondStartReachesTheFirst() throws Exception {
        Path socket = dir.resolve("call.sock");
        try (SingleInstance first = new SingleInstance(socket)) {
            assertFalse(new SingleInstance(socket).sendToRunning("show"));
            BlockingQueue<String> got = new ArrayBlockingQueue<>(2);
            first.listen(got::add);
            assertTrue(new SingleInstance(socket).sendToRunning("show"));
            assertEquals("show", got.poll(5, TimeUnit.SECONDS));
        }
        assertFalse(Files.exists(socket));
        assertFalse(new SingleInstance(socket).sendToRunning("show"));
    }

    @Test
    void ringTonesHaveSoundAndSilence() {
        byte[] ring = Ringer.ring();
        assertEquals((int) (44100 * 2.4) * 2, ring.length);
        assertTrue(loudest(ring, 0, 0.35) > 5000);
        assertEquals(0, loudest(ring, 1.0, 2.3));
        byte[] back = Ringer.ringBack();
        int peak = loudest(back, 0, 1.0);
        assertTrue(peak > 2000 && peak <= 3500, "ring-back peak " + peak);
        assertEquals(0, loudest(back, 1.1, 4.9));
    }

    private static int loudest(byte[] samples, double from, double to) {
        int max = 0;
        for (int i = (int) (from * 44100); i < (int) (to * 44100); i++) {
            int v = (short) ((samples[2 * i] & 0xff) | (samples[2 * i + 1] << 8));
            max = Math.max(max, Math.abs(v));
        }
        return max;
    }
}
