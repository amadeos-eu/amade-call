package eu.amadeos.apps.call.media;

import org.freedesktop.gstreamer.Bin;
import org.freedesktop.gstreamer.Buffer;
import org.freedesktop.gstreamer.Bus;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.FlowReturn;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.GstObject;
import org.freedesktop.gstreamer.Pad;
import org.freedesktop.gstreamer.PadDirection;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.SDPMessage;
import org.freedesktop.gstreamer.Sample;
import org.freedesktop.gstreamer.State;
import org.freedesktop.gstreamer.StateChangeReturn;
import org.freedesktop.gstreamer.Structure;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.webrtc.WebRTCBin;
import org.freedesktop.gstreamer.webrtc.WebRTCPeerConnectionState;
import org.freedesktop.gstreamer.webrtc.WebRTCSDPType;
import org.freedesktop.gstreamer.webrtc.WebRTCSessionDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The GStreamer pipeline of one call.
 *
 * <pre>
 * camera ─┐                                    ┌─ VP8 ─ RTP ─┐
 * black  ─┼─ input-selector ─ tee ─────────────┤             ├─ webrtcbin ─┬─ VP8 ─ appsink (picture)
 * screen ─┘   (what is sent)                   └─ self view  │             └─ Opus ─ audiomixer ─ speakers
 * microphone ─ echo cancellation ─ volume (mute) ─ Opus ─ RTP┘
 * </pre>
 *
 * The camera, a black picture and (while shared) the screen all lead into an {@code input-selector}, so that
 * switching between them never changes what webrtcbin negotiated. The sound of the other side goes through an
 * {@code audiomixer} that also plays silence, so that the echo probe sees the far end from the start.
 */
final class GstSession implements MediaSession {
    private static final Logger LOG = LoggerFactory.getLogger(GstSession.class);
    /** What every picture source becomes before the selector: 720p, 30 frames, I420. */
    static final String VIDEO_CAPS = "video/x-raw,format=I420,width=1280,height=720,framerate=30/1,pixel-aspect-ratio=1/1";
    /** How long the connection may take before the call is given up. */
    static final long CONNECT_TIMEOUT_SECONDS = 30;

