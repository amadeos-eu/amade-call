package eu.amadeos.apps.call.repository;

import eu.amadeos.apps.call.domain.CallKind;
import eu.amadeos.apps.call.domain.CallLink;
import eu.amadeos.apps.call.domain.PeerKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressBookTest {
    private static final PeerKey ANNA = new PeerKey("a".repeat(64));

    @TempDir
    Path dir;

    @Test
    void aLinkGoesIntoAnExistingContactAndTheRestStays() throws Exception {
        Path book = Files.createDirectories(dir.resolve("work"));
        Path card = book.resolve("x.vcf");
        Files.writeString(card, "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:x\r\nFN:Anna Berger\r\nTEL:+49 30 1234\r\n"
                + "NOTE:a long note that is folded over two\r\n  lines\r\nEND:VCARD\r\n");
        AddressBook contacts = new AddressBook(dir);
        AddressBook.Entry anna = contacts.contacts().getFirst();
        assertEquals("Anna Berger", anna.name());
        contacts.link(anna.file(), ANNA);
        String text = Files.readString(card);
        assertTrue(text.contains("TEL:+49 30 1234\r\n"));
        assertTrue(text.contains("IMPP:amadeos-call:" + ANNA.hex() + "\r\nEND:VCARD"));
        assertTrue(contacts.contacts().getFirst().links(ANNA));
    }

    @Test
    void aNewContactLandsInTheDefaultBook() throws Exception {
        AddressBook contacts = new AddressBook(dir);
        Path file = contacts.create("Berger, Anna", ANNA, Instant.EPOCH);
        assertEquals(dir.resolve("personal"), file.getParent());
        assertEquals("Berger, Anna", contacts.contacts().getFirst().name());
        assertTrue(Files.readString(file).contains("FN:Berger\\, Anna"));
        assertTrue(contacts.contacts().getFirst().links(ANNA));
    }

    @Test
    void linksAreRead() {
        assertEquals(Optional.of(new CallLink(ANNA, CallKind.AUDIO)), CallLink.parse("amadeos-call:" + ANNA.hex() + "?kind=audio"));
        assertEquals(Optional.of(new CallLink(ANNA, CallKind.VIDEO)), CallLink.parse("AMADEOS-CALL:" + ANNA.hex().toUpperCase()));
        assertEquals(Optional.of(new CallLink(ANNA, CallKind.CHAT)), CallLink.parse("amadeos-call://" + ANNA.hex() + "?kind=chat"));
        assertEquals(Optional.empty(), CallLink.parse("amadeos-call:nothex"));
        assertEquals(Optional.empty(), CallLink.parse("https://example.org"));
        assertEquals(List.of(), new AddressBook(dir.resolve("none")).contacts());
    }
}
