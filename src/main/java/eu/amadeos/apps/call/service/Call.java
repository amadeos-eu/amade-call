package eu.amadeos.apps.call.service;

import eu.amadeos.apps.call.domain.CallKind;
import eu.amadeos.apps.call.domain.ChatLine;
import eu.amadeos.apps.call.domain.EndReason;
import eu.amadeos.apps.call.domain.Endpoint;
import eu.amadeos.apps.call.domain.PeerKey;
import eu.amadeos.apps.call.media.MediaEvents;
import eu.amadeos.apps.call.media.MediaFactory;
import eu.amadeos.apps.call.media.MediaSession;
import eu.amadeos.apps.call.media.MediaSettings;
import eu.amadeos.apps.call.protocol.Signal;
import eu.amadeos.apps.call.protocol.SignalChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * One call, from the first ring to the end, without any window: the signals on the TLS connection on one side,
 * the {@link MediaSession} on the other. The caller dials and says hello; the callee rings until the user
 * answers; then the caller's media makes the WebRTC offer, the callee's media the answer, both send their ICE
 * candidates, and the call is active as soon as picture and sound flow.
 *
 * <p>Every method may be called from any thread; the listener is called on background threads.
 */
public final class Call {
    private static final Logger LOG = LoggerFactory.getLogger(Call.class);
    /** How long an outgoing call rings before it is given up. */
    public static final Duration RING_TIME = Duration.ofSeconds(45);
    /** How long an incoming call rings at most (the caller usually gives up first). */
    public static final Duration INCOMING_RING_TIME = Duration.ofSeconds(60);
    static final int CONNECT_TIMEOUT_MILLIS = 6000;

    public enum State {
        /** Outgoing: connecting to the other computer. */
        DIALING,
        /** Outgoing: it rings at the other end. */
        CALLING,
        /** Incoming: it rings here. */
        RINGING,
        /** Answered; picture and sound are being set up. */
        CONNECTING,
        ACTIVE,
        ENDED
    }

    public enum Direction { OUTGOING, INCOMING }

    /** What the window shows of the call. */
    public interface Listener {
        void state(Call call, State state);

        void ended(Call call, EndReason reason);

        default void remoteFrame(BufferedImage frame) {
        }

        default void localFrame(BufferedImage frame) {
        }

        default void chat(ChatLine line) {
        }

        default void remoteCamera(boolean on) {
        }

        default void remoteScreen(boolean on) {
        }

        default void remoteMicrophone(boolean on) {
        }

        default void cameraFailed() {
        }

        default void screenFailed() {
        }
    }

    /** Opens the TLS connection of an outgoing call. */
    @FunctionalInterface
    public interface Dialer {
        SignalChannel dial(Endpoint endpoint, int timeoutMillis) throws IOException;
    }

    private final Direction direction;
    private final CallKind kind;
    private final MediaFactory media;
    private final MediaSettings mediaSettings;
    private final ScheduledExecutorService timers;
    private final Supplier<Instant> clock;
    private final Instant created;
    private final List<Signal> pendingIce = new ArrayList<>();
    private Listener listener;
    private SignalChannel channel;
    private PeerKey peer;
    private String peerName;
    /** This side's name, sent with the answer of an incoming call. */
    private String myName = "";
    private Optional<Endpoint> endpoint;
    private MediaSession session;
    private State state;
    private EndReason endReason;
    private Instant activeSince;
    private Instant endedAt;
    private ScheduledFuture<?> ringTimeout;
    private boolean microphoneOn = true;
    private boolean cameraOn;
    private boolean sharing;
    private boolean remoteCameraOn;

    private Call(Direction direction, CallKind kind, MediaFactory media, MediaSettings mediaSettings,
                 ScheduledExecutorService timers, Supplier<Instant> clock) {
        this.direction = direction;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.media = media;
        this.mediaSettings = mediaSettings.withKind(kind);
        this.timers = timers;
        this.clock = clock;
        this.created = clock.get();
        this.cameraOn = kind.video() && mediaSettings.cameraOn();
    }

    // ---------------------------------------------------------------- outgoing

