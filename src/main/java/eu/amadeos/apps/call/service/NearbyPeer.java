package eu.amadeos.apps.call.service;

import eu.amadeos.apps.call.domain.Endpoint;
import eu.amadeos.apps.call.domain.PeerKey;

/** A computer that announces amade-call in the local network. */
public record NearbyPeer(PeerKey key, String name, Endpoint endpoint) {
}
