package eu.amadeos.apps.call.repository;

import eu.amadeos.apps.call.domain.CallKind;
import eu.amadeos.apps.call.domain.CallRecord;
import eu.amadeos.apps.call.domain.Endpoint;
import eu.amadeos.apps.call.domain.PeerKey;
import eu.amadeos.apps.call.domain.Person;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryTest {
    static final PeerKey ANNA = new PeerKey("a".repeat(64));
    static final PeerKey BEN = new PeerKey("b".repeat(64));

    @TempDir
    Path dir;

    @Test
    void preferencesHaveDefaultsAndKeepChanges() {
        Path file = dir.resolve("call.properties");
        CallPreferences p = new CallPreferences(file, "Ralf (zephyrus)");
        assertEquals("Ralf (zephyrus)", p.name());
        assertEquals(Endpoint.DEFAULT_PORT, p.port());
        assertTrue(p.echoCancellation());
        assertTrue(p.cameraOnAtStart());
        assertEquals(Optional.empty(), p.camera());

        p.setName("Ralf im Büro");
        p.setCamera("/dev/video2");
        p.setMicrophone("alsa_input.usb");
        p.setSpeaker("alsa_output.pci");
        p.setPort(47500);
        p.setEchoCancellation(false);
        p.setCameraOnAtStart(false);

        CallPreferences again = new CallPreferences(file, "Ralf (zephyrus)");
        assertEquals("Ralf im Büro", again.name());
        assertEquals(Optional.of("/dev/video2"), again.camera());
        assertEquals(Optional.of("alsa_input.usb"), again.microphone());
        assertEquals(Optional.of("alsa_output.pci"), again.speaker());
        assertEquals(47500, again.port());
        assertFalse(again.echoCancellation());
        assertFalse(again.cameraOnAtStart());

        again.setName("  ");
        again.setCamera(null);
        again.setPort(Endpoint.DEFAULT_PORT);
        assertEquals("Ralf (zephyrus)", again.name());
        assertEquals(Optional.empty(), again.camera());
        assertEquals(Endpoint.DEFAULT_PORT, again.port());
    }

    @Test
    void aBrokenPortFallsBackToTheDefault() throws Exception {
        Path file = dir.resolve("call.properties");
        Files.writeString(file, "port=nonsense\n");
        assertEquals(Endpoint.DEFAULT_PORT, new CallPreferences(file, "x").port());
        Files.writeString(file, "port=99999\n");
        assertEquals(Endpoint.DEFAULT_PORT, new CallPreferences(file, "x").port());
    }

    @Test
    void theFullNameComesFromThePasswordFile() throws Exception {
        Path passwd = dir.resolve("passwd");
        Files.writeString(passwd, "root:x:0:0:root:/root:/bin/bash\n"
                + "ralf:x:1000:1000:Ralf Eichinger,,,:/home/ralf:/bin/bash\nanna:x:1001:1001::/home/anna:/bin/bash\n");
        assertEquals(Optional.of("Ralf Eichinger"), CallPreferences.fullName("ralf", passwd));
        assertEquals(Optional.empty(), CallPreferences.fullName("anna", passwd));
        assertEquals(Optional.empty(), CallPreferences.fullName("ben", passwd));
        assertEquals(Optional.empty(), CallPreferences.fullName("ralf", dir.resolve("missing")));
    }

    @Test
    void knownPeopleKeepNameAddressAndVerification() {
        Path file = dir.resolve("people.properties");
        KnownPeers known = new KnownPeers(file);
        known.remember(BEN, "Ben", Optional.of(new Endpoint("10.0.0.2", 47474)));
        known.remember(ANNA, "Anna", Optional.empty());
        known.setVerified(ANNA, true);

        KnownPeers again = new KnownPeers(file);
        List<Person> all = again.all();
        assertEquals(List.of("Anna", "Ben"), all.stream().map(Person::name).toList());
        assertTrue(again.verified(ANNA));
        assertFalse(again.verified(BEN));
        assertEquals(Optional.of(new Endpoint("10.0.0.2", 47474)), again.get(BEN).orElseThrow().endpoint());
        assertFalse(again.get(BEN).orElseThrow().nearby());

        again.remember(ANNA, "Anna Berger", Optional.of(new Endpoint("anna-pc.local", 47474)));
        assertTrue(again.verified(ANNA), "a new name keeps the verification");
        again.setVerified(ANNA, false);
        assertFalse(again.verified(ANNA));
        again.forget(BEN);
        assertEquals(Optional.empty(), again.get(BEN));
        assertEquals(1, new KnownPeers(file).all().size());
    }

    @Test
    void theCallLogKeepsTheNewestFirstAndSurvivesARestart() throws Exception {
        Path file = dir.resolve("calls.tsv");
        CallLog log = new CallLog(file);
        log.add(new CallRecord(ANNA, "Anna", Instant.parse("2026-11-01T10:00:00Z"), Duration.ofMinutes(3),
                CallRecord.Direction.OUTGOING, CallKind.VIDEO));
        log.add(new CallRecord(BEN, "Ben\tTab", Instant.parse("2026-11-02T10:00:00Z"), Duration.ZERO,
                CallRecord.Direction.MISSED, CallKind.AUDIO));
        CallLog again = new CallLog(file);
        assertEquals(2, again.all().size());
        assertEquals(BEN, again.all().getFirst().key());
        assertEquals("Ben Tab", again.all().getFirst().name());
        assertEquals(CallRecord.Direction.MISSED, again.all().getFirst().direction());
        assertEquals(Duration.ofMinutes(3), again.with(ANNA).getFirst().length());
        assertEquals(1, again.with(BEN).size());

        Files.writeString(file, "broken line\n" + Files.readString(file));
        assertEquals(2, new CallLog(file).all().size());
        again.clear();
        assertTrue(new CallLog(file).all().isEmpty());
    }

    @Test
    void theCallLogKeepsAtMostItsLimit() {
        CallLog log = new CallLog(dir.resolve("calls.tsv"));
        for (int i = 0; i < CallLog.KEEP + 5; i++) {
            log.add(new CallRecord(ANNA, "Anna", Instant.ofEpochSecond(i), Duration.ZERO,
                    CallRecord.Direction.OUTGOING, CallKind.VIDEO));
        }
        assertEquals(CallLog.KEEP, log.all().size());
        assertEquals(Instant.ofEpochSecond(CallLog.KEEP + 4), log.all().getFirst().start());
    }
}
