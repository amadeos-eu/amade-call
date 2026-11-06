package eu.amadeos.apps.call.service;

import eu.amadeos.apps.call.domain.CallKind;
import eu.amadeos.apps.call.domain.ChatLine;
import eu.amadeos.apps.call.domain.EndReason;
import eu.amadeos.apps.call.domain.Endpoint;
import eu.amadeos.apps.call.domain.PeerKey;
import eu.amadeos.apps.call.media.GstMedia;
import eu.amadeos.apps.call.media.MediaSettings;
import eu.amadeos.apps.call.media.Sources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Two phones in one program call each other over localhost with GStreamer's test picture and test tone: the
 * whole way from hello to hang-up, with real TLS, real WebRTC and real VP8/Opus.
 */
class PhoneCallTest {
    private static final MediaSettings HEADLESS = new MediaSettings(CallKind.VIDEO, false, true, Optional.empty(),
            Optional.empty(), Optional.empty(), false, Sources.HEADLESS);

    @TempDir
    Path dir;
    private Phone anna;
    private Phone ben;

    /** Records what a side hears. */
    static final class Recorder implements Call.Listener, Phone.Listener {
        final List<Call.State> states = new CopyOnWriteArrayList<>();
        final List<ChatLine> chat = new CopyOnWriteArrayList<>();
        final AtomicInteger remoteFrames = new AtomicInteger();
        final AtomicInteger localFrames = new AtomicInteger();
        final AtomicReference<EndReason> ended = new AtomicReference<>();
        final AtomicReference<Call> incoming = new AtomicReference<>();
        final AtomicReference<BufferedImage> lastRemote = new AtomicReference<>();
        volatile boolean remoteCamera = true;
        volatile boolean remoteScreen;
        volatile boolean accept = true;

        @Override
        public void state(Call call, Call.State state) {
            states.add(state);
        }

        @Override
        public void ended(Call call, EndReason reason) {
            ended.set(reason);
        }

        @Override
        public void remoteFrame(BufferedImage frame) {
            remoteFrames.incrementAndGet();
            lastRemote.set(frame);
        }

        @Override
        public void localFrame(BufferedImage frame) {
            localFrames.incrementAndGet();
        }

        @Override
        public void chat(ChatLine line) {
            chat.add(line);
        }

        @Override
        public void remoteCamera(boolean on) {
            remoteCamera = on;
        }

        @Override
        public void remoteScreen(boolean on) {
            remoteScreen = on;
        }

        @Override
        public void incoming(Call call) {
            incoming.set(call);
            call.ring(this);
            if (accept) call.accept(true);
        }

        @Override
        public void found(NearbyPeer peer) {
        }

        @Override
        public void lost(PeerKey key) {
        }
    }

    @AfterEach
    void close() {
        if (anna != null) anna.close();
        if (ben != null) ben.close();
    }

    private Phone phone(String name, Recorder recorder) throws Exception {
        Phone p = new Phone(new IdentityStore(dir.resolve(name)), Discovery.none(), new GstMedia(), Instant::now);
        p.start(name, 0, () -> HEADLESS, recorder);
        return p;
    }

    @Test
    void aVideoCallFromHelloToHangUp() throws Exception {
        Recorder a = new Recorder(), b = new Recorder();
        anna = phone("anna", a);
        ben = phone("ben", b);
        Call call = anna.call(new Endpoint("127.0.0.1", ben.port()), Optional.of(ben.self()), "Ben", CallKind.VIDEO,
                HEADLESS, a).orElseThrow();
        waitFor("Anna's call is active", () -> call.state() == Call.State.ACTIVE);
        waitFor("Ben's call is active", () -> b.states.contains(Call.State.ACTIVE));
        waitFor("pictures both ways", () -> a.remoteFrames.get() > 15 && b.remoteFrames.get() > 15);
        assertTrue(a.localFrames.get() > 0, "self view");
        BufferedImage picture = a.lastRemote.get();
        assertEquals(1280, picture.getWidth());
        assertEquals(720, picture.getHeight());
        assertEquals("anna", b.incoming.get().peerName());
        assertEquals(anna.self(), b.incoming.get().peer().orElseThrow());

        call.sendChat("Hallo Ben");
        waitFor("chat arrives", () -> b.chat.size() == 1);
        assertEquals("Hallo Ben", b.chat.getFirst().text());
        assertTrue(a.chat.getFirst().mine());

        call.setCameraOn(false);
        waitFor("camera off arrives", () -> !b.remoteCamera);
        call.setScreenSharing(true);
        waitFor("screen arrives", () -> b.remoteScreen);
        int before = b.remoteFrames.get();
        waitFor("pictures go on while sharing", () -> b.remoteFrames.get() > before + 15);
        call.setScreenSharing(false);
        waitFor("screen stops", () -> !b.remoteScreen);

        call.hangUp();
        waitFor("Ben hears the end", () -> b.ended.get() != null);
        assertEquals(EndReason.HUNG_UP, a.ended.get());
        assertEquals(EndReason.REMOTE_HUNG_UP, b.ended.get());
        assertTrue(call.length().toMillis() > 0);
    }

    @Test
    void anAudioCall() throws Exception {
        Recorder a = new Recorder(), b = new Recorder();
        anna = phone("anna", a);
        ben = phone("ben", b);
        Call call = anna.call(new Endpoint("127.0.0.1", ben.port()), Optional.empty(), "Ben", CallKind.AUDIO,
                HEADLESS, a).orElseThrow();
        waitFor("active", () -> call.state() == Call.State.ACTIVE && b.states.contains(Call.State.ACTIVE));
        assertEquals(CallKind.AUDIO, b.incoming.get().kind());
        Thread.sleep(500);
        assertEquals(0, a.remoteFrames.get());
        b.incoming.get().hangUp();
        waitFor("Anna hears the end", () -> a.ended.get() == EndReason.REMOTE_HUNG_UP);
    }

    private static void waitFor(String what, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("Timed out waiting for: " + what);
    }
}
