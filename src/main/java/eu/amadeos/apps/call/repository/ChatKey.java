package eu.amadeos.apps.call.repository;

import eu.amadeos.commons.desktop.PasswordStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The key of the chat history, kept in the login keyring (Secret Service through secret-tool) and made on first
 * use. Without a keyring that keeps it across logins there is no key, and the history stays off.
 */
public final class ChatKey implements Supplier<Optional<SecretKey>> {
    private static final Logger LOG = LoggerFactory.getLogger(ChatKey.class);
    static final String ACCOUNT = "chat-history-key";

    private final Supplier<PasswordStore> store;
    private Optional<SecretKey> key;

    /** @param store a new store each time, so that a check reads the keyring and not a cache */
    public ChatKey(Supplier<PasswordStore> store) {
        this.store = store;
    }

    @Override
    public synchronized Optional<SecretKey> get() {
        if (key == null) key = find();
        return key;
    }

    private Optional<SecretKey> find() {
        PasswordStore keyring = store.get();
        if (!keyring.isPersistent()) {
            LOG.info("No keyring: the chat history is not kept");
            return Optional.empty();
        }
        Optional<SecretKey> existing = keyring.lookup(ACCOUNT).flatMap(ChatKey::decode);
        if (existing.isPresent()) return existing;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String text = Base64.getEncoder().encodeToString(bytes);
        keyring.store(ACCOUNT, "amade-call chat history", text);
        // Only a key that the keyring really keeps is used: else the history could not be read after the next login.
        if (!store.get().lookup(ACCOUNT).map(text::equals).orElse(false)) {
            LOG.warn("The keyring does not keep the chat key: the chat history is not kept");
            return Optional.empty();
        }
        return decode(text);
    }

    static Optional<SecretKey> decode(String text) {
        try {
            byte[] bytes = Base64.getDecoder().decode(text.strip());
            return bytes.length == 32 ? Optional.of(new SecretKeySpec(bytes, "AES")) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
