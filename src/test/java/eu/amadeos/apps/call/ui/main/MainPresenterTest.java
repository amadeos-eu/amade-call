package eu.amadeos.apps.call.ui.main;

import eu.amadeos.apps.call.domain.CallKind;
import eu.amadeos.apps.call.domain.CallRecord;
import eu.amadeos.apps.call.domain.PeerKey;
import eu.amadeos.apps.call.domain.Person;
import eu.amadeos.apps.call.domain.SafetyCode;
import eu.amadeos.apps.call.media.Devices;
import eu.amadeos.apps.call.service.Ringer;
import eu.amadeos.commons.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static eu.amadeos.apps.call.Wait.until;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two whole programs without windows: real phones over localhost with TLS, fake media and fake views. Every
 * presenter call runs on its own "event thread", like in the program.
 */
class MainPresenterTest {
    private static final Messages MSG = Messages.load(MainPresenter.class, "eu.amadeos.apps.call.messages", Locale.ENGLISH);

    @TempDir
    Path dir;
    private TestSide anna;
    private TestSide ben;

    @BeforeEach
    void start() throws Exception {
        anna = new TestSide(dir.resolve("anna"), "Anna");
        ben = new TestSide(dir.resolve("ben"), "Ben");
        anna.start();
        ben.start();
    }

    @AfterEach
    void stop() throws Exception {
        anna.shutdown();
        ben.shutdown();
    }

    @Test
    void aCallFromAddressToHangUpLeavesBothInTheLists() throws Exception {
        Calling c = annaCallsBen(CallKind.VIDEO);
        ben.on(() -> ben.presenter.accept(true));
        until("both active", () -> c.annaWindow.active && ben.lastWindow().active);
        TestSide.FakeView.Window benWindow = ben.lastWindow();
        assertTrue(anna.ringer.contains(Ringer.Tone.RINGBACK));
        assertTrue(ben.ringer.contains(Ringer.Tone.RING));
        until("pictures", () -> c.annaWindow.remoteFrames > 0 && benWindow.remoteFrames > 0);

        anna.on(() -> anna.presenter.sendChat("Hallo Ben"));
        until("chat", () -> benWindow.chat.size() == 1);
        assertEquals("Hallo Ben", benWindow.chat.getFirst().text());
        assertFalse(benWindow.chat.getFirst().mine());

        anna.on(() -> anna.presenter.toggleMicrophone());
        until("muted", () -> benWindow.remoteMuted);
        anna.on(() -> anna.presenter.toggleCamera());
        until("camera off", () -> !benWindow.remoteCamera);
        assertEquals(List.of(false, false, false, true), c.annaWindow.switches.getLast());
        anna.on(() -> anna.presenter.toggleScreen());
        until("screen", () -> benWindow.remoteScreen);

        ben.on(() -> ben.presenter.hangUp());
        until("both ended", () -> c.annaWindow.ended != null && benWindow.ended != null);
        assertEquals(MSG.get("ended.remote-hung-up"), c.annaWindow.ended);
        assertEquals(MSG.get("ended.hung-up"), benWindow.ended);

        until("logged", () -> anna.log.all().size() == 1 && ben.log.all().size() == 1);
        assertEquals(CallRecord.Direction.OUTGOING, anna.log.all().getFirst().direction());
        assertEquals(CallRecord.Direction.INCOMING, ben.log.all().getFirst().direction());
        Person annaSeen = ben.known.all().getFirst();
        assertEquals("Anna", annaSeen.name());
        Person benSeen = anna.known.all().getFirst();
        assertEquals("Ben", benSeen.name());
        assertEquals(ben.phone.port(), benSeen.endpoint().orElseThrow().port());
        until("Ben listed", () -> anna.view.people.stream().anyMatch(p -> p.name().equals("Ben")));
    }

