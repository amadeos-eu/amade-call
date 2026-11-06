package eu.amadeos.apps.call.service;

import eu.amadeos.apps.call.domain.PeerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The key of this user on this computer: an Ed25519 key pair with a self-signed certificate in
 * {@code ~/.local/share/amadeos/call/identity.p12}, made with the JDK's {@code keytool} on the first start.
 * Its fingerprint is how other computers know this one ({@link PeerKey}).
 *
 * <p>The file is readable by the user only; the keystore password is a fixed word, because the file
 * permissions are what protect it (like an SSH key without passphrase). Deleting the file makes a new key at
 * the next start: everyone then sees a new, not yet compared safety code.
 */
public class IdentityStore {
    private static final Logger LOG = LoggerFactory.getLogger(IdentityStore.class);
    public static final String FILE = "identity.p12";
    static final String ALIAS = "amade-call";
    static final char[] PASSWORD = "amade-call".toCharArray();

    private final Path file;

    public IdentityStore(Path directory) {
        this.file = directory.resolve(FILE);
    }

    public Path file() {
        return file;
    }

    /** The keystore, made first if there is none. */
    public KeyStore load() throws IOException {
        if (!Files.isRegularFile(file)) create();
        try (InputStream in = Files.newInputStream(file)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, PASSWORD);
            if (store.getCertificate(ALIAS) == null) throw new IOException(file + " holds no key " + ALIAS);
            return store;
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not read " + file, e);
        }
    }

    public char[] password() {
        return PASSWORD.clone();
    }

    public static PeerKey key(KeyStore store) {
        try {
            Certificate certificate = store.getCertificate(ALIAS);
            return PeerKey.of(certificate.getPublicKey());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void create() throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(FILE + ".new");
        Files.deleteIfExists(temp);
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        List<String> command = List.of(keytool.toString(), "-genkeypair", "-alias", ALIAS, "-keyalg", "Ed25519",
                "-dname", "CN=amade-call", "-validity", "36500", "-storetype", "PKCS12",
                "-keystore", temp.toString(), "-storepass", new String(PASSWORD));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new IOException("keytool could not make the key: " + output.strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while making the key", e);
        }
        try {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            LOG.debug("No POSIX permissions for {}", temp);
        }
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        LOG.info("Made a new call key in {}", file);
    }
}
