package eu.amadeos.apps.call.service;

import eu.amadeos.apps.call.domain.ChatFile;
import eu.amadeos.apps.call.domain.ChatLine;
import eu.amadeos.apps.call.protocol.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Pictures and files in the chat of one call. They travel over the call's TLS connection in pieces of 48 kB
 * between the other messages, so a chat line still gets through while a file is on its way, and picture and
 * sound (their own WebRTC stream) do not notice it. The receiving side writes into a {@code .part} file next to
 * the target, checks the SHA-256 at the end and only then gives the file its name.
 */
final class ChatFiles {
    private static final Logger LOG = LoggerFactory.getLogger(ChatFiles.class);
    /** Larger files are refused on both sides. */
    static final long MAX_SIZE = 2L * 1024 * 1024 * 1024;
    static final int PIECE = 48 * 1024;
    /** The window hears about progress every this many bytes. */
    private static final long PROGRESS_STEP = 512 * 1024;
    /** At most this many files arrive at the same time. */
    private static final int MAX_INCOMING = 8;

    /** Where a file from the other side goes, and what the chat shows of it. */
    interface Target {
        /** A free path for a file of this (already cleaned) name; empty refuses it. */
        Optional<Path> pathFor(String name);

        void show(ChatLine line);
    }

    private final Function<Signal, Boolean> send;
    private final Supplier<Instant> clock;
    private final Target target;
    private final Set<String> sending = ConcurrentHashMap.newKeySet();
    private final Map<String, Incoming> incoming = new ConcurrentHashMap<>();
    private volatile boolean closed;

    ChatFiles(Function<Signal, Boolean> send, Supplier<Instant> clock, Target target) {
        this.send = send;
        this.clock = clock;
        this.target = target;
    }

    // ---------------------------------------------------------------- sending