    @Test
    void picturesAndFilesGoThroughTheChat() throws Exception {
        Calling c = annaCallsBen(CallKind.CHAT);
        ben.on(() -> ben.presenter.accept(true));
        until("both open", () -> c.annaWindow.active && ben.lastWindow().active);
        java.nio.file.Path photo = dir.resolve("anna").resolve("floor plan.png");
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(40, 30,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(image, "png", photo.toFile());
        byte[] big = new byte[1_300_000];
        new java.util.Random(7).nextBytes(big);
        java.nio.file.Path data = java.nio.file.Files.write(dir.resolve("anna").resolve("data.dat"), big);
        anna.on(() -> anna.presenter.sendFiles(List.of(photo, data, dir.resolve("anna").resolve("missing.txt"))));

        java.nio.file.Path inbox = dir.resolve("ben").resolve("Downloads").resolve("amade-call");
        until("both arrived", () -> java.nio.file.Files.exists(inbox.resolve("floor plan.png"))
                && java.nio.file.Files.exists(inbox.resolve("data.dat")));
        assertArrayEquals(big, java.nio.file.Files.readAllBytes(inbox.resolve("data.dat")));
        assertArrayEquals(java.nio.file.Files.readAllBytes(photo),
                java.nio.file.Files.readAllBytes(inbox.resolve("floor plan.png")));
        until("Ben sees them done", () -> done(ben.lastWindow(), "floor plan.png") && done(ben.lastWindow(), "data.dat"));
        until("Anna sees them done", () -> done(c.annaWindow, "floor plan.png") && done(c.annaWindow, "data.dat"));
        assertTrue(c.annaWindow.chat.stream().anyMatch(l -> l.file() != null
                && l.file().status() == eu.amadeos.apps.call.domain.ChatFile.Status.RUNNING), "progress shown");
        until("the missing one is reported", () -> c.annaWindow.infos.size() == 1);
        assertTrue(c.annaWindow.infos.getFirst().startsWith("missing.txt cannot be sent"), c.annaWindow.infos.toString());

        // The same name again does not overwrite the first one.
        anna.on(() -> anna.presenter.sendFiles(List.of(photo)));
        until("second copy", () -> java.nio.file.Files.exists(inbox.resolve("floor plan (2).png")));
        assertFalse(java.nio.file.Files.list(inbox).anyMatch(p -> p.toString().endsWith(".part")));

        ben.on(() -> ben.presenter.openFile(inbox.resolve("data.dat")));
        until("opened", () -> ben.opened.size() == 1);
        assertEquals(inbox.resolve("data.dat"), ben.opened.getFirst());
        java.nio.file.Path script = java.nio.file.Files.writeString(inbox.resolve("run.sh"), "echo hi");
        ben.on(() -> ben.presenter.openFile(script));
        until("folder opened", () -> ben.opened.size() == 2);
        assertEquals(inbox, ben.opened.get(1), "a received script is not started");

        ben.on(() -> ben.presenter.hangUp());
        until("logged", () -> anna.log.all().size() == 1 && ben.log.all().size() == 1);
    }

    private static boolean done(TestSide.FakeView.Window w, String name) {
        return w.chat.stream().anyMatch(l -> l.file() != null && l.file().name().equals(name)
                && l.file().status() == eu.amadeos.apps.call.domain.ChatFile.Status.DONE);
    }

    @Test
    void aChatHasTextButNoPictureOrSound() throws Exception {
        Calling c = annaCallsBen(CallKind.CHAT);
        assertEquals(CallKind.CHAT, ben.view.incomingKind);
        ben.on(() -> ben.presenter.accept(true));
        until("both open", () -> c.annaWindow.active && ben.lastWindow().active);
        assertEquals(CallKind.CHAT, ben.lastWindow().kind);
        assertTrue(anna.media.sessions.isEmpty() && ben.media.sessions.isEmpty(), "no media for a chat");
        anna.on(() -> anna.presenter.sendChat("Zeit für einen Anruf?"));
        until("Ben reads it", () -> ben.lastWindow().chat.size() == 1);
        ben.on(() -> ben.presenter.hangUp());
        until("logged", () -> anna.log.all().size() == 1 && ben.log.all().size() == 1);
        assertEquals(CallKind.CHAT, anna.log.all().getFirst().kind());
    }

    @Test
    void fromAChatToAVideoCallTheLinesMoveAlong() throws Exception {
        Calling c = annaCallsBen(CallKind.CHAT);
        ben.on(() -> ben.presenter.accept(true));
        TestSide.FakeView.Window benChat = ben.lastWindow();
        until("both open", () -> c.annaWindow.active && benChat.active);
        anna.on(() -> anna.presenter.sendChat("Zeit für einen Anruf?"));
        until("Ben reads it", () -> benChat.chat.size() == 1);
        ben.on(() -> ben.presenter.sendChat("Ja, gern"));
        until("Anna reads it", () -> c.annaWindow.chat.size() == 2);
        ben.view.incoming = null;

        anna.on(() -> anna.presenter.switchCall(CallKind.VIDEO));
        until("Ben rings again", () -> ben.view.incoming != null);
        assertEquals(CallKind.VIDEO, ben.view.incomingKind);
        TestSide.FakeView.Window annaCall = anna.lastWindow();
        assertEquals(CallKind.VIDEO, annaCall.kind);
        assertTrue(c.annaWindow.closed, "Anna's chat window makes way for the call");
        assertEquals(List.of("Zeit für einen Anruf?", "Ja, gern"),
                annaCall.chat.stream().map(l -> l.text()).toList());

        ben.on(() -> ben.presenter.accept(true));
        TestSide.FakeView.Window benCall = ben.lastWindow();
        until("both active", () -> annaCall.active && benCall.active);
        assertTrue(benChat.closed, "Ben's chat window makes way for the call");
        assertEquals(List.of("Zeit für einen Anruf?", "Ja, gern"),
                benCall.chat.stream().map(l -> l.text()).toList());
        assertTrue(benCall.chat.get(1).mine());
        until("pictures", () -> annaCall.remoteFrames > 0 && benCall.remoteFrames > 0);
    }

    @Test
    void theNextChatShowsTheKeptOne() throws Exception {
        Calling c = annaCallsBen(CallKind.CHAT);
        ben.on(() -> ben.presenter.accept(true));
        until("both open", () -> c.annaWindow.active && ben.lastWindow().active);
        anna.on(() -> anna.presenter.sendChat("Hallo Ben"));
        until("Ben reads it", () -> ben.lastWindow().chat.size() == 1);
        ben.on(() -> ben.presenter.sendChat("Hallo Anna"));
        until("Anna reads it", () -> c.annaWindow.chat.size() == 2);
        anna.on(() -> anna.presenter.hangUp());
        until("ended", () -> c.annaWindow.ended != null && ben.lastWindow().ended != null);
        until("kept", () -> anna.chats.load(ben.phone.self()).size() == 2 && ben.chats.load(anna.phone.self()).size() == 2);
        assertFalse(java.nio.file.Files.readString(dir.resolve("anna").resolve("chats")
                .resolve(ben.phone.self().hex() + ".chat"), java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("Hallo"), "the file is encrypted");

        ben.view.incoming = null;
        Calling again = annaCallsBen(CallKind.AUDIO);
        ben.on(() -> ben.presenter.accept(true));
        until("history shown", () -> again.annaWindow.history.size() == 2 && ben.lastWindow().history.size() == 2);
        assertEquals("Hallo Ben", again.annaWindow.history.getFirst().text());
        assertTrue(again.annaWindow.history.getFirst().mine());
        assertFalse(ben.lastWindow().history.getFirst().mine());
        anna.on(() -> anna.presenter.hangUp());
        until("ended again", () -> again.annaWindow.ended != null);

        anna.on(() -> anna.presenter.select(ben.phone.self()));
        anna.view.forget = true;
        anna.on(() -> anna.presenter.clearChatSelected());
        until("cleared", () -> anna.chats.load(ben.phone.self()).isEmpty());
        assertEquals(2, ben.chats.load(anna.phone.self()).size(), "Ben keeps his");
    }

    @Test
    void aLinkFromContactsCallsAndThePersonGoesIntoContacts() throws Exception {
        anna.known.remember(ben.phone.self(), "Ben", Optional.of(new eu.amadeos.apps.call.domain.Endpoint("127.0.0.1", ben.phone.port())));
        anna.on(() -> anna.presenter.select(ben.phone.self()));
        anna.on(() -> anna.presenter.addToContacts());
        until("added", () -> anna.view.infos.size() == 1);
        eu.amadeos.apps.call.repository.AddressBook book = new eu.amadeos.apps.call.repository.AddressBook(
                dir.resolve("anna").resolve("contacts"));
        assertTrue(book.contacts().getFirst().links(ben.phone.self()));
        anna.on(() -> anna.presenter.addToContacts());
        until("already there", () -> anna.view.infos.size() == 2);
        assertEquals(MSG.format("info.contact.already", "Ben", "Ben"), anna.view.infos.getLast());

        anna.on(() -> anna.presenter.message("call amadeos-call:" + ben.phone.self().hex() + "?kind=chat"));
        until("Ben asked to chat", () -> ben.view.incoming != null);
        assertEquals(CallKind.CHAT, ben.view.incomingKind);
        assertEquals(CallKind.CHAT, anna.lastWindow().kind);
        // End it, so nothing writes into the temporary folder while it is deleted.
        ben.on(() -> ben.presenter.decline());
        until("logged", () -> anna.log.all().size() == 1 && ben.log.all().size() == 1);
    }

    @Test
    void aDeclinedCall() throws Exception {
        Calling c = annaCallsBen(CallKind.AUDIO);
        ben.on(() -> ben.presenter.decline());
        until("declined", () -> c.annaWindow.ended != null);
        assertEquals(MSG.get("ended.declined"), c.annaWindow.ended);
        until("incoming closed", () -> ben.view.incoming.closed);
        until("logged", () -> ben.log.all().size() == 1);
        assertEquals(CallKind.AUDIO, ben.log.all().getFirst().kind());
    }

    @Test
    void aCallThatStopsRingingIsMissed() throws Exception {
        Calling c = annaCallsBen(CallKind.VIDEO);
        anna.on(() -> anna.presenter.hangUp());
        until("missed", () -> ben.view.missed.size() == 1);
        assertEquals("Anna", ben.view.missed.getFirst());
        until("logged", () -> ben.log.all().size() == 1);
        assertEquals(CallRecord.Direction.MISSED, ben.log.all().getFirst().direction());
        assertTrue(ben.view.incoming.closed);
        assertEquals(MSG.get("ended.hung-up"), c.annaWindow.ended);
    }

    @Test
    void onlyOneCallAtATime() throws Exception {
        annaCallsBen(CallKind.VIDEO);
        anna.view.address = "127.0.0.1:" + ben.phone.port();
        anna.on(() -> anna.presenter.callAddress(CallKind.VIDEO));
        assertEquals(MSG.get("error.in-call"), anna.view.errors.getLast());
    }

    @Test
    void addressesAreChecked() throws Exception {
        anna.view.address = "not an address::";
        anna.on(() -> anna.presenter.callAddress(CallKind.VIDEO));
        assertEquals(MSG.format("error.address", "not an address::"), anna.view.errors.getLast());
        anna.view.address = "127.0.0.1:1";
        anna.on(() -> anna.presenter.callAddress(CallKind.VIDEO));
        TestSide.FakeView.Window w = anna.lastWindow();
        until("unreachable", () -> w.ended != null);
        assertEquals(MSG.get("ended.unreachable"), w.ended);
        assertTrue(anna.log.all().isEmpty(), "an unreachable address is not logged");
    }

    @Test
    void theSafetyCodeIsKeptPerPerson() throws Exception {
        PeerKey benKey = ben.phone.self();
        anna.known.remember(benKey, "Ben", Optional.empty());
        anna.on(() -> {
            anna.presenter.select(benKey);
            anna.view.verdict = MainView.Verdict.MATCH;
            anna.presenter.verifySelected();
        });
        assertTrue(anna.known.verified(benKey));
        assertEquals(SafetyCode.of(anna.phone.self(), benKey), anna.view.lastCode);
        anna.on(() -> {
            anna.view.verdict = MainView.Verdict.DIFFERENT;
            anna.presenter.verifySelected();
        });
        assertFalse(anna.known.verified(benKey));
        assertEquals(MSG.format("safety.warning", "Ben"), anna.view.errors.getLast());
        anna.on(() -> {
            anna.view.forget = true;
            anna.presenter.forgetSelected();
        });
        assertTrue(anna.known.get(benKey).isEmpty());
    }

    @Test
    void settingsAreKeptAndTheNameIsAnnounced() throws Exception {
        SettingsModel before = anna.openSettings();
        assertEquals("Anna", before.name());
        assertEquals(List.of(new Devices.Choice("mic", "Microphone")), before.microphones());
        anna.on(() -> anna.presenter.applySettings(new SettingsModel("Anna B.", Optional.empty(), Optional.of("mic"),
                Optional.empty(), false, false, false, before.port() + 1, true, "", false, true, List.of(), List.of(),
                List.of())));
        assertEquals("Anna B.", anna.preferences.name());
        assertEquals(Optional.of("mic"), anna.preferences.microphone());
        assertFalse(anna.preferences.cameraOnAtStart());
        assertTrue(anna.autostart.enabled());
        assertTrue(before.keepChats() && before.chatsAvailable());
        assertFalse(anna.preferences.keepChats());
        assertEquals(MSG.get("settings.port.restart"), anna.view.errors.getLast());
        assertTrue(anna.view.status.startsWith(MSG.format("status.reachable", "Anna B.", String.valueOf(anna.phone.port()))));
    }

    @Test
    void closingTheWindowHidesItWhenCallsAreAwaited() throws Exception {
        anna.autostart.set(true);
        anna.on(() -> anna.presenter.windowClosing());
        assertTrue(anna.view.hidden);
        assertFalse(anna.exited.get());
        anna.autostart.set(false);
        anna.on(() -> anna.presenter.windowClosing());
        assertTrue(anna.exited.get());
        assertTrue(anna.view.closed);
    }

    @Test
    void missingPartsAreShownAtStart() throws Exception {
        TestSide carl = new TestSide(dir.resolve("carl"), "Carl");
        carl.media.missing = List.of("webrtcbin (gstreamer1.0-plugins-bad)");
        carl.start();
        assertEquals(List.of("webrtcbin (gstreamer1.0-plugins-bad)"), carl.view.missingParts);
        carl.shutdown();
    }

    // ---------------------------------------------------------------- helpers

    private record Calling(TestSide.FakeView.Window annaWindow) {
    }

    private Calling annaCallsBen(CallKind kind) throws Exception {
        anna.view.address = "127.0.0.1:" + ben.phone.port();
        anna.on(() -> anna.presenter.callAddress(kind));
        TestSide.FakeView.Window window = anna.lastWindow();
        until("Ben rings", () -> ben.view.incoming != null);
        assertEquals("Anna", ben.view.incomingName);
        until("Anna hears it ring", () -> window.phases.contains(MSG.format("phase.calling", "127.0.0.1:" + ben.phone.port())));
        return new Calling(window);
    }
}
