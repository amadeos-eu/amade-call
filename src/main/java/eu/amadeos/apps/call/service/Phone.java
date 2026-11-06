package eu.amadeos.apps.call.service;

import eu.amadeos.apps.call.domain.CallKind;
import eu.amadeos.apps.call.domain.Endpoint;
import eu.amadeos.apps.call.domain.PeerKey;
import eu.amadeos.apps.call.media.MediaFactory;
import eu.amadeos.apps.call.media.MediaSettings;
import eu.amadeos.apps.call.protocol.CallServer;
import eu.amadeos.apps.call.protocol.Signal;
import eu.amadeos.apps.call.protocol.SignalChannel;
import eu.amadeos.apps.call.protocol.Tls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * The telephone of this computer: its key, the server that takes calls, the network announcement and the one
 * call that may run at a time. A second caller hears "busy".
 */
public class Phone implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(Phone.class);
    private static final int HELLO_TIMEOUT_MILLIS = 10_000;

    /** What the main window hears from the phone; called on background threads. */
    public interface Listener extends Discovery.Listener {
        /** A call rings; answer it with {@link Call#ring}, then accept or decline. */
        void incoming(Call call);
    }

    private final IdentityStore identities;
    private final Discovery discovery;
    private final MediaFactory media;
    private final Supplier<Instant> clock;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("call-timers").factory());
    private SSLContext tls;
    private PeerKey self;
    private CallServer server;
    private Call current;
    private String name;

    public Phone(IdentityStore identities, Discovery discovery, MediaFactory media, Supplier<Instant> clock) {
        this.identities = identities;
        this.discovery = discovery;
        this.media = media;
        this.clock = clock;
    }

    /** Makes or loads the key, opens the call port and announces it. */
    public synchronized void start(String myName, int port, Supplier<MediaSettings> settings, Listener listener)
            throws IOException {
        KeyStore store = identities.load();
        try {
            tls = Tls.context(store, identities.password());
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS could not be set up", e);
        }
        self = IdentityStore.key(store);
        name = myName;
        server = CallServer.open(tls, port);
        server.start(channel -> answer(channel, settings, listener));
        discovery.start(myName, self, server.port(), listener);
        LOG.info("Waiting for calls on port {} as {}", server.port(), self.shortText());
    }

    // --8<-- [start:answer]
    private void answer(SignalChannel channel, Supplier<MediaSettings> settings, Listener listener) {
        Optional<Signal> hello;
        try {
            hello = channel.read(HELLO_TIMEOUT_MILLIS);
        } catch (IOException e) {
            LOG.info("No hello from {}: {}", channel.remoteHost(), e.toString());
            channel.close();
            return;
        }
        if (hello.isEmpty() || hello.get().type() != Signal.Type.HELLO) {
            channel.close();
            return;
        }
        Call call;
        synchronized (this) {
            if (current != null && current.live()) {
                LOG.info("{} calls while another call runs: busy", hello.get().text("name"));
                Call.busy(channel, name);
                return;
            }
            Optional<Endpoint> back = Endpoint.parse(channel.remoteHost());
            call = Call.incoming(channel, hello.get(), name, back, media, settings.get(), timers, clock);
            current = call;
        }
        listener.incoming(call);
    }
    // --8<-- [end:answer]

    /** Calls someone; empty while another call runs. */
    public synchronized Optional<Call> call(Endpoint endpoint, Optional<PeerKey> expected, String peerName, CallKind kind,
                                            MediaSettings settings, Call.Listener listener) {
        if (current != null && current.live()) return Optional.empty();
        current = Call.dial(endpoint, expected, peerName, kind, name,
                (e, timeout) -> CallServer.dial(tls, e, timeout), media, settings, timers, clock, listener);
        return Optional.of(current);
    }

    public synchronized Optional<Call> current() {
        return current != null && current.live() ? Optional.of(current) : Optional.empty();
    }

    public synchronized PeerKey self() {
        return self;
    }

    public synchronized int port() {
        return server == null ? 0 : server.port();
    }

    public synchronized void rename(String newName) {
        name = newName;
        discovery.rename(newName);
    }

    @Override
    public void close() {
        Call call;
        synchronized (this) {
            call = current;
        }
        if (call != null && call.live()) call.hangUp();
        discovery.close();
        if (server != null) server.close();
        timers.shutdownNow();
    }
}