    /** Starts sending {@code file}; false when it cannot be read, is too large or the call is gone. */
    boolean send(Path file) {
        long size;
        try {
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) return false;
            size = Files.size(file);
        } catch (IOException e) {
            return false;
        }
        if (size > MAX_SIZE || closed) return false;
        String id = UUID.randomUUID().toString();
        String name = cleanName(file.getFileName().toString());
        ChatLine line = new ChatLine(true, name, clock.get(), ChatFile.running(id, name, size));
        sending.add(id);
        if (!send.apply(Signal.file(id, name, size))) {
            sending.remove(id);
            return false;
        }
        target.show(line);
        Thread.ofVirtual().name("call-file-out").start(() -> push(file, line));
        return true;
    }

    private void push(Path file, ChatLine line) {
        ChatFile f = line.file();
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest sha = sha256();
            byte[] piece = new byte[PIECE];
            long done = 0;
            long shown = 0;
            int n;
            while ((n = in.readNBytes(piece, 0, PIECE)) > 0) {
                if (!sending.contains(f.id())) throw new IOException("given up");
                sha.update(piece, 0, n);
                String data = Base64.getEncoder().encodeToString(n == PIECE ? piece : Arrays.copyOf(piece, n));
                if (!send.apply(Signal.fileData(f.id(), data))) throw new IOException("the call ended");
                done += n;
                if (done - shown >= PROGRESS_STEP) {
                    shown = done;
                    target.show(line.with(f.progress(done)));
                }
            }
            if (done != f.size()) throw new IOException("the file changed while it was sent");
            if (!send.apply(Signal.fileEnd(f.id(), HexFormat.of().formatHex(sha.digest())))) {
                throw new IOException("the call ended");
            }
            target.show(line.with(f.arrived(file)));
        } catch (IOException e) {
            LOG.info("Sending {} failed: {}", f.name(), e.getMessage());
            send.apply(Signal.fileCancel(f.id()));
            target.show(line.with(f.failed()));
        } finally {
            sending.remove(f.id());
        }
    }

    // ---------------------------------------------------------------- receiving

    private static final class Incoming {
        final ChatLine line;
        final Path part;
        final Path path;
        final OutputStream out;
        final MessageDigest sha = sha256();
        long received;
        long shown;

        Incoming(ChatLine line, Path part, Path path, OutputStream out) {
            this.line = line;
            this.part = part;
            this.path = path;
            this.out = out;
        }
    }

    /** One of the four file signals from the other side, in the order they came. */
    void onSignal(Signal signal) {
        String id = signal.text("id");
        switch (signal.type()) {
            case FILE -> start(id, signal.text("name"), signal.longNumber("size"));
            case FILE_DATA -> piece(id, signal.text("data"));
            case FILE_END -> end(id, signal.text("sha256"));
            case FILE_CANCEL -> {
                sending.remove(id);
                Incoming in = incoming.remove(id);
                if (in != null) fail(in);
            }
            default -> {
            }
        }
    }

    private void start(String id, String rawName, long size) {
        if (!id.matches("[A-Za-z0-9-]{1,64}") || incoming.containsKey(id)) return;
        String name = cleanName(rawName);
        ChatLine line = new ChatLine(false, name, clock.get(), ChatFile.running(id, name, Math.max(size, 0)));
        Optional<Path> path = size < 0 || size > MAX_SIZE || incoming.size() >= MAX_INCOMING || closed
                ? Optional.empty() : target.pathFor(name);
        if (path.isEmpty()) {
            send.apply(Signal.fileCancel(id));
            target.show(line.with(line.file().failed()));
            return;
        }
        Path part = path.get().resolveSibling(path.get().getFileName() + ".part");
        try {
            Files.createDirectories(part.getParent());
            incoming.put(id, new Incoming(line, part, path.get(), Files.newOutputStream(part)));
            target.show(line);
        } catch (IOException e) {
            LOG.info("Cannot receive {}: {}", name, e.toString());
            send.apply(Signal.fileCancel(id));
            target.show(line.with(line.file().failed()));
        }
    }

    private void piece(String id, String data) {
        Incoming in = incoming.get(id);
        if (in == null) return;
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            if (in.received + bytes.length > in.line.file().size()) throw new IOException("more than announced");
            in.out.write(bytes);
            in.sha.update(bytes);
            in.received += bytes.length;
            if (in.received - in.shown >= PROGRESS_STEP) {
                in.shown = in.received;
                target.show(in.line.with(in.line.file().progress(in.received)));
            }
        } catch (IOException | IllegalArgumentException e) {
            LOG.info("Receiving {} failed: {}", in.line.text(), e.toString());
            incoming.remove(id);
            send.apply(Signal.fileCancel(id));
            fail(in);
        }
    }

    private void end(String id, String sha256) {
        Incoming in = incoming.remove(id);
        if (in == null) return;
        try {
            in.out.close();
            String got = HexFormat.of().formatHex(in.sha.digest());
            if (in.received != in.line.file().size() || !got.equalsIgnoreCase(sha256)) {
                throw new IOException("the file arrived damaged");
            }
            Files.move(in.part, in.path, StandardCopyOption.ATOMIC_MOVE);
            target.show(in.line.with(in.line.file().arrived(in.path)));
        } catch (IOException e) {
            LOG.info("Receiving {} failed: {}", in.line.text(), e.toString());
            fail(in);
        }
    }

    private void fail(Incoming in) {
        try {
            in.out.close();
        } catch (IOException ignored) {
            // the part file goes anyway
        }
        try {
            Files.deleteIfExists(in.part);
        } catch (IOException e) {
            LOG.debug("Could not delete {}: {}", in.part, e.toString());
        }
        target.show(in.line.with(in.line.file().progress(in.received).failed()));
    }

    /** The call ended: files on their way fail, half-received ones are deleted. */
    void close() {
        closed = true;
        sending.clear();
        for (String id : Set.copyOf(incoming.keySet())) {
            Incoming in = incoming.remove(id);
            if (in != null) fail(in);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The last part of a name without folders, control characters or a leading dot; "file" when nothing is left. */
    static String cleanName(String name) {
        String n = name == null ? "" : name;
        n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1);
        n = n.replaceAll("[\\p{Cntrl}]", "").strip();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.length() > 120) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 && n.length() - dot <= 10 ? n.substring(dot) : "";
            n = n.substring(0, 120 - ext.length()) + ext;
        }
        return n.isBlank() ? "file" : n;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
