package eu.amadeos.apps.call.ui.main;

import eu.amadeos.apps.call.domain.CallKind;
import eu.amadeos.apps.call.domain.CallRecord;
import eu.amadeos.apps.call.domain.ChatLine;
import eu.amadeos.apps.call.domain.PeerKey;
import eu.amadeos.apps.call.domain.Person;
import eu.amadeos.apps.call.domain.SafetyCode;
import eu.amadeos.apps.call.media.Devices;
import eu.amadeos.apps.call.media.FakeMedia;
import eu.amadeos.apps.call.media.Sources;
import eu.amadeos.apps.call.repository.CallLog;
import eu.amadeos.apps.call.repository.ChatHistory;
import eu.amadeos.apps.call.repository.CallPreferences;
import eu.amadeos.apps.call.repository.KnownPeers;
import eu.amadeos.apps.call.service.Autostart;
import eu.amadeos.apps.call.service.Discovery;
import eu.amadeos.apps.call.service.IdentityStore;
import eu.amadeos.apps.call.service.Phone;
import eu.amadeos.apps.call.service.Ringer;
import eu.amadeos.apps.call.ui.call.CallView;
import eu.amadeos.apps.call.ui.dialogs.IncomingView;
import eu.amadeos.commons.i18n.Messages;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static eu.amadeos.apps.call.Wait.until;

/** One program for tests: presenter, phone, files and fake windows, with its own "event thread". */
final class TestSide {
    static final Messages MSG = Messages.load(MainPresenter.class, "eu.amadeos.apps.call.messages", Locale.ENGLISH);

    final ExecutorService edt = Executors.newSingleThreadExecutor();
    final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
    final FakeMedia media = new FakeMedia();
    /** The fake main window; null when the side runs with a real one. */
    final FakeView view;
    final RecordingRinger ringer = new RecordingRinger();
    final AtomicBoolean exited = new AtomicBoolean();
    final Phone phone;
    final KnownPeers known;
    final CallLog log;
    final ChatHistory chats;
    final CallPreferences preferences;
    final Autostart autostart;
    final MainPresenter presenter;
    private final boolean onEdt;

    TestSide(Path home, String name) {
        this(home, name, null, null);
    }

    /** A side whose presenter talks to {@code window} on {@code ui} instead of the fake view and its own thread. */
    TestSide(Path home, String name, MainView window, java.util.concurrent.Executor ui) {
        this(home, name, window, ui, null, Sources.HEADLESS, Discovery.none());
    }

    /** With real media ({@code realMedia}, for screenshots) instead of the fake one. */
    TestSide(Path home, String name, MainView window, java.util.concurrent.Executor ui,
             eu.amadeos.apps.call.media.MediaFactory realMedia, Sources sources, Discovery discovery) {
        eu.amadeos.apps.call.media.MediaFactory used = realMedia == null ? media : realMedia;
        view = window == null ? new FakeView() : null;
        onEdt = ui != null;
        phone = new Phone(new IdentityStore(home), discovery, used, Instant::now);
        known = new KnownPeers(home.resolve("people.properties"));
        log = new CallLog(home.resolve("calls.tsv"));
        javax.crypto.SecretKey chatKey = new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
        chats = new ChatHistory(home.resolve("chats"), () -> java.util.Optional.of(chatKey));
        preferences = new CallPreferences(home.resolve("call.properties"), name);
        preferences.setPort(0);
        autostart = new Autostart(home.resolve("config"));
        Devices devices = new Devices(null) {
            @Override
            public List<Choice> microphones() {
                return List.of(new Choice("mic", "Microphone"));
            }

            @Override
            public List<Choice> speakers() {
                return List.of();
            }
        };
        presenter = new MainPresenter(window == null ? view : window, MSG, null, new MainPresenter.Services(phone,
                used, devices, known, log, preferences, chats, autostart, ringer, sources, background,
                ui == null ? edt : ui, Instant::now,
                () -> exited.set(true)));
    }

    void start() throws Exception {
        on(presenter::start);
        until("started", () -> phone.port() > 0 && phone.self() != null && (view == null || view.status.contains(String.valueOf(phone.port()))));
    }

    /** Runs on the event thread and waits for it. */
    void on(Runnable action) throws Exception {
        if (onEdt) {
            eu.amadeos.apps.call.OnEdt.run(action);
        } else {
            edt.submit(action).get();
        }
    }

    FakeView.Window lastWindow() {
        return view.windows.getLast();
    }

    SettingsModel openSettings() throws Exception {
        on(presenter::openSettings);
        until("settings", () -> view.settings != null);
        return view.settings;
    }

    void shutdown() throws Exception {
        on(presenter::exit);
        edt.shutdownNow();
        background.shutdownNow();
    }

