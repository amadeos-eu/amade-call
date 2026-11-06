package eu.amadeos.apps.call.protocol;

import eu.amadeos.apps.call.domain.PeerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSocket;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The TLS connection of one call. Each {@link Signal} travels as a frame: its length (four bytes) and its JSON
 * text in UTF-8. A reader thread hands incoming signals to the listener in the order they came.
 */
public class SignalChannel implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SignalChannel.class);
    /** Larger frames are a broken or hostile peer; an SDP offer is a few kilobytes. */
    static final int MAX_FRAME = 256 * 1024;

    private final SSLSocket socket;
    private final PeerKey peer;
    private final DataInputStream in;
    private final DataOutputStream out;
    private volatile boolean closed;

    /** Takes a socket whose handshake is done. */
    public SignalChannel(SSLSocket socket) throws IOException {
        this.socket = socket;
        this.peer = Tls.peerKey(socket.getSession());
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }

    /** Who is on the other end, by the key they proved in the handshake. */
    public PeerKey peer() {
        return peer;
    }

    /** The address of the other end, for the list of known people. */
    public String remoteHost() {
        return socket.getRemoteSocketAddress() instanceof InetSocketAddress a
                ? a.getAddress().getHostAddress() : "";
    }

    /** Reads one signal, waiting at most {@code timeoutMillis}; empty at the end of the connection. */
    public Optional<Signal> read(int timeoutMillis) throws IOException {
        int before = socket.getSoTimeout();
        socket.setSoTimeout(timeoutMillis);
        try {
            while (true) {
                Optional<String> frame = frame();
                if (frame.isEmpty()) return Optional.empty();
                Optional<Signal> signal = Signal.decode(frame.get());
                if (signal.isPresent()) return signal;
            }
        } finally {
            if (!socket.isClosed()) socket.setSoTimeout(before);
        }
    }

    /**
     * Reads the rest of the connection on a thread of its own: every signal goes to {@code onSignal}, the end
     * (closed by either side, or broken) to {@code onClosed} once.
     */
    public void listen(Consumer<Signal> onSignal, Runnable onClosed) {
        Thread.ofVirtual().name("call-signals").start(() -> {
            try {
                socket.setSoTimeout(0);
                while (!closed) {
                    Optional<String> frame = frame();
                    if (frame.isEmpty()) break;
                    Signal.decode(frame.get()).ifPresentOrElse(onSignal,
                            () -> LOG.debug("Skipping an unknown message from {}", peer.shortText()));
                }
            } catch (IOException e) {
                if (!closed) LOG.info("Call connection to {} broke: {}", peer.shortText(), e.toString());
            } catch (RuntimeException e) {
                LOG.warn("Handling a message from {} failed", peer.shortText(), e);
            } finally {
                close();
                onClosed.run();
            }
        });
    }

    // --8<-- [start:frame]
    private Optional<String> frame() throws IOException {
        int length;
        try {
            length = in.readInt();
        } catch (EOFException e) {
            return Optional.empty();
        }
        if (length < 0 || length > MAX_FRAME) throw new IOException("frame of " + length + " bytes");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return Optional.of(new String(bytes, StandardCharsets.UTF_8));
    }

    /** Sends a signal; false when the connection is gone. */
    public boolean send(Signal signal) {
        if (closed) return false;
        byte[] bytes = signal.encode().getBytes(StandardCharsets.UTF_8);
        try {
            synchronized (out) {
                out.writeInt(bytes.length);
                out.write(bytes);
                out.flush();
            }
            return true;
        } catch (IOException e) {
            LOG.debug("Could not send {} to {}: {}", signal.type(), peer.shortText(), e.toString());
            close();
            return false;
        }
    }
    // --8<-- [end:frame]

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            socket.close();
        } catch (IOException e) {
            LOG.debug("Closing the call connection: {}", e.toString());
        }
    }
}
