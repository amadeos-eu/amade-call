package eu.amadeos.apps.call.media;

import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Calls with GStreamer: {@code webrtcbin} for the connection (ICE through libnice, DTLS-SRTP, RTP), VP8 and Opus
 * for picture and sound, {@code v4l2src} for the camera, {@code ximagesrc} for the screen and PulseAudio
 * (PipeWire) for microphone and speakers. All parts come from the Debian packages gstreamer1.0-plugins-base,
 * -good, -bad and gstreamer1.0-nice.
 */
public final class GstMedia implements MediaFactory {
    private static final Logger LOG = LoggerFactory.getLogger(GstMedia.class);
    private static boolean initialised;
    private static String initError;

    /** A still picture that stands in for the camera of test sources; empty for the moving test picture. */
    private final Optional<Path> testPicture;

    public GstMedia() {
        this(Optional.empty());
    }

    public GstMedia(Optional<Path> testPicture) {
        this.testPicture = testPicture;
    }

    /** Starts GStreamer once per program; false when the library cannot be loaded. */
    static synchronized boolean init() {
        if (initialised) return initError == null;
        initialised = true;
        try {
            if (!Gst.isInitialized()) Gst.init(Version.of(1, 16), "amade-call");
            LOG.info("GStreamer {}", Gst.getVersionString());
        } catch (Throwable e) {
            initError = e.toString();
            LOG.warn("GStreamer could not be started", e);
        }
        return initError == null;
    }

    @Override
    public MediaSession create(MediaSettings settings, MediaEvents events) {
        if (!init()) throw new IllegalStateException("GStreamer: " + initError);
        return new GstSession(settings, events, testPicture);
    }

    @Override
    public List<String> missing(Sources sources) {
        if (!init()) return List.of("libgstreamer1.0-0 (" + initError + ")");
        List<String> missing = new ArrayList<>();
        need(missing, "webrtcbin", "gstreamer1.0-plugins-bad");
        need(missing, "nicesrc", "gstreamer1.0-nice");
        need(missing, "vp8enc", "gstreamer1.0-plugins-good");
        need(missing, "rtpvp8pay", "gstreamer1.0-plugins-good");
        need(missing, "opusenc", "gstreamer1.0-plugins-base");
        need(missing, "videotestsrc", "gstreamer1.0-plugins-base");
        need(missing, "input-selector", "gstreamer1.0-plugins-base (libgstreamer1.0-0)");
        need(missing, "appsink", "gstreamer1.0-plugins-base");
        need(missing, "audiomixer", "gstreamer1.0-plugins-base");
        if (sources != Sources.HEADLESS) {
            need(missing, "pulsesrc", "gstreamer1.0-pulseaudio");
            need(missing, "pulsesink", "gstreamer1.0-pulseaudio");
        }
        if (sources == Sources.DEVICES) need(missing, "v4l2src", "gstreamer1.0-plugins-good");
        return missing;
    }

    private static void need(List<String> missing, String element, String debianPackage) {
        if (!has(element)) missing.add(element + " (" + debianPackage + ")");
    }

    /** Whether GStreamer knows the element. */
    static boolean has(String element) {
        try {
            return ElementFactory.find(element) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
