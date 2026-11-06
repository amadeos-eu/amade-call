package eu.amadeos.apps.call.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Only one amade-call per user: it owns the call port and the network announcement. A second start (from the
 * menu while the first waits for calls in the background) sends its wish over a Unix socket in
 * {@code $XDG_RUNTIME_DIR} to the first one, which shows its window, and ends.
 */
public class SingleInstance implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SingleInstance.class);

    private final Path socket;
    private ServerSocketChannel server;

    public SingleInstance(Path socket) {
        this.socket = socket;
    }

    public static Path defaultSocket() {
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        Path dir = runtime == null || runtime.isBlank() ? Path.of(System.getProperty("java.io.tmpdir")) : Path.of(runtime);
        return dir.resolve("amade-call-" + System.getProperty("user.name") + ".sock");
    }

    /** Hands {@code message} to a running instance; false when there is none. */
    public boolean sendToRunning(String message) {
        if (!Files.exists(socket)) return false;
        try (SocketChannel ch = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            ch.write(ByteBuffer.wrap((message + "\n").getBytes(StandardCharsets.UTF_8)));
            return true;
        } catch (IOException e) {
            LOG.debug("No running instance at {}: {}", socket, e.toString());
            return false;
        }
    }

    /** Becomes the running instance; every message of a later start goes to {@code onMessage}. */
    public void listen(Consumer<String> onMessage) throws IOException {
        Files.deleteIfExists(socket);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        ServerSocketChannel s = server;
        Thread.ofVirtual().name("single-instance").start(() -> {
            while (s.isOpen()) {
                try (SocketChannel ch = s.accept()) {
                    ByteBuffer buffer = ByteBuffer.allocate(4096);
                    while (ch.read(buffer) > 0 && buffer.hasRemaining()) {
                        // read until the sender closes
                    }
                    buffer.flip();
                    onMessage.accept(StandardCharsets.UTF_8.decode(buffer).toString().strip());
                } catch (IOException e) {
                    if (s.isOpen()) LOG.debug("single-instance socket: {}", e.toString());
                }
            }
        });
    }

    @Override
    public void close() {
        try {
            if (server != null) server.close();
            Files.deleteIfExists(socket);
        } catch (IOException e) {
            LOG.debug("Closing {}: {}", socket, e.toString());
        }
    }
}
