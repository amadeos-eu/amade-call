package eu.amadeos.apps.call.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAddressTest {
    @Test
    void theNameLosesItsDomain() {
        assertEquals("anna-pc", LocalAddress.shortName("Anna-PC.fritz.box"));
        assertEquals("amadeos", LocalAddress.shortName("amadeos"));
        assertEquals("", LocalAddress.shortName("localhost"));
        assertEquals("", LocalAddress.shortName(""));
    }

    @Test
    void bridgesOfVirtualMachinesAreLeftOut() {
        assertTrue(LocalAddress.real("enp3s0"));
        assertTrue(LocalAddress.real("wlan0"));
        assertFalse(LocalAddress.real("docker0"));
        assertFalse(LocalAddress.real("virbr0"));
    }

    @Test
    void theDescriptionNeverHasLoopback() {
        assertFalse(LocalAddress.describe().contains("127.0.0.1"));
    }
}
