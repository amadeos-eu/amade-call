package eu.amadeos.apps.call.media;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaHelpersTest {
    @Test
    void bgrxBytesBecomeRgbPixels() {
        ByteBuffer bytes = ByteBuffer.wrap(new byte[]{
                (byte) 0x30, (byte) 0x20, (byte) 0x10, 0, // B G R x
                (byte) 0xff, 0, 0, 0});
        BufferedImage image = GstSession.image(bytes, 2, 1);
        assertEquals(0x102030, image.getRGB(0, 0) & 0xffffff);
        assertEquals(0x0000ff, image.getRGB(1, 0) & 0xffffff);
    }

    @Test
    void aShortBufferLeavesTheRestBlack() {
        BufferedImage image = GstSession.image(ByteBuffer.wrap(new byte[]{1, 2, 3, 0}), 2, 2);
        assertEquals(0, image.getRGB(1, 1) & 0xffffff);
    }

    @Test
    void quotedValuesKeepQuotesAndBackslashes() {
        assertEquals("\"/home/a b/\\\"x\\\\.png\"", GstSession.quote("/home/a b/\"x\\.png"));
    }

    @Test
    void pactlListsWithoutMonitors() {
        String json = """
                [{"name":"alsa_input.usb-mic","description":"USB Microphone"},
                 {"name":"alsa_output.pci.monitor","description":"Monitor of Speakers"},
                 {"name":"noname","description":""},
                 {"name":"","description":"skipped"}]""";
        assertEquals(List.of(new Devices.Choice("alsa_input.usb-mic", "USB Microphone"),
                new Devices.Choice("noname", "noname")), Devices.parse(json, true));
        assertEquals(3, Devices.parse(json, false).size());
        assertTrue(Devices.parse("not json", true).isEmpty());
        assertEquals("USB Microphone", new Devices.Choice("x", "USB Microphone").toString());
    }
}
