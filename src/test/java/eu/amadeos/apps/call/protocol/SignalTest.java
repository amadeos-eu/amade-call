package eu.amadeos.apps.call.protocol;

import eu.amadeos.apps.call.domain.CallKind;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalTest {
    private static Signal roundTrip(Signal s) {
        return Signal.decode(s.encode()).orElseThrow();
    }

    @Test
    void helloCarriesNameKindCameraAndVersion() {
        Signal hello = roundTrip(Signal.hello("Anna \"B\" Ünal", CallKind.AUDIO, false));
        assertEquals(Signal.Type.HELLO, hello.type());
        assertEquals("Anna \"B\" Ünal", hello.text("name"));
        assertEquals(CallKind.AUDIO, hello.kind());
        assertFalse(hello.flag("video"));
        assertEquals(Signal.VERSION, hello.number("version"));
    }

    @Test
    void everyMessageSurvivesTheWire() {
        assertTrue(roundTrip(Signal.accept("Ben", true)).flag("video"));
        assertEquals("Ben", roundTrip(Signal.accept("Ben", true)).text("name"));
        assertEquals("busy", roundTrip(Signal.decline("Ben", true)).text("reason"));
        assertEquals("declined", roundTrip(Signal.decline("Ben", false)).text("reason"));
        String sdp = "v=0\r\no=- 1 0 IN IP4 0.0.0.0\r\na=fingerprint:sha-256 AB:CD\r\n";
        assertEquals(sdp, roundTrip(Signal.offer(sdp)).text("sdp"));
        assertEquals(sdp, roundTrip(Signal.answer(sdp)).text("sdp"));
        Signal ice = roundTrip(Signal.ice(1, "candidate:1 1 UDP 2015363327 192.168.1.20 47000 typ host"));
        assertEquals(1, ice.number("index"));
        assertEquals("candidate:1 1 UDP 2015363327 192.168.1.20 47000 typ host", ice.text("candidate"));
        assertEquals("Hallo 👋\nzweite Zeile", roundTrip(Signal.chat("Hallo 👋\nzweite Zeile")).text("text"));
        assertFalse(roundTrip(Signal.camera(false)).flag("on"));
        assertTrue(roundTrip(Signal.screen(true)).flag("on"));
        assertTrue(roundTrip(Signal.microphone(true)).flag("on"));
        assertEquals(Signal.Type.BYE, roundTrip(Signal.bye()).type());
    }

    @Test
    void unknownOrBrokenMessagesAreSkipped() {
        assertEquals(Optional.empty(), Signal.decode("{\"type\":\"teleport\"}"));
        assertEquals(Optional.empty(), Signal.decode("not json"));
        assertEquals(Optional.empty(), Signal.decode("[1,2]"));
        assertEquals(Optional.empty(), Signal.decode("{}"));
    }

    @Test
    void aHelloWithoutKindIsAVideoCall() {
        Signal hello = Signal.decode("{\"type\":\"hello\",\"name\":\"x\"}").orElseThrow();
        assertEquals(CallKind.VIDEO, hello.kind());
    }
}
