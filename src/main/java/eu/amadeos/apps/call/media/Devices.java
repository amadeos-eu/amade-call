package eu.amadeos.apps.call.media;

import eu.amadeos.commons.json.Json;
import eu.amadeos.commons.process.CommandRunner;
import org.freedesktop.gstreamer.Structure;
import org.freedesktop.gstreamer.device.Device;
import org.freedesktop.gstreamer.device.DeviceMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The cameras (from GStreamer's device monitor, which leaves out the metadata nodes of USB cameras) and the
 * microphones and speakers (from PipeWire through its PulseAudio interface, {@code pactl}).
 */
public class Devices {
    private static final Logger LOG = LoggerFactory.getLogger(Devices.class);

    /** A device: {@code id} is what GStreamer takes (device node or PulseAudio name), {@code label} what people read. */
    public record Choice(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final CommandRunner runner;

    public Devices(CommandRunner runner) {
        this.runner = runner;
    }

    public List<Choice> cameras() {
        if (!GstMedia.init()) return List.of();
        List<Choice> cameras = new ArrayList<>();
        DeviceMonitor monitor = new DeviceMonitor();
        try {
            monitor.addFilter("Video/Source", null);
            monitor.start();
            for (Device device : monitor.getDevices()) {
                String path = path(device.getProperties());
                if (path.isEmpty() || cameras.stream().anyMatch(c -> c.id().equals(path))) continue;
                cameras.add(new Choice(path, device.getDisplayName()));
            }
        } catch (RuntimeException e) {
            LOG.debug("No camera list: {}", e.toString());
        } finally {
            monitor.stop();
        }
        cameras.sort(Comparator.comparing(Choice::id));
        return cameras;
    }

    /** The device node of a V4L2 camera; PipeWire's own camera entries have none and are skipped. */
    static String path(Structure properties) {
        if (properties == null) return "";
        for (String key : List.of("api.v4l2.path", "device.path")) {
            if (properties.hasField(key)) {
                String value = properties.getString(key);
                if (value != null && value.startsWith("/dev/video")) return value;
            }
        }
        return "";
    }

    public List<Choice> microphones() {
        return parse(pactl("sources"), true);
    }

    public List<Choice> speakers() {
        return parse(pactl("sinks"), false);
    }

    private String pactl(String what) {
        try {
            return runner.output(List.of("pactl", "-f", "json", "list", what), Duration.ofSeconds(4));
        } catch (IOException | RuntimeException e) {
            LOG.debug("No {}: {}", what, e.getMessage());
            return "[]";
        }
    }

    /** The entries of {@code pactl -f json list sources|sinks}; the monitor sources (what speakers play) are left out. */
    static List<Choice> parse(String json, boolean sources) {
        List<Choice> list = new ArrayList<>();
        try {
            for (Object entry : Json.parseArray(json)) {
                String name = Json.str(entry, "name");
                if (name.isBlank() || (sources && name.endsWith(".monitor"))) continue;
                String description = Json.str(entry, "description");
                list.add(new Choice(name, description.isBlank() ? name : description));
            }
        } catch (IllegalArgumentException e) {
            LOG.debug("pactl gave no JSON: {}", e.getMessage());
        }
        return list;
    }
}
