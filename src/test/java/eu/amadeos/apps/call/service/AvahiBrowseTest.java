package eu.amadeos.apps.call.service;

import eu.amadeos.apps.call.domain.Endpoint;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvahiBrowseTest {
    private static final String ID = "3f2a".repeat(16);

    @Test
    void aResolvedLineGivesNameKeyAndAddress() {
        var event = AvahiBrowse.parse("=;eth0;IPv4;Anna\\032Berger\\032\\040Laptop\\041;_amadeos-call._tcp;local;"
                + "anna-pc.local;192.168.1.20;47474;\"v=1\" \"id=" + ID + "\" \"name=Anna Berger\"").orElseThrow();
        AvahiBrowse.Resolved r = assertInstanceOf(AvahiBrowse.Resolved.class, event);
        assertEquals("Anna Berger (Laptop)", r.service());
        assertEquals("Anna Berger", r.peer().name());
        assertEquals(ID, r.peer().key().hex());
        assertEquals(new Endpoint("192.168.1.20", 47474), r.peer().endpoint());
        assertTrue(!r.ipv6());
    }

    @Test
    void linkLocalIpv6GetsTheInterface() {
        var r = (AvahiBrowse.Resolved) AvahiBrowse.parse("=;wlan0;IPv6;Ben;_amadeos-call._tcp;local;ben.local;"
                + "fe80::1;47474;\"id=" + ID + "\"").orElseThrow();
        assertEquals("fe80::1%wlan0", r.peer().endpoint().host());
        assertEquals("Ben", r.peer().name());
        assertTrue(r.ipv6());
    }

    @Test
    void removedAndOtherLines() {
        assertEquals(new AvahiBrowse.Removed("Anna Berger"),
                AvahiBrowse.parse("-;eth0;IPv4;Anna\\032Berger;_amadeos-call._tcp;local").orElseThrow());
        assertTrue(AvahiBrowse.parse("+;eth0;IPv4;Anna;_amadeos-call._tcp;local").isEmpty());
        assertTrue(AvahiBrowse.parse("=;eth0;IPv4;Printer;_ipp._tcp;local;p.local;10.0.0.2;631;\"id=" + ID + "\"").isEmpty());
        assertTrue(AvahiBrowse.parse("=;eth0;IPv4;Bad;_amadeos-call._tcp;local;b.local;10.0.0.2;47474;\"id=xyz\"").isEmpty());
        assertTrue(AvahiBrowse.parse("=;eth0;IPv4;Bad;_amadeos-call._tcp;local;b.local;10.0.0.2;port;\"id=" + ID + "\"").isEmpty());
        assertTrue(AvahiBrowse.parse("Failed to resolve service").isEmpty());
        assertTrue(AvahiBrowse.parse("").isEmpty());
        assertTrue(AvahiBrowse.parse(null).isEmpty());
    }

    @Test
    void unescapeAndTxt() {
        assertEquals("Jürgen; Büro", AvahiBrowse.unescape("J\\195\\188rgen\\059\\032B\\195\\188ro"));
        assertEquals("a.b", AvahiBrowse.unescape("a\\.b"));
        assertEquals(Map.of("name", "A \"B\"", "id", "1"), AvahiBrowse.txt("\"name=A \\\"B\\\"\" \"id=1\" \"id=2\""));
        assertEquals(3, AvahiBrowse.split("a;b" + '\\' + ";c;d").size());
    }
}
