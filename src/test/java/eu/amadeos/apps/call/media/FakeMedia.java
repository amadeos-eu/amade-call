package eu.amadeos.apps.call.media;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Media without GStreamer: the offer and answer are words, and the call connects as soon as both are there. */
public final class FakeMedia implements MediaFactory {
    public final List<Session> sessions = new CopyOnWriteArrayList<>();
    public volatile List<String> missing = List.of();

    @Override
    public MediaSession create(MediaSettings settings, MediaEvents events) {
        Session s = new Session(settings, events);
        sessions.add(s);
        return s;
    }

    @Override
    public List<String> missing(Sources sources) {
        return missing;
    }

    public static final class Session implements MediaSession {
        public final MediaSettings settings;
        public final MediaEvents events;
        public volatile boolean microphone = true;
        public volatile boolean camera;
        public volatile boolean screen;
        public volatile boolean closed;

        Session(MediaSettings settings, MediaEvents events) {
            this.settings = settings;
            this.events = events;
            this.camera = settings.cameraOn();
        }

        @Override
        public void start() {
            if (settings.offerer()) events.localDescription("offer", "fake offer");
        }

        @Override
        public void remoteDescription(String type, String sdp) {
            if (type.equals("offer")) events.localDescription("answer", "fake answer");
            events.connected();
            events.remoteFrame(new BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB));
        }

        @Override
        public void iceCandidate(int index, String candidate) {
        }

        @Override
        public void setMicrophoneOn(boolean on) {
            microphone = on;
        }

        @Override
        public void setCameraOn(boolean on) {
            camera = on;
        }

        @Override
        public void setScreenSharing(boolean on) {
            screen = on;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
