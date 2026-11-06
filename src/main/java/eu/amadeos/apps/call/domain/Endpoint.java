package eu.amadeos.apps.call.domain;

import java.util.Objects;
import java.util.Optional;

/** Where a computer takes calls: a host name or address and the port. */
public record Endpoint(String host, int port) {
    /** The port amade-call listens on when it is free. */
    public static final int DEFAULT_PORT = 47474;

    public Endpoint {
        Objects.requireNonNull(host, "host");
        host = host.strip();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.isEmpty()) throw new IllegalArgumentException("empty host");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
    }

    /**
     * What someone typed: {@code 192.168.1.20}, {@code anna-pc.local:47475}, {@code [fe80::1]:47474} or a bare
     * IPv6 address. Without a port, the default port.
     */
    public static Optional<Endpoint> parse(String text) {
        if (text == null) return Optional.empty();
        String t = text.strip();
        if (t.isEmpty() || t.contains(" ")) return Optional.empty();
        try {
            if (t.startsWith("[")) {
                int close = t.indexOf(']');
                if (close < 0) return Optional.empty();
                String host = t.substring(1, close);
                String rest = t.substring(close + 1);
                if (rest.isEmpty()) return Optional.of(new Endpoint(host, DEFAULT_PORT));
                if (!rest.startsWith(":")) return Optional.empty();
                return Optional.of(new Endpoint(host, Integer.parseInt(rest.substring(1))));
            }
            int colons = t.length() - t.replace(":", "").length();
            if (colons > 1) return Optional.of(new Endpoint(t, DEFAULT_PORT));
            if (colons == 1) {
                int at = t.indexOf(':');
                return Optional.of(new Endpoint(t.substring(0, at), Integer.parseInt(t.substring(at + 1))));
            }
            return Optional.of(new Endpoint(t, DEFAULT_PORT));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        String h = host.contains(":") ? "[" + host + "]" : host;
        return port == DEFAULT_PORT ? h : h + ":" + port;
    }
}
