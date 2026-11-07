package eu.amadeos.apps.call.repository;

import eu.amadeos.apps.call.domain.CallLink;
import eu.amadeos.apps.call.domain.PeerKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The address books of amade-contacts as far as Calls needs them: one folder per book in
 * {@code ~/.local/share/amadeos/contacts}, one {@code .vcf} per contact. Calls adds its link
 * ({@code IMPP:amadeos-call:<key>}) to a contact, or makes a new contact with it; Contacts then offers to call.
 * Everything else in the cards stays as it is.
 */
public class AddressBook {
    /** The book amade-contacts makes first. */
    public static final String DEFAULT_BOOK = "personal";
    private static final DateTimeFormatter REV = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** One contact: its file, its name and whether it already has the link to {@code key}. */
    public record Entry(Path file, String name, List<String> links) {
        public boolean links(PeerKey key) {
            return links.stream().anyMatch(l -> l.equalsIgnoreCase(CallLink.of(key)));
        }
    }

    private final Path root;

    public AddressBook(Path root) {
        this.root = root;
    }

    /** All contacts, by name. */
    public List<Entry> contacts() {
        List<Entry> list = new ArrayList<>();
        if (!Files.isDirectory(root)) return list;
        try (Stream<Path> books = Files.list(root)) {
            for (Path book : books.filter(Files::isDirectory).toList()) {
                try (Stream<Path> cards = Files.list(book)) {
                    for (Path f : cards.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vcf")).toList()) {
                        read(f).ifPresent(list::add);
                    }
                }
            }
        } catch (IOException e) {
            return list;
        }
        list.sort(Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return list;
    }

    private static java.util.Optional<Entry> read(Path file) {
        try {
            String name = "";
            List<String> links = new ArrayList<>();
            for (String line : unfold(Files.readString(file, StandardCharsets.UTF_8))) {
                String upper = line.toUpperCase(Locale.ROOT);
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String prop = upper.substring(0, colon).split(";")[0];
                // A group prefix such as "item1.IMPP" counts too.
                if (prop.contains(".")) prop = prop.substring(prop.lastIndexOf('.') + 1);
                String value = line.substring(colon + 1);
                if (prop.equals("FN") && name.isEmpty()) name = unescape(value);
                if (prop.equals("IMPP") && value.regionMatches(true, 0, CallLink.SCHEME + ":", 0, CallLink.SCHEME.length() + 1)) {
                    links.add(value.strip());
                }
            }
            return java.util.Optional.of(new Entry(file, name.isBlank() ? file.getFileName().toString() : name, links));
        } catch (IOException | RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    /** Adds the link to an existing contact, just before its {@code END:VCARD}. */
    public void link(Path file, PeerKey key) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        int end = text.toUpperCase(Locale.ROOT).lastIndexOf("END:VCARD");
        if (end < 0) throw new IOException("not a vCard: " + file);
        String before = text.substring(0, end);
        if (!before.endsWith("\n")) before += "\r\n";
        write(file, before + "IMPP:" + CallLink.of(key) + "\r\n" + text.substring(end));
    }

    /** A new contact in the default book with only the name and the link; returns its file. */
    public Path create(String name, PeerKey key, Instant now) throws IOException {
        Path book = root.resolve(DEFAULT_BOOK);
        Files.createDirectories(book);
        String uid = UUID.randomUUID().toString();
        Path file = book.resolve(uid + ".vcf");
        write(file, "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:urn:uuid:" + uid + "\r\nFN:" + escape(name) + "\r\nIMPP:"
                + CallLink.of(key) + "\r\nREV:" + REV.format(now) + "\r\nEND:VCARD\r\n");
        return file;
    }

    private static void write(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    static List<String> unfold(String text) {
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && !lines.isEmpty()) {
                lines.set(lines.size() - 1, lines.getLast() + raw.substring(1));
            } else {
                lines.add(raw);
            }
        }
        return lines;
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n");
    }

    static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }
}
