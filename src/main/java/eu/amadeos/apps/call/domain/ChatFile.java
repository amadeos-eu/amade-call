package eu.amadeos.apps.call.domain;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A picture or file in the chat, as it stands: on its way ({@code done} of {@code size} bytes), arrived (then
 * {@code path} is where it is) or failed.
 */
public record ChatFile(String id, String name, long size, long done, Status status, Path path) {
    public enum Status { RUNNING, DONE, FAILED }

    private static final Set<String> PICTURES = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp");

    public ChatFile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
    }

    public static ChatFile running(String id, String name, long size) {
        return new ChatFile(id, name, size, 0, Status.RUNNING, null);
    }

    public ChatFile progress(long bytes) {
        return new ChatFile(id, name, size, bytes, Status.RUNNING, null);
    }

    public ChatFile arrived(Path at) {
        return new ChatFile(id, name, size, size, Status.DONE, at);
    }

    public ChatFile failed() {
        return new ChatFile(id, name, size, done, Status.FAILED, null);
    }

    /** A picture the chat can show small. */
    public boolean picture() {
        int dot = name.lastIndexOf('.');
        return dot > 0 && PICTURES.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** 0 to 100. */
    public int percent() {
        return size <= 0 ? 100 : (int) Math.min(100, done * 100 / size);
    }

    /** "512 B", "3.4 kB", "12.0 MB", "1.2 GB". */
    public static String sizeText(long bytes, Locale locale) {
        if (bytes < 1000) return bytes + " B";
        String[] units = {"kB", "MB", "GB"};
        double v = bytes;
        int u = -1;
        while (v >= 1000 && u < units.length - 1) {
            v /= 1000;
            u++;
        }
        return String.format(locale, "%.1f %s", v, units[u]);
    }
}
