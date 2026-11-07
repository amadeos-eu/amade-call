package eu.amadeos.apps.call.protocol;

import eu.amadeos.apps.call.domain.Endpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.util.function.Consumer;

/**
 * Waits for calls: a TLS server socket on the call port (47474, or a free one when that is taken), which
 * demands the caller's certificate too. Every connection that finishes the handshake goes to the handler as a
 * {@link SignalChannel}.
 */
public class CallServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(CallServer.class);
    private static final int HANDSHAKE_TIMEOUT = 10_000;

    private final SSLServerSocket server;
    private volatile boolean closed;

    private CallServer(SSLServerSocket server) {
        this.server = server;
    }

    /** Listens on {@code port}; 0, or a port that is taken, gives a free one. */
    public static CallServer open(SSLContext tls, int port) throws IOException {
        SSLServerSocket socket = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket();
        socket.setReuseAddress(true);
        try {
            socket.bind(new InetSocketAddress(port));
        } catch (BindException e) {
            if (port == 0) throw e;
            LOG.info("Port {} is taken; taking a free one", port);
            socket.bind(new InetSocketAddress(0));
        }
        socket.setNeedClientAuth(true);
        socket.setEnabledProtocols(new String[]{Tls.PROTOCOL});
        return new CallServer(socket);
    }

    public int port() {
        return server.getLocalPort();
    }

    /** Accepts connections on a thread of its own until {@link #close}. */
    public void start(Consumer<SignalChannel> handler) {
        Thread.ofVirtual().name("call-server").start(() -> {
            while (!closed) {
                try {
                    SSLSocket socket = (SSLSocket) server.accept();
                    Thread.ofVirtual().name("call-handshake").start(() -> handshake(socket, handler));
                } catch (IOException e) {
                    if (!closed) LOG.warn("Accepting a call failed", e);
                }
            }
        });
    }

    private static void handshake(SSLSocket socket, Consumer<SignalChannel> handler) {
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT);
            socket.setTcpNoDelay(true);
            socket.startHandshake();
            handler.accept(new SignalChannel(socket));
        } catch (IOException e) {
            LOG.info("A connection from {} did not finish the handshake: {}", socket.getRemoteSocketAddress(),
                    e.toString());
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    // --8<-- [start:dial]
    /** Opens the connection of an outgoing call. */
    public static SignalChannel dial(SSLContext tls, Endpoint endpoint, int timeoutMillis) throws IOException {
        SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket();
        try {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), timeoutMillis);
            socket.setEnabledProtocols(new String[]{Tls.PROTOCOL});
            socket.setSoTimeout(HANDSHAKE_TIMEOUT);
            socket.setTcpNoDelay(true);
            socket.startHandshake();
            return new SignalChannel(socket);
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }
    // --8<-- [end:dial]

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException e) {
            LOG.debug("Closing the call server: {}", e.toString());
        }
    }
}