    /**
     * Calls {@code endpoint}. {@code expected} is the key the person is known by (from the network or from
     * earlier calls); a computer that answers with another key is not called.
     */
    public static Call dial(Endpoint endpoint, Optional<PeerKey> expected, String peerName, CallKind kind,
                            String myName, Dialer dialer, MediaFactory media, MediaSettings mediaSettings,
                            ScheduledExecutorService timers, Supplier<Instant> clock, Listener listener) {
        Call call = new Call(Direction.OUTGOING, kind, media, mediaSettings.withOfferer(true), timers, clock);
        call.listener = listener;
        call.peerName = peerName;
        call.peer = expected.orElse(null);
        call.endpoint = Optional.of(endpoint);
        call.state = State.DIALING;
        Thread.ofVirtual().name("call-dial").start(() -> call.connect(endpoint, expected, myName, dialer));
        return call;
    }

    private void connect(Endpoint endpoint, Optional<PeerKey> expected, String myName, Dialer dialer) {
        SignalChannel ch;
        try {
            ch = dialer.dial(endpoint, CONNECT_TIMEOUT_MILLIS);
        } catch (IOException | RuntimeException e) {
            LOG.info("Could not reach {}: {}", endpoint, e.toString());
            end(EndReason.UNREACHABLE, false);
            return;
        }
        if (expected.isPresent() && !expected.get().equals(ch.peer())) {
            LOG.warn("{} answered with key {} instead of {}", endpoint, ch.peer().shortText(), expected.get().shortText());
            ch.close();
            synchronized (this) {
                peer = ch.peer();
            }
            end(EndReason.WRONG_KEY, false);
            return;
        }
        synchronized (this) {
            if (state == State.ENDED) {
                ch.send(Signal.bye());
                ch.close();
                return;
            }
            channel = ch;
            peer = ch.peer();
            state = State.CALLING;
            ringTimeout = timers.schedule(() -> end(EndReason.NO_ANSWER, true), RING_TIME.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
        ch.listen(this::onSignal, this::onClosed);
        ch.send(Signal.hello(myName, kind, cameraOn));
        listener.state(this, State.CALLING);
    }

    // ---------------------------------------------------------------- incoming

    /** A call that came in with its HELLO; it rings until {@link #accept} or {@link #decline}. */
    public static Call incoming(SignalChannel channel, Signal hello, String myName, Optional<Endpoint> endpoint,
                                MediaFactory media, MediaSettings mediaSettings, ScheduledExecutorService timers,
                                Supplier<Instant> clock) {
        Call call = new Call(Direction.INCOMING, hello.kind(), media, mediaSettings.withOfferer(false), timers, clock);
        call.channel = channel;
        call.peer = channel.peer();
        call.peerName = hello.text("name");
        call.myName = myName;
        call.endpoint = endpoint;
        call.remoteCameraOn = hello.kind().video() && !Boolean.FALSE.equals(hello.fields().get("video"));
        call.state = State.RINGING;
        return call;
    }

    /** Starts listening once the window is there to hear about it. */
    public void ring(Listener newListener) {
        synchronized (this) {
            listener = newListener;
            ringTimeout = timers.schedule(() -> {
                synchronized (this) {
                    if (state != State.RINGING) return;
                }
                if (channel != null) channel.send(Signal.decline(myName, false));
                end(EndReason.MISSED, false);
            }, INCOMING_RING_TIME.toMillis(), TimeUnit.MILLISECONDS);
        }
        channel.listen(this::onSignal, this::onClosed);
        listener.state(this, State.RINGING);
    }

    // --8<-- [start:accept]
    /** Answers; {@code withCamera} false answers a video call with sound only (the camera can be switched on later). */
    public void accept(boolean withCamera) {
        synchronized (this) {
            if (state != State.RINGING) return;
            cancelRing();
            cameraOn = kind.video() && withCamera;
            state = State.CONNECTING;
        }
        // Ready for the offer before the caller hears the answer.
        startMedia();
        channel.send(Signal.accept(myName, cameraOn));
        listener.state(this, State.CONNECTING);
    }

    public void decline() {
        synchronized (this) {
            if (state != State.RINGING) return;
        }
        channel.send(Signal.decline(myName, false));
        end(EndReason.DECLINED, false);
    }
    // --8<-- [end:accept]

    /** Tells a caller that this computer is in another call. */
    public static void busy(SignalChannel channel, String myName) {
        channel.send(Signal.decline(myName, true));
        channel.close();
    }

    // ---------------------------------------------------------------- during the call

    public void hangUp() {
        end(EndReason.HUNG_UP, true);
    }

    public void setMicrophoneOn(boolean on) {
        MediaSession s;
        synchronized (this) {
            microphoneOn = on;
            s = session;
        }
        if (s != null) s.setMicrophoneOn(on);
        send(Signal.microphone(on));
    }

    public void setCameraOn(boolean on) {
        MediaSession s;
        synchronized (this) {
            if (!kind.video()) return;
            cameraOn = on;
            s = session;
        }
        if (s != null) s.setCameraOn(on);
        send(Signal.camera(on));
    }

    public void setScreenSharing(boolean on) {
        MediaSession s;
        synchronized (this) {
            if (!kind.video()) return;
            sharing = on;
            s = session;
        }
        if (s != null) s.setScreenSharing(on);
        send(Signal.screen(on));
    }

    public void sendChat(String text) {
        if (text == null || text.isBlank()) return;
        ChatLine line = new ChatLine(true, text.strip(), clock.get());
        if (send(Signal.chat(line.text()))) listener.chat(line);
    }

    private boolean send(Signal signal) {
        SignalChannel ch;
        synchronized (this) {
            ch = channel;
            if (state == State.ENDED) return false;
        }
        return ch != null && ch.send(signal);
    }

    // ---------------------------------------------------------------- signals

    /** The name the other side gives itself replaces the address a call was dialled with. */
    private void learnName(Signal signal) {
        String name = signal.text("name").strip();
        if (!name.isEmpty()) peerName = name.length() > 80 ? name.substring(0, 80) : name;
    }

    void onSignal(Signal signal) {
        switch (signal.type()) {
            case ACCEPT -> {
                synchronized (this) {
                    if (state != State.CALLING) return;
                    cancelRing();
                    state = State.CONNECTING;
                    remoteCameraOn = signal.flag("video");
                    learnName(signal);
                }
                startMedia();
                listener.remoteCamera(remoteCameraOn);
                listener.state(this, State.CONNECTING);
            }
            case DECLINE -> {
                synchronized (this) {
                    learnName(signal);
                }
                end("busy".equals(signal.text("reason")) ? EndReason.BUSY : EndReason.DECLINED, false);
            }
            case OFFER, ANSWER -> {
                MediaSession s;
                synchronized (this) {
                    s = session;
                }
                if (s != null) s.remoteDescription(signal.type() == Signal.Type.OFFER ? "offer" : "answer",
                        signal.text("sdp"));
            }
            case ICE -> {
                MediaSession s;
                synchronized (this) {
                    s = session;
                    if (s == null) {
                        pendingIce.add(signal);
                        return;
                    }
                }
                s.iceCandidate(signal.number("index"), signal.text("candidate"));
            }
            case CHAT -> listener.chat(new ChatLine(false, signal.text("text"), clock.get()));
            case CAMERA -> {
                synchronized (this) {
                    remoteCameraOn = signal.flag("on");
                }
                listener.remoteCamera(signal.flag("on"));
            }
            case SCREEN -> listener.remoteScreen(signal.flag("on"));
            case MICROPHONE -> listener.remoteMicrophone(signal.flag("on"));
            case BYE -> {
                boolean ringing;
                synchronized (this) {
                    ringing = state == State.RINGING;
                }
                end(ringing ? EndReason.MISSED : EndReason.REMOTE_HUNG_UP, false);
            }
            case HELLO -> LOG.debug("A second hello on the same call; ignored");
        }
    }

    private void onClosed() {
        State s;
        synchronized (this) {
            s = state;
        }
        if (s == State.ENDED) return;
        end(s == State.RINGING ? EndReason.MISSED : EndReason.CONNECTION_LOST, false);
    }

    // ---------------------------------------------------------------- media

    /**
     * Starts picture and sound. Not under the lock: GStreamer calls back (offer, ICE) from its own threads while
     * the pipeline starts, and those callbacks send through this call.
     */
    private void startMedia() {
        MediaSession s;
        boolean mic;
        try {
            s = media.create(mediaSettings.withCameraOn(cameraOn()), new Events());
            synchronized (this) {
                if (state == State.ENDED) {
                    return;
                }
                session = s;
                mic = microphoneOn;
            }
            s.start();
            if (!mic) s.setMicrophoneOn(false);
            List<Signal> ice;
            synchronized (this) {
                ice = List.copyOf(pendingIce);
                pendingIce.clear();
            }
            for (Signal c : ice) s.iceCandidate(c.number("index"), c.text("candidate"));
        } catch (RuntimeException e) {
            LOG.warn("Picture and sound could not be set up", e);
            timers.execute(() -> end(EndReason.MEDIA_FAILED, true));
        }
    }

    private final class Events implements MediaEvents {
        @Override
        public void localDescription(String type, String sdp) {
            send("offer".equals(type) ? Signal.offer(sdp) : Signal.answer(sdp));
        }

        @Override
        public void iceCandidate(int index, String candidate) {
            send(Signal.ice(index, candidate));
        }

        @Override
        public void remoteFrame(BufferedImage frame) {
            listener.remoteFrame(frame);
        }

        @Override
        public void localFrame(BufferedImage frame) {
            listener.localFrame(frame);
        }

        @Override
        public void connected() {
            synchronized (Call.this) {
                if (state != State.CONNECTING) return;
                state = State.ACTIVE;
                activeSince = clock.get();
            }
            listener.state(Call.this, State.ACTIVE);
        }

        @Override
        public void failed(String reason) {
            LOG.warn("Call media failed: {}", reason);
            end(EndReason.MEDIA_FAILED, true);
        }

        @Override
        public void cameraFailed(String reason) {
            synchronized (Call.this) {
                cameraOn = false;
            }
            send(Signal.camera(false));
            listener.cameraFailed();
        }

        @Override
        public void screenFailed(String reason) {
            synchronized (Call.this) {
                sharing = false;
            }
            send(Signal.screen(false));
            listener.screenFailed();
        }
    }

    // ---------------------------------------------------------------- the end

    private void end(EndReason reason, boolean sayBye) {
        SignalChannel ch;
        MediaSession s;
        synchronized (this) {
            if (state == State.ENDED) return;
            state = State.ENDED;
            endReason = reason;
            endedAt = clock.get();
            cancelRing();
            ch = channel;
            s = session;
            session = null;
        }
        if (ch != null) {
            if (sayBye) ch.send(Signal.bye());
            ch.close();
        }
        if (s != null) {
            // Closing the pipeline can take a moment; the window does not wait for it.
            Thread.ofVirtual().name("call-media-close").start(s::close);
        }
        LOG.info("Call with {} ended: {}", peerName, reason);
        if (listener != null) {
            listener.state(this, State.ENDED);
            listener.ended(this, reason);
        }
    }

    private void cancelRing() {
        if (ringTimeout != null) ringTimeout.cancel(false);
        ringTimeout = null;
    }

    // ---------------------------------------------------------------- what the window asks

    public Direction direction() {
        return direction;
    }

    public CallKind kind() {
        return kind;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized Optional<EndReason> endReason() {
        return Optional.ofNullable(endReason);
    }

    /** The other side's key; empty while an outgoing call to an unknown address is still connecting. */
    public synchronized Optional<PeerKey> peer() {
        return Optional.ofNullable(peer);
    }

    public synchronized String peerName() {
        return peerName == null ? "" : peerName;
    }

    public synchronized Optional<Endpoint> endpoint() {
        return endpoint;
    }

    public Instant created() {
        return created;
    }

    /** How long picture and sound flowed; zero for a call that never became active. */
    public synchronized Duration length() {
        if (activeSince == null) return Duration.ZERO;
        return Duration.between(activeSince, endedAt == null ? clock.get() : endedAt);
    }

    public synchronized boolean microphoneOn() {
        return microphoneOn;
    }

    public synchronized boolean cameraOn() {
        return cameraOn;
    }

    public synchronized boolean sharing() {
        return sharing;
    }

    public synchronized boolean remoteCameraOn() {
        return remoteCameraOn;
    }

    /** Whether the call still runs or rings. */
    public synchronized boolean live() {
        return state != State.ENDED;
    }
}