    static final class RecordingRinger extends Ringer {
        final List<Tone> tones = new CopyOnWriteArrayList<>();

        @Override
        public void start(Tone tone) {
            tones.add(tone);
        }

        @Override
        public void stop() {
        }

        boolean contains(Tone tone) {
            return tones.contains(tone);
        }
    }

    /** Records what the presenter shows. */
    static final class FakeView implements MainView {
        String name = "";
        volatile String status = "";
        final List<String> errors = new CopyOnWriteArrayList<>();
        volatile List<Person> people = List.of();
        volatile List<String> missingParts = List.of();
        final List<Window> windows = new CopyOnWriteArrayList<>();
        final List<String> missed = new CopyOnWriteArrayList<>();
        volatile Incoming incoming;
        volatile String incomingName;
        volatile CallKind incomingKind;
        volatile String address = "";
        volatile Verdict verdict = Verdict.LATER;
        volatile SafetyCode lastCode;
        volatile boolean forget;
        volatile SettingsModel settings;
        volatile boolean hidden;
        volatile boolean closed;

        @Override
        public void showPeople(List<Person> list, Optional<PeerKey> selected) {
            people = List.copyOf(list);
        }

        @Override
        public void showDetails(Optional<Person> person, List<CallRecord> calls, boolean inCall) {
        }

        @Override
        public void showStatus(String text) {
            status = text;
        }

        @Override
        public void showError(String message) {
            errors.add(message);
        }

        @Override
        public void showMissing(List<String> parts) {
            missingParts = List.copyOf(parts);
        }

        @Override
        public Optional<String> askAddress(String previous) {
            return Optional.of(address);
        }

        @Override
        public void showSettings(SettingsModel model, Consumer<SettingsModel> apply) {
            settings = model;
        }

        @Override
        public Verdict askSafetyCode(String who, SafetyCode code, boolean verified) {
            lastCode = code;
            return verdict;
        }

        @Override
        public boolean confirmClearChat(String who) {
            return forget;
        }

        @Override
        public boolean confirmForget(String who) {
            return forget;
        }

        @Override
        public CallView openCall(String who, CallKind kind, boolean verified) {
            Window w = new Window();
            w.kind = kind;
            windows.add(w);
            return w;
        }

        @Override
        public IncomingView showIncoming(String who, CallKind kind, boolean known, boolean verified) {
            incomingName = who;
            incomingKind = kind;
            Incoming i = new Incoming();
            incoming = i;
            return i;
        }

        @Override
        public void announceMissed(String who, Runnable callBack) {
            missed.add(who);
        }

        @Override
        public void showAbout(String ownKey) {
        }

        @Override
        public void bringToFront() {
            hidden = false;
        }

        @Override
        public void hideWindow() {
            hidden = true;
        }

        @Override
        public void close() {
            closed = true;
        }

        static final class Incoming implements IncomingView {
            volatile boolean closed;

            @Override
            public void close() {
                closed = true;
            }
        }

        static final class Window implements CallView {
            final List<String> phases = new CopyOnWriteArrayList<>();
            final List<ChatLine> chat = new CopyOnWriteArrayList<>();
            final List<List<Boolean>> switches = new CopyOnWriteArrayList<>();
            volatile boolean active;
            volatile int remoteFrames;
            volatile boolean remoteCamera = true;
            volatile boolean remoteScreen;
            volatile boolean remoteMuted;
            volatile String ended;
            volatile CallKind kind;
            volatile List<ChatLine> history = List.of();
            volatile boolean closed;

            @Override
            public void showPhase(String text) {
                phases.add(text);
            }

            @Override
            public void showActive(Instant since) {
                active = true;
            }

            @Override
            public void showRemoteFrame(BufferedImage frame) {
                remoteFrames++;
            }

            @Override
            public void showLocalFrame(BufferedImage frame) {
            }

            @Override
            public void showRemoteCamera(boolean on) {
                remoteCamera = on;
            }

            @Override
            public void showRemoteScreen(boolean sharing) {
                remoteScreen = sharing;
            }

            @Override
            public void showRemoteMuted(boolean muted) {
                remoteMuted = muted;
            }

            @Override
            public void showSwitches(boolean microphone, boolean camera, boolean sharing, boolean video) {
                switches.add(List.of(microphone, camera, sharing, video));
            }

            @Override
            public void showChat(ChatLine line) {
                chat.add(line);
            }

            @Override
            public void showHistory(List<ChatLine> earlier) {
                history = List.copyOf(earlier);
            }

            @Override
            public void showVerified(boolean verified) {
            }

            @Override
            public void showInfo(String text) {
            }

            @Override
            public void showEnded(String text) {
                ended = text;
            }

            @Override
            public void close() {
                closed = true;
            }
        }
    }
}
