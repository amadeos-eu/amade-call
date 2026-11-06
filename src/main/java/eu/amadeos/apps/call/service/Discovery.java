package eu.amadeos.apps.call.service;

import eu.amadeos.apps.call.domain.PeerKey;

/**
 * Finds the other amade-call programs in the local network and makes this one known there. The listener is
 * called on a background thread.
 */
public interface Discovery extends AutoCloseable {
    interface Listener {
        void found(NearbyPeer peer);

        void lost(PeerKey key);

        /** Discovery does not work (Avahi missing or not running); calls by address still do. */
        default void unavailable(String reason) {
        }
    }

    /** Announces this program and starts looking for others; {@code self} is left out of what is found. */
    void start(String name, PeerKey self, int port, Listener listener);

    /** Announces a new name. */
    void rename(String name);

    @Override
    void close();

    /** For computers without Avahi and for tests. */
    static Discovery none() {
        return new Discovery() {
            @Override
            public void start(String name, PeerKey self, int port, Listener listener) {
                listener.unavailable("off");
            }

            @Override
            public void rename(String name) {
            }

            @Override
            public void close() {
            }
        };
    }
}
