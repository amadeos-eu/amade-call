package eu.amadeos.apps.call.repository;

import com.google.inject.Inject;
import eu.amadeos.apps.call.domain.Endpoint;
import eu.amadeos.commons.config.AppDirectories;
import eu.amadeos.commons.config.PropertiesFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Choices of amade-call that last, in {@code ~/.config/amadeos/call/call.properties}: the name others see, the
 * camera, microphone and speakers, the call port and the echo cancellation. Written at each change.
 */
public class CallPreferences {
    public static final String FILE = "call.properties";

    private final Path file;
    private final Properties properties;
    private final String defaultName;

    @Inject
    public CallPreferences(AppDirectories directories) {
        this(directories.configFile(FILE), systemName());
    }

    public CallPreferences(Path file, String defaultName) {
        this.file = Objects.requireNonNull(file, "file");
        this.properties = PropertiesFile.load(file);
        this.defaultName = defaultName;
    }

    /** The name others see in their list: "Ralf Eichinger (zephyrus)" unless the user chose one. */
    public String name() {
        return text("name").orElse(defaultName);
    }

    public void setName(String name) {
        set("name", name == null || name.isBlank() || name.strip().equals(defaultName) ? null : name.strip());
    }

    /** The camera's device node, e.g. {@code /dev/video0}; empty for the first camera found. */
    public Optional<String> camera() {
        return text("camera");
    }

    public void setCamera(String device) {
        set("camera", device);
    }

    /** The PulseAudio/PipeWire source; empty for the desktop's default input. */
    public Optional<String> microphone() {
        return text("microphone");
    }

    public void setMicrophone(String name) {
        set("microphone", name);
    }

    /** The PulseAudio/PipeWire sink; empty for the desktop's default output. */
    public Optional<String> speaker() {
        return text("speaker");
    }

    public void setSpeaker(String name) {
        set("speaker", name);
    }

    public int port() {
        try {
            int p = Integer.parseInt(text("port").orElse(""));
            return p > 0 && p < 65536 ? p : Endpoint.DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return Endpoint.DEFAULT_PORT;
        }
    }

    public void setPort(int port) {
        set("port", port == Endpoint.DEFAULT_PORT ? null : Integer.toString(port));
    }

    /** Echo cancellation for the microphone; on by default (it lets laptops call without headphones). */
    public boolean echoCancellation() {
        return !"false".equals(properties.getProperty("echo-cancellation"));
    }

    public void setEchoCancellation(boolean on) {
        set("echo-cancellation", on ? null : "false");
    }

    /** Whether a video call starts with the camera on; on by default. */
    public boolean cameraOnAtStart() {
        return !"false".equals(properties.getProperty("camera.on-at-start"));
    }

    public void setCameraOnAtStart(boolean on) {
        set("camera.on-at-start", on ? null : "false");
    }

    private Optional<String> text(String key) {
        String v = properties.getProperty(key);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v.strip());
    }

    private synchronized void set(String key, String value) {
        if (value == null || value.isBlank()) {
            properties.remove(key);
        } else {
            properties.setProperty(key, value);
        }
        PropertiesFile.store(file, properties, "amade-call");
    }

    /** "Full Name (host)" from /etc/passwd and the host name, or the login name. */
    static String systemName() {
        String user = System.getProperty("user.name", "");
        String full = fullName(user, Path.of("/etc/passwd")).orElse(user);
        String host = hostName();
        return host.isEmpty() || host.equalsIgnoreCase("localhost") ? full : full + " (" + host + ")";
    }

    static Optional<String> fullName(String user, Path passwd) {
        try {
            List<String> lines = Files.readAllLines(passwd);
            for (String line : lines) {
                String[] f = line.split(":", -1);
                if (f.length > 4 && f[0].equals(user)) {
                    String gecos = f[4].split(",", -1)[0].strip();
                    return gecos.isEmpty() ? Optional.empty() : Optional.of(gecos);
                }
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    static String hostName() {
        try {
            String h = Files.readString(Path.of("/etc/hostname")).strip();
            if (!h.isEmpty()) return h;
        } catch (IOException ignored) {
            // fall back below
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "";
        }
    }
}
