package eu.amadeos.apps.call.repository;

import com.google.inject.Inject;
import eu.amadeos.apps.call.domain.Endpoint;
import eu.amadeos.apps.call.domain.PeerKey;
import eu.amadeos.apps.call.domain.Person;
import eu.amadeos.commons.config.AppDirectories;
import eu.amadeos.commons.config.PropertiesFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * The people called before, by key, in {@code ~/.config/amadeos/call/people.properties}: the name they had at
 * the last call, the address they were reached at, and whether the safety code was compared with them.
 */
public class KnownPeers {
    public static final String FILE = "people.properties";

    private final Path file;
    private final Properties properties;

    @Inject
    public KnownPeers(AppDirectories directories) {
        this(directories.configFile(FILE));
    }

    public KnownPeers(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        this.properties = PropertiesFile.load(file);
    }

    public synchronized List<Person> all() {
        List<Person> people = new ArrayList<>();
        for (String name : properties.stringPropertyNames()) {
            if (!name.endsWith(".name")) continue;
            String hex = name.substring(0, name.length() - ".name".length());
            if (PeerKey.valid(hex)) get(new PeerKey(hex)).ifPresent(people::add);
        }
        people.sort(Comparator.comparing(Person::name, String.CASE_INSENSITIVE_ORDER));
        return people;
    }

    public synchronized Optional<Person> get(PeerKey key) {
        String name = properties.getProperty(key.hex() + ".name");
        if (name == null) return Optional.empty();
        Optional<Endpoint> endpoint = Optional.ofNullable(properties.getProperty(key.hex() + ".address"))
                .flatMap(Endpoint::parse);
        return Optional.of(new Person(key, name, endpoint, false,
                "true".equals(properties.getProperty(key.hex() + ".verified"))));
    }

    /** After a call: the name and address the person had; keeps whether they are verified. */
    public synchronized void remember(PeerKey key, String name, Optional<Endpoint> endpoint) {
        properties.setProperty(key.hex() + ".name", name == null || name.isBlank() ? key.shortText() : name.strip());
        endpoint.ifPresent(e -> properties.setProperty(key.hex() + ".address", e.toString()));
        save();
    }

    public synchronized boolean verified(PeerKey key) {
        return "true".equals(properties.getProperty(key.hex() + ".verified"));
    }

    public synchronized void setVerified(PeerKey key, boolean verified) {
        if (verified) {
            properties.setProperty(key.hex() + ".verified", "true");
        } else {
            properties.remove(key.hex() + ".verified");
        }
        save();
    }

    public synchronized void forget(PeerKey key) {
        properties.stringPropertyNames().stream().filter(n -> n.startsWith(key.hex() + "."))
                .forEach(properties::remove);
        save();
    }

    private void save() {
        PropertiesFile.store(file, properties, "amade-call: people called before, by key fingerprint");
    }
}
