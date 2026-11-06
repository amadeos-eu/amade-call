package eu.amadeos.apps.call.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * "Wait for calls after login": an XDG autostart entry that starts amade-call in the background (window
 * minimised) when the desktop session begins, so that calls come in without opening the program first.
 */
public class Autostart {
    public static final String FILE = "amade-call.desktop";
    public static final String BACKGROUND = "--background";

    private final Path file;

    public Autostart(Path configHome) {
        this.file = configHome.resolve("autostart").resolve(FILE);
    }

    public boolean enabled() {
        return Files.isRegularFile(file);
    }

    public void set(boolean on) throws IOException {
        if (!on) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                [Desktop Entry]
                Type=Application
                Name=amadeOS Calls
                Comment=Waits for calls in the background
                Exec=/usr/bin/amade-call %s
                Icon=amade-call
                Terminal=false
                NoDisplay=true
                X-GNOME-Autostart-enabled=true
                """.formatted(BACKGROUND));
    }

    Path file() {
        return file;
    }
}
