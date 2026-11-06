package eu.amadeos.apps.call.service;

import eu.amadeos.apps.call.domain.PeerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovery through Avahi (mDNS/DNS-SD), the way printers and network drives are found: {@code avahi-publish}
 * announces this program as {@code _amadeos-call._tcp} with its name and key fingerprint in TXT records, and
 * {@code avahi-browse} reports the others as they come and go. Both run as long as the program does.
 */
public class AvahiDiscovery implements Discovery {
    private static final Logger LOG = LoggerFactory.getLogger(AvahiDiscovery.class);
    /** DNS-SD names and TXT entries are short. */
    static final int MAX_NAME = 60;

    private final Object lock = new Object();
    private Process publish;
    private Process browse;
    private PeerKey self;
    private int port;
    private boolean closed;
    /** The resolved entries per service name: one per network interface and IP version. */
    private final Map<String, List<AvahiBrowse.Resolved>> services = new HashMap<>();
    /** What the listener was last told per key, so that every key is found once and lost once. */
    private final Map<PeerKey, NearbyPeer> reported = new HashMap<>();

    @Override
    public void start(String name, PeerKey self, int port, Listener listener) {
        synchronized (lock) {
            this.self = self;
            this.port = port;
            publish(name, listener);
            try {
                browse = new ProcessBuilder("avahi-browse", "--parsable", "--resolve", "--no-db-lookup",
                        AvahiBrowse.SERVICE).redirectErrorStream(true).start();
            } catch (IOException e) {
                LOG.info("avahi-browse is missing: {}", e.getMessage());
                listener.unavailable("avahi-browse");
                return;
            }
            Process p = browse;
            Thread.ofVirtual().name("avahi-browse").start(() -> read(p, listener));
        }
    }

    private void publish(String name, Listener listener) {
        String shown = shorten(name);
        // The service name only has to be unique in the network; the name people see travels in the TXT record.
        String service = shown + " " + self.hex().substring(0, 8);
        try {
            publish = new ProcessBuilder("avahi-publish", "--service", service, AvahiBrowse.SERVICE,
                    Integer.toString(port), "name=" + shown, "id=" + self.hex(), "v=1")
                    .redirectErrorStream(true).start();
            Process p = publish;
            Thread.ofVirtual().name("avahi-publish").start(() -> {
                try (BufferedReader r = reader(p)) {
                    String line;
                    while ((line = r.readLine()) != null) LOG.debug("avahi-publish: {}", line);
                } catch (IOException ignored) {
                    // ends with the process
                }
            });
        } catch (IOException e) {
            LOG.info("avahi-publish is missing: {}", e.getMessage());
            listener.unavailable("avahi-publish");
        }
    }

    static String shorten(String name) {
        String n = name == null ? "" : name.strip().replaceAll("[\\p{Cntrl}\"]", "");
        return n.length() > MAX_NAME ? n.substring(0, MAX_NAME) : n;
    }

    private void read(Process p, Listener listener) {
        try (BufferedReader r = reader(p)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Failed")) {
                    LOG.info("avahi-browse: {}", line);
                    listener.unavailable(line);
                    continue;
                }
                AvahiBrowse.parse(line).ifPresent(event -> handle(event, listener));
            }
        } catch (IOException e) {
            LOG.debug("avahi-browse ended: {}", e.toString());
        }
    }

    void handle(AvahiBrowse.Event event, Listener listener) {
        List<Runnable> calls = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            switch (event) {
                case AvahiBrowse.Resolved r -> {
                    if (r.peer().key().equals(self)) return;
                    List<AvahiBrowse.Resolved> list = services.computeIfAbsent(r.service(), k -> new ArrayList<>());
                    list.removeIf(x -> x.ipv6() == r.ipv6() && x.peer().endpoint().equals(r.peer().endpoint()));
                    list.add(r);
                }
                case AvahiBrowse.Removed rm -> services.remove(rm.service());
            }
            Map<PeerKey, NearbyPeer> now = current();
            for (Map.Entry<PeerKey, NearbyPeer> e : now.entrySet()) {
                if (!e.getValue().equals(reported.get(e.getKey()))) calls.add(() -> listener.found(e.getValue()));
            }
            for (PeerKey gone : new ArrayList<>(reported.keySet())) {
                if (!now.containsKey(gone)) calls.add(() -> listener.lost(gone));
            }
            reported.clear();
            reported.putAll(now);
        }
        calls.forEach(Runnable::run);
    }

    /** One entry per key, IPv4 before IPv6 (link-local IPv6 needs the right interface). */
    private Map<PeerKey, NearbyPeer> current() {
        Map<PeerKey, NearbyPeer> map = new HashMap<>();
        Map<PeerKey, Boolean> v6 = new HashMap<>();
        for (List<AvahiBrowse.Resolved> list : services.values()) {
            for (AvahiBrowse.Resolved r : list) {
                PeerKey key = r.peer().key();
                if (!map.containsKey(key) || (v6.get(key) && !r.ipv6())) {
                    map.put(key, r.peer());
                    v6.put(key, r.ipv6());
                }
            }
        }
        return map;
    }

    @Override
    public void rename(String name) {
        synchronized (lock) {
            if (closed || self == null) return;
            if (publish != null) publish.destroy();
            publish(name, new Listener() {
                @Override
                public void found(NearbyPeer peer) {
                }

                @Override
                public void lost(PeerKey key) {
                }
            });
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            if (publish != null) publish.destroy();
            if (browse != null) browse.destroy();
        }
    }

    private static BufferedReader reader(Process p) {
        return new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
    }
}
