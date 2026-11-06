package eu.amadeos.apps.call.protocol;

import eu.amadeos.apps.call.domain.Endpoint;
import eu.amadeos.apps.call.domain.PeerKey;
import eu.amadeos.apps.call.service.IdentityStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two TLS ends on this computer: who is who, frames both ways, and frames that are too big. */
class SignalChannelTest {
    @TempDir
    static Path dir;
    static SSLContext anna;
    static SSLContext ben;
    static PeerKey annaKey;
    static PeerKey benKey;

    @BeforeAll
    static void keys() throws Exception {
        IdentityStore a = new IdentityStore(dir.resolve("anna"));
        IdentityStore b = new IdentityStore(dir.resolve("ben"));
        KeyStore ka = a.load();
        KeyStore kb = b.load();
        anna = Tls.context(ka, a.password());
        ben = Tls.context(kb, b.password());
        annaKey = IdentityStore.key(ka);
        benKey = IdentityStore.key(kb);
    }

    @Test
    void bothSidesSeeTheOthersKeyAndSignalsTravel() throws Exception {
        BlockingQueue<SignalChannel> accepted = new ArrayBlockingQueue<>(1);
        try (CallServer server = CallServer.open(ben, 0)) {
            server.start(accepted::add);
            try (SignalChannel caller = CallServer.dial(anna, new Endpoint("127.0.0.1", server.port()), 5000)) {
                SignalChannel callee = accepted.poll(10, TimeUnit.SECONDS);
                assertNotNull(callee);
                assertEquals(benKey, caller.peer());
                assertEquals(annaKey, callee.peer());
                assertEquals("127.0.0.1", callee.remoteHost());

                assertTrue(caller.send(Signal.chat("Grüß dich ☺")));
                Optional<Signal> got = callee.read(5000);
                assertEquals(Signal.Type.CHAT, got.orElseThrow().type());
                assertEquals(Signal.chat("Grüß dich ☺"), got.get());

                List<Signal> heard = new CopyOnWriteArrayList<>();
                CountDownLatch closed = new CountDownLatch(1);
                caller.listen(heard::add, closed::countDown);
                callee.send(Signal.camera(false));
                callee.send(Signal.bye());
                callee.close();
                assertTrue(closed.await(10, TimeUnit.SECONDS));
                assertEquals(List.of(Signal.camera(false), Signal.bye()), heard);
                assertTrue(caller.isClosed());
                assertTrue(!caller.send(Signal.bye()));
            }
        }
    }

    @Test
    void aTakenPortGivesAFreeOne() throws Exception {
        try (CallServer first = CallServer.open(ben, 0); CallServer second = CallServer.open(ben, first.port())) {
            assertNotEquals(first.port(), second.port());
        }
    }

    @Test
    void anOversizedFrameEndsTheConnection() throws Exception {
        BlockingQueue<SignalChannel> accepted = new ArrayBlockingQueue<>(1);
        try (CallServer server = CallServer.open(ben, 0)) {
            server.start(accepted::add);
            SSLSocket raw = (SSLSocket) anna.getSocketFactory().createSocket("127.0.0.1", server.port());
            raw.setEnabledProtocols(new String[]{Tls.PROTOCOL});
            raw.startHandshake();
            SignalChannel callee = accepted.poll(10, TimeUnit.SECONDS);
            assertNotNull(callee);
            CountDownLatch closed = new CountDownLatch(1);
            List<Signal> heard = new CopyOnWriteArrayList<>();
            callee.listen(heard::add, closed::countDown);
            DataOutputStream out = new DataOutputStream(raw.getOutputStream());
            out.writeInt(SignalChannel.MAX_FRAME + 1);
            out.flush();
            assertTrue(closed.await(10, TimeUnit.SECONDS));
            assertTrue(heard.isEmpty());
            raw.close();
        }
    }

    @Test
    void unknownMessagesAreSkipped() throws Exception {
        BlockingQueue<SignalChannel> accepted = new ArrayBlockingQueue<>(1);
        try (CallServer server = CallServer.open(ben, 0)) {
            server.start(accepted::add);
            SSLSocket raw = (SSLSocket) anna.getSocketFactory().createSocket("127.0.0.1", server.port());
            raw.setEnabledProtocols(new String[]{Tls.PROTOCOL});
            raw.startHandshake();
            SignalChannel callee = accepted.poll(10, TimeUnit.SECONDS);
            assertNotNull(callee);
            DataOutputStream out = new DataOutputStream(raw.getOutputStream());
            for (String text : List.of("{\"type\":\"teleport\"}", Signal.bye().encode())) {
                byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            out.flush();
            assertEquals(Signal.bye(), callee.read(5000).orElseThrow());
            raw.close();
            assertTrue(callee.read(5000).isEmpty());
            callee.close();
        }
    }
}
