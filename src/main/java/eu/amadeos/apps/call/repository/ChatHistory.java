package eu.amadeos.apps.call.repository;

import eu.amadeos.apps.call.domain.ChatLine;
import eu.amadeos.apps.call.domain.PeerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The chat with each person, kept on this computer and encrypted: one file per person
 * ({@code <key fingerprint>.chat}) with AES-256-GCM. The key itself is not on the disk but in the login keyring
 * ({@link ChatKey}); without it nothing is read or written. At most {@link #KEEP} lines per person.
 */
public class ChatHistory {
    private static final Logger LOG = LoggerFactory.getLogger(ChatHistory.class);
    /** The first bytes of every file: name and version of the format. */
    private static final byte[] MAGIC = "ACHAT1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    public static final int KEEP = 2000;

    private final Path dir;
    private final Supplier<Optional<SecretKey>> key;
    private final SecureRandom random = new SecureRandom();

    /** @param key asked on first use, from a background thread; empty when there is no keyring */
    public ChatHistory(Path dir, Supplier<Optional<SecretKey>> key) {
        this.dir = dir;
        this.key = key;
    }

    /** Whether lines can be kept: the keyring gave a key. */
    public boolean available() {
        return key.get().isPresent();
    }

    /** The lines with this person, oldest first; empty when there are none or they cannot be read. */
    public synchronized List<ChatLine> load(PeerKey peer) {
        Optional<SecretKey> k = key.get();
        Path file = file(peer);
        if (k.isEmpty() || !Files.isRegularFile(file)) return List.of();
        try {
            return decode(decrypt(k.get(), Files.readAllBytes(file)));
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            LOG.warn("The chat with {} cannot be read: {}", peer.shortText(), e.toString());
            return List.of();
        }
    }

    /** Adds lines to the chat with this person; nothing happens without a key. */
    public synchronized void add(PeerKey peer, List<ChatLine> lines) {
        if (lines.isEmpty()) return;
        Optional<SecretKey> k = key.get();
        if (k.isEmpty()) return;
        List<ChatLine> all = new ArrayList<>(load(peer));
        all.addAll(lines);
        if (all.size() > KEEP) all = all.subList(all.size() - KEEP, all.size());
        try {
            Files.createDirectories(dir);
            restrict(dir, "rwx------");
            Path tmp = dir.resolve(file(peer).getFileName() + ".part");
            Files.write(tmp, encrypt(k.get(), encode(all)));
            restrict(tmp, "rw-------");
            Files.move(tmp, file(peer), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | GeneralSecurityException e) {
            LOG.warn("The chat with {} cannot be kept: {}", peer.shortText(), e.toString());
        }
    }

    /** Forgets the chat with this person. */
    public synchronized void delete(PeerKey peer) {
        try {
            Files.deleteIfExists(file(peer));
        } catch (IOException e) {
            LOG.warn("The chat with {} cannot be deleted: {}", peer.shortText(), e.toString());
        }
    }

    public synchronized boolean has(PeerKey peer) {
        return Files.isRegularFile(file(peer));
    }

    Path file(PeerKey peer) {
        return dir.resolve(peer.hex() + ".chat");
    }

    // ---------------------------------------------------------------- format

    /** One line per chat line: time in milliseconds, 1 for mine or 0, the text with \\, \n and \t escaped. */
    static byte[] encode(List<ChatLine> lines) {
        StringBuilder b = new StringBuilder();
        for (ChatLine l : lines) {
            b.append(l.at().toEpochMilli()).append('\t').append(l.mine() ? '1' : '0').append('\t')
                    .append(l.text().replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t"))
                    .append('\n');
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    static List<ChatLine> decode(byte[] bytes) {
        List<ChatLine> lines = new ArrayList<>();
        for (String row : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            String[] f = row.split("\t", 3);
            if (f.length < 3) continue;
            lines.add(new ChatLine("1".equals(f[1]), unescape(f[2]), Instant.ofEpochMilli(Long.parseLong(f[0]))));
        }
        return lines;
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private byte[] encrypt(SecretKey k, byte[] plain) throws GeneralSecurityException {
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(MAGIC);
        byte[] sealed = cipher.doFinal(plain);
        return ByteBuffer.allocate(MAGIC.length + IV_LENGTH + sealed.length).put(MAGIC).put(iv).put(sealed).array();
    }

    private static byte[] decrypt(SecretKey k, byte[] file) throws GeneralSecurityException {
        if (file.length < MAGIC.length + IV_LENGTH || !Arrays.equals(Arrays.copyOf(file, MAGIC.length), MAGIC)) {
            throw new GeneralSecurityException("not a chat file");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, k,
                new GCMParameterSpec(TAG_BITS, file, MAGIC.length, IV_LENGTH));
        cipher.updateAAD(MAGIC);
        return cipher.doFinal(file, MAGIC.length + IV_LENGTH, file.length - MAGIC.length - IV_LENGTH);
    }

    private static void restrict(Path path, String permissions) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (IOException | UnsupportedOperationException e) {
            LOG.debug("No permissions for {}: {}", path, e.toString());
        }
    }
}