    private final MediaSettings settings;
    private final MediaEvents events;
    private final ScheduledExecutorService watch = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("call-media-watch").factory());
    private Pipeline pipe;
    private WebRTCBin webrtc;
    private Element selector;
    private Pad cameraPad;
    private Pad blackPad;
    private Pad screenPad;
    private Bin screenBin;
    private boolean hasCamera;
    private boolean cameraOn;
    private boolean sharing;
    private boolean echoCancellation;
    private volatile boolean connected;
    private volatile boolean closed;
    private long startedAt;

    GstSession(MediaSettings settings, MediaEvents events) {
        this.settings = settings;
        this.events = events;
        this.cameraOn = settings.cameraOn();
    }

    // ---------------------------------------------------------------- set-up

    @Override
    public synchronized void start() {
        hasCamera = settings.kind().video();
        echoCancellation = settings.echoCancellation() && settings.sources() != Sources.HEADLESS
                && GstMedia.has("webrtcdsp") && GstMedia.has("webrtcechoprobe");
        build();
        StateChangeReturn result = pipe.setState(State.PLAYING);
        if (result == StateChangeReturn.FAILURE && hasCamera && settings.kind().video()) {
            // Most often the camera: busy in another program, or pulled out. Go on without it.
            LOG.info("The pipeline did not start with the camera; trying without");
            dispose();
            hasCamera = false;
            events.cameraFailed("start");
            build();
            result = pipe.setState(State.PLAYING);
        }
        if (result == StateChangeReturn.FAILURE) {
            events.failed("pipeline");
            return;
        }
        startedAt = System.nanoTime();
        watch.scheduleWithFixedDelay(this::checkConnection, 500, 500, TimeUnit.MILLISECONDS);
    }

    private void build() {
        String description = description();
        LOG.debug("Call pipeline: {}", description);
        pipe = (Pipeline) Gst.parseLaunch(description);
        webrtc = (WebRTCBin) pipe.getElementByName("webrtc");
        selector = pipe.getElementByName("sel");
        if (selector != null) {
            cameraPad = hasCamera ? selector.getStaticPad("sink_0") : null;
            blackPad = selector.getStaticPad(hasCamera ? "sink_1" : "sink_0");
            select(hasCamera && cameraOn ? cameraPad : blackPad);
            appSink("self", events::localFrame);
        }
        pipe.getBus().connect((Bus.ERROR) this::onError);
        pipe.getBus().connect((Bus.WARNING) (source, code, message) ->
                LOG.debug("GStreamer warning from {}: {}", source.getName(), message));
        webrtc.connect((WebRTCBin.ON_ICE_CANDIDATE) events::iceCandidate);
        if (settings.offerer()) {
            webrtc.connect((WebRTCBin.ON_NEGOTIATION_NEEDED) element -> webrtc.createOffer(offer -> {
                webrtc.setLocalDescription(offer);
                events.localDescription("offer", offer.getSDPMessage().toString());
            }));
        }
        webrtc.connect((Element.PAD_ADDED) this::onRemotePad);
    }

    /** The pipeline as gst-launch text. */
    String description() {
        StringBuilder d = new StringBuilder("webrtcbin name=webrtc bundle-policy=max-bundle latency=150\n");
        if (settings.kind().video()) {
            d.append("input-selector name=sel sync-mode=clock ! tee name=t\n");
            d.append("t. ! queue leaky=downstream max-size-buffers=2 max-size-time=0 max-size-bytes=0"
                    + " ! vp8enc deadline=1 cpu-used=8 end-usage=cbr target-bitrate=1500000 keyframe-max-dist=90"
                    + " error-resilient=default threads=4 ! rtpvp8pay pt=96 mtu=1200"
                    + " ! application/x-rtp,media=video,encoding-name=VP8,payload=96,clock-rate=90000 ! webrtc.\n");
            d.append("t. ! queue leaky=downstream max-size-buffers=1 ! videorate ! video/x-raw,framerate=15/1"
                    + " ! videoscale ! videoconvert ! video/x-raw,format=BGRx,width=320,height=180"
                    + " ! appsink name=self emit-signals=true sync=false max-buffers=1 drop=true\n");
            if (hasCamera) d.append(cameraSource()).append(" ! queue ! videoconvert ! videoscale add-borders=true"
                    + " ! videorate ! ").append(VIDEO_CAPS).append(" ! sel.\n");
            d.append("videotestsrc is-live=true pattern=black ! ").append(VIDEO_CAPS).append(" ! sel.\n");
        }
        d.append(microphone()).append(" ! queue ! audioconvert ! audioresample ! audio/x-raw,rate=48000,channels=1");
        if (echoCancellation) d.append(" ! audioconvert ! webrtcdsp probe=echo");
        d.append(" ! volume name=mic ! audioconvert ! opusenc bitrate=32000 ! rtpopuspay pt=97")
                .append(" ! application/x-rtp,media=audio,encoding-name=OPUS,payload=97,clock-rate=48000 ! webrtc.\n");
        // The other side's sound; the silence keeps the mixer (and the echo probe) running from the start.
        d.append("audiotestsrc is-live=true wave=silence ! audio/x-raw,rate=48000,channels=1 ! audiomixer name=mix")
                .append(" ! audioconvert ! audioresample");
        if (echoCancellation) d.append(" ! webrtcechoprobe name=echo");
        d.append(" ! audioconvert ! ").append(speaker()).append('\n');
        return d.toString();
    }

    private String cameraSource() {
        if (settings.sources() != Sources.DEVICES) return "videotestsrc is-live=true pattern=ball name=cam";
        String device = settings.camera().map(c -> " device=" + quote(c)).orElse("");
        // Most webcams give 720p at 30 frames only as MJPEG; raw pictures are the fallback.
        return "v4l2src name=cam" + device + " ! image/jpeg,width=[320,1280],height=[240,720],framerate=[15/1,30/1]"
                + ";video/x-raw,width=[320,1280],height=[240,720],framerate=[10/1,30/1] ! decodebin";
    }

    private String microphone() {
        return switch (settings.sources()) {
            case HEADLESS -> "audiotestsrc is-live=true wave=sine freq=440 volume=0.1";
            default -> "pulsesrc" + settings.microphone().map(m -> " device=" + quote(m)).orElse("")
                    + " client-name=amade-call";
        };
    }

    private String speaker() {
        return switch (settings.sources()) {
            case HEADLESS -> "fakesink sync=true";
            default -> "pulsesink" + settings.speaker().map(s -> " device=" + quote(s)).orElse("")
                    + " client-name=amade-call";
        };
    }

    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---------------------------------------------------------------- the other side

    private void onRemotePad(Element element, Pad pad) {
        if (closed || pad.getDirection() != PadDirection.SRC) return;
        String caps = String.valueOf(pad.getCurrentCaps());
        boolean video = caps.contains("media=(string)video");
        LOG.info("Receiving {}", video ? "picture" : "sound");
        synchronized (this) {
            if (closed) return;
            Bin bin = Gst.parseBinFromDescription(video
                    ? "queue ! rtpvp8depay ! vp8dec ! videoconvert ! video/x-raw,format=BGRx"
                    + " ! appsink name=remote emit-signals=true sync=true max-buffers=2 drop=true"
                    : "queue ! rtpopusdepay ! opusdec plc=true ! audioconvert ! audioresample"
                    + " ! capsfilter caps=audio/x-raw,rate=48000,channels=1", true);
            pipe.add(bin);
            if (video) {
                AppSink sink = (AppSink) bin.getElementByName("remote");
                sink.connect((AppSink.NEW_SAMPLE) s -> frame(s, events::remoteFrame));
            } else {
                Element mix = pipe.getElementByName("mix");
                Pad mixPad = mix.getRequestPad("sink_%u");
                bin.getSrcPads().getFirst().link(mixPad);
            }
            bin.syncStateWithParent();
            pad.link(bin.getSinkPads().getFirst());
        }
    }

    @Override
    public void remoteDescription(String type, String sdp) {
        boolean offer = "offer".equals(type);
        SDPMessage message = new SDPMessage();
        message.parseBuffer(sdp);
        synchronized (this) {
            if (closed) return;
            webrtc.setRemoteDescription(new WebRTCSessionDescription(offer ? WebRTCSDPType.OFFER : WebRTCSDPType.ANSWER,
                    message));
            if (offer) {
                webrtc.createAnswer(answer -> {
                    synchronized (this) {
                        if (closed) return;
                        webrtc.setLocalDescription(answer);
                    }
                    events.localDescription("answer", answer.getSDPMessage().toString());
                });
            }
        }
    }

    @Override
    public synchronized void iceCandidate(int index, String candidate) {
        if (closed || candidate == null || candidate.isBlank()) return;
        webrtc.addIceCandidate(index, candidate);
    }

    private void checkConnection() {
        if (closed) return;
        WebRTCPeerConnectionState state;
        synchronized (this) {
            if (closed) return;
            state = webrtc.getConnectionState();
        }
        if (!connected && state == WebRTCPeerConnectionState.CONNECTED) {
            connected = true;
            events.connected();
        } else if (state == WebRTCPeerConnectionState.FAILED) {
            events.failed("ice");
            watch.shutdown();
        } else if (!connected && System.nanoTime() - startedAt > TimeUnit.SECONDS.toNanos(CONNECT_TIMEOUT_SECONDS)) {
            events.failed("timeout");
            watch.shutdown();
        }
    }

    // ---------------------------------------------------------------- switches

    @Override
    public synchronized void setMicrophoneOn(boolean on) {
        if (closed) return;
        Element mic = pipe.getElementByName("mic");
        if (mic != null) mic.set("mute", !on);
    }

    @Override
    public synchronized void setCameraOn(boolean on) {
        cameraOn = on;
        if (closed || selector == null || sharing) return;
        select(on && hasCamera ? cameraPad : blackPad);
    }

    @Override
    public synchronized void setScreenSharing(boolean on) {
        if (closed || selector == null || on == sharing) return;
        if (on) {
            try {
                screenBin = Gst.parseBinFromDescription(screenSource() + " ! videoconvert ! videoscale add-borders=true"
                        + " ! videorate ! " + VIDEO_CAPS + " ! queue", true);
                pipe.add(screenBin);
                screenPad = selector.getRequestPad("sink_%u");
                screenBin.getSrcPads().getFirst().link(screenPad);
                if (!screenBin.syncStateWithParent()) throw new IllegalStateException("screen source did not start");
                select(screenPad);
                sharing = true;
            } catch (RuntimeException e) {
                LOG.warn("Sharing the screen failed", e);
                removeScreen();
                events.screenFailed(e.getMessage());
            }
        } else {
            sharing = false;
            select(cameraOn && hasCamera ? cameraPad : blackPad);
            removeScreen();
        }
    }

    private String screenSource() {
        return settings.sources() == Sources.HEADLESS
                ? "videotestsrc is-live=true pattern=snow"
                : "ximagesrc use-damage=false show-pointer=true ! video/x-raw,framerate=15/1";
    }

    private void removeScreen() {
        if (screenBin != null) {
            screenBin.setState(State.NULL);
            if (screenPad != null) {
                screenBin.getSrcPads().getFirst().unlink(screenPad);
                selector.releaseRequestPad(screenPad);
            }
            pipe.remove(screenBin);
            screenBin = null;
            screenPad = null;
        }
    }

    private void select(Pad pad) {
        if (selector != null && pad != null) selector.set("active-pad", pad);
    }

    // ---------------------------------------------------------------- frames and errors

    private void appSink(String name, Consumer<BufferedImage> to) {
        AppSink sink = (AppSink) pipe.getElementByName(name);
        sink.connect((AppSink.NEW_SAMPLE) s -> frame(s, to));
    }

    private FlowReturn frame(AppSink sink, Consumer<BufferedImage> to) {
        Sample sample = sink.pullSample();
        if (sample == null) return FlowReturn.EOS;
        try {
            Structure s = sample.getCaps().getStructure(0);
            int width = s.getInteger("width"), height = s.getInteger("height");
            Buffer buffer = sample.getBuffer();
            ByteBuffer bytes = buffer.map(false);
            try {
                to.accept(image(bytes, width, height));
            } finally {
                buffer.unmap();
            }
        } catch (RuntimeException e) {
            LOG.debug("Skipping a frame: {}", e.toString());
        } finally {
            sample.dispose();
        }
        return FlowReturn.OK;
    }

    /** BGRx bytes as an RGB image: read as little-endian ints they are 0xXXRRGGBB. */
    static BufferedImage image(ByteBuffer bytes, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        bytes.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels, 0, Math.min(pixels.length, bytes.remaining() / 4));
        return image;
    }

    private void onError(GstObject source, int code, String message) {
        String name = source.getName();
        LOG.warn("GStreamer error from {}: {}", name, message);
        if (closed) return;
        if (isCameraElement(source)) {
            synchronized (this) {
                hasCamera = false;
                if (!sharing) select(blackPad);
            }
            events.cameraFailed(message);
        } else if (screenBin != null && name.startsWith("ximagesrc")) {
            synchronized (this) {
                setScreenSharing(false);
            }
            events.screenFailed(message);
        } else {
            events.failed(message);
        }
    }

    private static boolean isCameraElement(GstObject source) {
        for (GstObject o = source; o != null; o = o.getParent()) {
            String n = o.getName();
            if ("cam".equals(n) || n.toLowerCase(Locale.ROOT).startsWith("decodebin")) return true;
        }
        return false;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        watch.shutdownNow();
        dispose();
    }

    private synchronized void dispose() {
        if (pipe != null) {
            pipe.setState(State.NULL);
            pipe.dispose();
            pipe = null;
        }
    }
}
