package eu.amadeos.apps.call.service;

import eu.amadeos.apps.call.domain.Endpoint;
import eu.amadeos.apps.call.domain.PeerKey;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the lines of {@code avahi-browse --parsable --resolve --no-db-lookup _amadeos-call._tcp}:
 * <pre>
 * +;eth0;IPv4;Anna\032Berger;_amadeos-call._tcp;local
 * =;eth0;IPv4;Anna\032Berger;_amadeos-call._tcp;local;anna-pc.local;192.168.1.20;47474;"name=Anna Berger" "id=3f2a..."
 * -;eth0;IPv4;Anna\032Berger;_amadeos-call._tcp;local
 * </pre>
 * {@code +} is a new service, {@code =} the resolved one with address, port and TXT records, {@code -} one that
 * left. Service names escape special characters as a backslash and three decimal digits.
 */
public final class AvahiBrowse {
    public static final String SERVICE = "_amadeos-call._tcp";

    /** What one line says. */
    public sealed interface Event permits Resolved, Removed {
    }

    /** A service with address; {@code service} identifies it in later lines of the same interface. */
    public record Resolved(String service, boolean ipv6, NearbyPeer peer) implements Event {
    }

    public record Removed(String service) implements Event {
    }

    private AvahiBrowse() {
    }

    public static Optional<Event> parse(String line) {
        if (line == null || line.length() < 2 || line.charAt(1) != ';') return Optional.empty();
        List<String> fields = split(line);
        if (fields.size() < 6 || !SERVICE.equals(fields.get(4))) return Optional.empty();
        String service = unescape(fields.get(3));
        switch (fields.get(0)) {
            case "-":
                return Optional.of(new Removed(service));
            case "=":
                if (fields.size() < 10) return Optional.empty();
                Map<String, String> txt = txt(fields.get(9));
                String id = txt.getOrDefault("id", "");
                if (!PeerKey.valid(id)) return Optional.empty();
                int port;
                try {
                    port = Integer.parseInt(fields.get(8));
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
                String address = fields.get(7);
                boolean ipv6 = "IPv6".equals(fields.get(2));
                // A link-local IPv6 address needs the interface to be reachable.
                if (ipv6 && address.toLowerCase(java.util.Locale.ROOT).startsWith("fe80") && !address.contains("%")) {
                    address = address + "%" + fields.get(1);
                }
                String name = txt.getOrDefault("name", service);
                try {
                    return Optional.of(new Resolved(service, ipv6,
                            new NearbyPeer(new PeerKey(id), name, new Endpoint(address, port))));
                } catch (IllegalArgumentException e) {
                    return Optional.empty();
                }
            default:
                return Optional.empty();
        }
    }

    /** Splits on semicolons that are not escaped. */
    static List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                current.append(c).append(line.charAt(++i));
            } else if (c == ';') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    /** {@code Anna\032Berger} gives "Anna Berger"; the digits are byte values of UTF-8. */
    static String unescape(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 3 < text.length() && text.substring(i + 1, i + 4).matches("\\d{3}")) {
                bytes.write(Integer.parseInt(text.substring(i + 1, i + 4)));
                i += 3;
            } else if (c == '\\' && i + 1 < text.length()) {
                bytes.writeBytes(String.valueOf(text.charAt(++i)).getBytes(StandardCharsets.UTF_8));
            } else {
                bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** {@code "name=Anna Berger" "id=3f2a..."} as a map. */
    static Map<String, String> txt(String field) {
        Map<String, String> map = new LinkedHashMap<>();
        int i = 0;
        while (i < field.length()) {
            int open = field.indexOf('"', i);
            if (open < 0) break;
            StringBuilder value = new StringBuilder();
            int j = open + 1;
            while (j < field.length() && field.charAt(j) != '"') {
                if (field.charAt(j) == '\\' && j + 1 < field.length()) j++;
                value.append(field.charAt(j));
                j++;
            }
            String entry = value.toString();
            int eq = entry.indexOf('=');
            if (eq > 0) map.putIfAbsent(entry.substring(0, eq), entry.substring(eq + 1));
            i = j + 1;
        }
        return map;
    }
}
