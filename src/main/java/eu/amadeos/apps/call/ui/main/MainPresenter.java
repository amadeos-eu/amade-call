package eu.amadeos.apps.call.ui.main;

import eu.amadeos.apps.call.domain.CallKind;
import eu.amadeos.apps.call.domain.CallLink;
import eu.amadeos.apps.call.domain.CallRecord;
import eu.amadeos.apps.call.domain.ChatFile;
import eu.amadeos.apps.call.domain.ChatLine;
import eu.amadeos.apps.call.domain.EndReason;
import eu.amadeos.apps.call.domain.Endpoint;
import eu.amadeos.apps.call.domain.PeerKey;
import eu.amadeos.apps.call.domain.Person;
import eu.amadeos.apps.call.domain.SafetyCode;
import eu.amadeos.apps.call.media.Devices;
import eu.amadeos.apps.call.media.MediaFactory;
import eu.amadeos.apps.call.media.MediaSettings;
import eu.amadeos.apps.call.media.Sources;
import eu.amadeos.apps.call.repository.AddressBook;
import eu.amadeos.apps.call.repository.CallLog;
import eu.amadeos.apps.call.repository.CallPreferences;
import eu.amadeos.apps.call.repository.ChatHistory;
import eu.amadeos.apps.call.repository.KnownPeers;
import eu.amadeos.apps.call.service.Autostart;
import eu.amadeos.apps.call.service.Call;
import eu.amadeos.apps.call.service.LocalAddress;
import eu.amadeos.apps.call.service.NearbyPeer;
import eu.amadeos.apps.call.service.Phone;
import eu.amadeos.apps.call.service.Ringer;
import eu.amadeos.apps.call.ui.call.CallView;
import eu.amadeos.apps.call.ui.dialogs.IncomingView;
import eu.amadeos.commons.desktop.DesktopLauncher;
import eu.amadeos.commons.i18n.Language;
import eu.amadeos.commons.i18n.LocaleSettings;
import eu.amadeos.commons.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Everything amade-call does, without Swing: the list of people (seen in the network and called before), the
 * calls with their windows, the ring, the safety codes, the settings. The windows are reached only through
 * {@link MainView}, {@link CallView} and {@link IncomingView}, so the tests drive whole calls without them.
 */
public class MainPresenter {
    private static final Logger LOG = LoggerFactory.getLogger(MainPresenter.class);

    /** What the presenter works with. */
    public record Services(Phone phone, MediaFactory media, Devices devices, KnownPeers known, CallLog log,
                           CallPreferences preferences, ChatHistory chats, AddressBook contacts,
                           Path downloads, DesktopLauncher launcher,
                           Autostart autostart, Ringer ringer,
                           Sources sources,
                           ExecutorService background, Executor ui, Supplier<Instant> clock, Runnable exit) {
    }

    private final MainView view;
    private final Messages msg;
    private final LocaleSettings locales;
    private final Services s;
    private final Map<PeerKey, NearbyPeer> nearby = new HashMap<>();
    private Optional<PeerKey> selected = Optional.empty();
    private boolean discovery = true;
    private boolean started;
    /** A link that came before the call port was open. */
    private CallLink pendingLink;
    /** This computer's name and addresses in the network, for the status line. */
    private String ownAddress = "";
    private String lastAddress = "";
    private Call call;
    private CallView callView;
    /** Download names handed out in this run, so two files of the same name at once do not collide. */
    private final java.util.Set<Path> reserved = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private IncomingView incomingView;
    /** The lines of the last chat (text only) with each person, so that a call that follows it carries them on. */
    private final Map<PeerKey, RecentChat> recentChats = new HashMap<>();
    /** The kind of call that follows the running chat, once the chat has ended. */
    private CallKind switchTo;

    /** A chat that ended at {@code ended}, with its window while that is still open. */
    private record RecentChat(List<ChatLine> lines, Instant ended, CallView window) {
    }

    /** How long after a chat a call with the same person still carries its lines. */
    static final Duration CHAT_CARRIES_OVER = Duration.ofMinutes(2);

    public MainPresenter(MainView view, Messages msg, LocaleSettings locales, Services services) {
        this.view = view;
        this.msg = msg;
        this.locales = locales;
        this.s = services;
    }

    // ---------------------------------------------------------------- start

    /** Opens the call port and the network announcement on a background thread (the first start makes the key). */
    public void start() {
        refreshPeople();
        view.showStatus(msg.get("status.starting"));
        List<String> missing = s.media().missing(s.sources());
        if (!missing.isEmpty()) view.showMissing(missing);
        s.background().execute(() -> {
            try {
                s.phone().start(s.preferences().name(), s.preferences().port(), this::mediaSettings, new PhoneEvents());
                String address = LocalAddress.describe();
                s.ui().execute(() -> {
                    ownAddress = address;
                    started = true;
                    showStatus();
                    if (pendingLink != null) {
                        CallLink link = pendingLink;
                        pendingLink = null;
                        open(link);
                    }
                });
            } catch (IOException | RuntimeException e) {
                LOG.error("amade-call could not start", e);
                s.ui().execute(() -> {
                    view.showStatus(msg.get("status.offline"));
                    view.showError(msg.format("error.start", e.getMessage()));
                });
            }
        });
    }

    private void showStatus() {
        if (!started) return;
        String status = msg.format("status.reachable", s.preferences().name(), String.valueOf(s.phone().port()));
        if (!ownAddress.isEmpty()) status += " (" + ownAddress + ")";
        view.showStatus(status + "  ·  " + msg.get(discovery ? "status.network-on" : "status.network-off"));
    }

    /** The settings for the media of the next call. */
    MediaSettings mediaSettings() {
        CallPreferences p = s.preferences();
        return new MediaSettings(CallKind.VIDEO, false, p.cameraOnAtStart(), p.camera(), p.microphone(), p.speaker(),
                p.echoCancellation(), s.sources());
    }

    /** From a second start of the program: show the window. */
    /** A wish from a second start: show the window, and maybe call ({@code call amadeos-call:...}). */
    public void message(String text) {
        s.ui().execute(() -> {
            view.bringToFront();
            if (text.startsWith("call ")) CallLink.parse(text.substring(5)).ifPresent(this::open);
        });
    }

    /** Calls the person of a link, for example from Contacts; waits for the start when it comes early. */
    public void open(CallLink link) {
        if (!started) {
            pendingLink = link;
            return;
        }
        Optional<Person> person = people().stream().filter(p -> p.key().equals(link.key())).findFirst();
        if (person.isEmpty()) {
            view.showError(msg.get("error.link.unknown"));
            return;
        }
        select(link.key());
        call(person.get(), link.kind());
    }

    /** Person → Add to Contacts: the link into a contact of the address book, or a new contact. */
    public void addToContacts() {
        Optional<Person> person = selectedPerson();
        if (person.isEmpty()) return;
        Person p = person.get();
        s.background().execute(() -> {
            List<AddressBook.Entry> all = s.contacts().contacts();
            s.ui().execute(() -> {
                Optional<AddressBook.Entry> linked = all.stream().filter(e -> e.links(p.key())).findFirst();
                if (linked.isPresent()) {
                    view.showInfo(msg.format("info.contact.already", p.name(), linked.get().name()));
                    return;
                }
                List<String> names = all.stream().map(AddressBook.Entry::name).toList();
                Optional<Integer> choice = view.chooseContact(p.name(), names);
                if (choice.isEmpty()) return;
                int i = choice.get();
                s.background().execute(() -> {
                    try {
                        String target;
                        if (i < 0) {
                            s.contacts().create(p.name(), p.key(), s.clock().get());
                            target = p.name();
                        } else {
                            s.contacts().link(all.get(i).file(), p.key());
                            target = all.get(i).name();
                        }
                        s.ui().execute(() -> view.showInfo(msg.format("info.contact.added", p.name(), target)));
                    } catch (IOException | RuntimeException e) {
                        LOG.warn("No contact for {}", p.name(), e);
                        s.ui().execute(() -> view.showError(msg.format("error.contact", e.getMessage())));
                    }
                });
            });
        });
    }

    // ---------------------------------------------------------------- the list of people

    private final class PhoneEvents implements Phone.Listener {
        @Override
        public void found(NearbyPeer peer) {
            s.ui().execute(() -> {
                nearby.put(peer.key(), peer);
                refreshPeople();
            });
        }

        @Override
        public void lost(PeerKey key) {
            s.ui().execute(() -> {
                nearby.remove(key);
                refreshPeople();
            });
        }

        @Override
        public void unavailable(String reason) {
            s.ui().execute(() -> {
                discovery = false;
                showStatus();
            });
        }

        @Override
        public void incoming(Call c) {
            s.ui().execute(() -> ringing(c));
        }
    }

    /** People seen in the network first, then those called before, each by name. */
    List<Person> people() {
        Map<PeerKey, Person> all = new LinkedHashMap<>();
        for (Person p : s.known().all()) all.put(p.key(), p);
        for (NearbyPeer n : nearby.values()) {
            boolean verified = s.known().verified(n.key());
            all.put(n.key(), new Person(n.key(), n.name(), Optional.of(n.endpoint()), true, verified));
        }
        List<Person> list = new ArrayList<>(all.values());
        list.sort(Comparator.comparing((Person p) -> !p.nearby()).thenComparing(Person::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    private void refreshPeople() {
        List<Person> list = people();
        if (selected.isEmpty() && !list.isEmpty()) selected = Optional.of(list.getFirst().key());
        if (selected.isPresent() && list.stream().noneMatch(p -> p.key().equals(selected.get()))) {
            selected = list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst().key());
        }
        view.showPeople(list, selected);
        showDetails();
    }

    private void showDetails() {
        Optional<Person> person = selectedPerson();
        view.showDetails(person, person.map(p -> s.log().with(p.key())).orElse(List.of()), call != null && call.live());
    }

    Optional<Person> selectedPerson() {
        return selected.flatMap(k -> people().stream().filter(p -> p.key().equals(k)).findFirst());
    }

    public void select(PeerKey key) {
        selected = Optional.ofNullable(key);
        showDetails();
    }

    // ---------------------------------------------------------------- calling out

    public void callSelected(CallKind kind) {
        selectedPerson().ifPresent(p -> call(p, kind));
    }

    public void call(Person person, CallKind kind) {
        if (person.endpoint().isEmpty()) {
            view.showError(msg.format("error.no-address", person.name()));
            return;
        }
        dial(person.endpoint().get(), Optional.of(person.key()), person.name(), kind, person.verified());
    }

    /** Call → Call Address: an address typed in, for computers that Avahi does not show. */
    public void callAddress(CallKind kind) {
        Optional<String> text = view.askAddress(lastAddress);
        if (text.isEmpty() || text.get().isBlank()) return;
        Optional<Endpoint> endpoint = Endpoint.parse(text.get());
        if (endpoint.isEmpty()) {
            view.showError(msg.format("error.address", text.get().strip()));
            return;
        }
        lastAddress = text.get().strip();
        dial(endpoint.get(), Optional.empty(), endpoint.get().toString(), kind, false);
    }

    private void dial(Endpoint endpoint, Optional<PeerKey> expected, String name, CallKind kind, boolean verified) {
        if (call != null && call.live()) {
            view.showError(msg.get("error.in-call"));
            return;
        }
        if (!started) {
            view.showError(msg.get("error.not-started"));
            return;
        }
        CallView window = view.openCall(name, kind, verified);
        CallEvents events = new CallEvents(window);
        window.showRemoteCamera(kind.video());
        Optional<Call> c = s.phone().call(endpoint, expected, name, kind, mediaSettings(), events);
        if (c.isEmpty()) {
            window.close();
            view.showError(msg.get("error.in-call"));
            return;
        }
        call = c.get();
        events.call = call;
        listeners.put(call, events);
        callView = window;
        expected.ifPresent(k -> carryChat(k, window));
        window.showPhase(msg.format("phase.dialing", name));
        window.showSwitches(true, call.cameraOn(), false, kind.video());
        s.ringer().start(Ringer.Tone.RINGBACK);
        showDetails();
    }

    // ---------------------------------------------------------------- calls coming in

    private void ringing(Call c) {
        Optional<Person> known = c.peer().flatMap(k -> s.known().get(k));
        String name = c.peerName().isBlank() ? known.map(Person::name).orElse("?") : c.peerName();
        incomingView = view.showIncoming(name, c.kind(), known.isPresent(), known.map(Person::verified).orElse(false));
        call = c;
        CallEvents events = new CallEvents(null);
        events.call = c;
        listeners.put(c, events);
        s.ringer().start(Ringer.Tone.RING);
        c.ring(events);
        showDetails();
    }

    /** The ringing call is answered; {@code withCamera} false answers a video call with sound only. */
    public void accept(boolean withCamera) {
        Call c = call;
        if (c == null || c.state() != Call.State.RINGING) return;
        s.ringer().stop();
        if (incomingView != null) incomingView.close();
        incomingView = null;
        boolean verified = c.peer().map(s.known()::verified).orElse(false);
        callView = view.openCall(c.peerName(), c.kind(), verified);
        CallView opened = callView;
        c.peer().ifPresent(k -> carryChat(k, opened));
        callView.showPhase(msg.get("phase.connecting"));
        callView.showRemoteCamera(c.remoteCameraOn());
        callView.showSwitches(true, c.kind().video() && withCamera, false, c.kind().video());
        listenerOf(c).ifPresent(e -> e.window = callView);
        c.accept(withCamera);
    }

    public void decline() {
        Call c = call;
        if (c == null || c.state() != Call.State.RINGING) return;
        c.decline();
    }

    private final Map<Call, CallEvents> listeners = new HashMap<>();

    private Optional<CallEvents> listenerOf(Call c) {
        return Optional.ofNullable(listeners.get(c));
    }

    // ---------------------------------------------------------------- during a call

    public void toggleMicrophone() {
        if (call == null || !call.live()) return;
        call.setMicrophoneOn(!call.microphoneOn());
        showSwitches();
    }

    public void toggleCamera() {
        if (call == null || !call.live() || !call.kind().video()) return;
        call.setCameraOn(!call.cameraOn());
        showSwitches();
    }

    public void toggleScreen() {
        if (call == null || !call.live() || !call.kind().video()) return;
        call.setScreenSharing(!call.sharing());
        showSwitches();
    }

    private void showSwitches() {
        if (callView != null && call != null) {
            callView.showSwitches(call.microphoneOn(), call.cameraOn(), call.sharing(), call.kind().video());
        }
    }

    public void sendChat(String text) {
        if (call != null && call.live()) call.sendChat(text);
    }

    // --8<-- [start:files]
    /** Sends pictures and files into the chat of the running call; says which could not go. */
    public void sendFiles(List<Path> files) {
        Call c = call;
        CallView w = callView;
        if (c == null || !c.live() || files.isEmpty()) return;
        s.background().execute(() -> {
            for (Path f : files) {
                if (!c.sendFile(f)) {
                    String text = msg.format("error.file.send", f.getFileName(),
                            ChatFile.sizeText(Call.maxFileSize(), msg.locale()));
                    s.ui().execute(() -> {
                        if (w != null) w.showInfo(text);
                    });
                }
            }
        });
    }

    /**
     * Opens a picture or file of the chat with its program. A received program or script is not started: its
     * folder opens instead.
     */
    public void openFile(Path file) {
        Path target = DesktopLauncher.isRisky(file.getFileName().toString()) && file.getParent() != null
                ? file.getParent() : file;
        s.background().execute(() -> {
            try {
                s.launcher().open(target);
            } catch (IOException | RuntimeException e) {
                s.ui().execute(() -> view.showError(msg.format("error.file.open", file.getFileName(), e.getMessage())));
            }
        });
    }

    /** A free name in the downloads folder for a file from the chat: "photo.jpg", else "photo (2).jpg" … */
    synchronized Optional<Path> downloadPath(String name) {
        Path dir = s.downloads().resolve("amade-call");
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            Path p = dir.resolve(i == 1 ? name : stem + " (" + i + ")" + ext);
            if (!Files.exists(p) && !Files.exists(p.resolveSibling(p.getFileName() + ".part"))
                    && reserved.add(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
    // --8<-- [end:files]

    public void hangUp() {
        if (call != null && call.live()) call.hangUp();
    }

    /** The safety code of the running call. */
    public void verifyCall() {
        if (call == null || call.peer().isEmpty() || s.phone().self() == null) return;
        PeerKey peer = call.peer().get();
        boolean verified = askSafetyCode(peer, call.peerName());
        if (callView != null) callView.showVerified(verified);
    }

    /** The safety code of the person chosen in the list (it was compared in an earlier call, or is read out on the phone). */
    public void verifySelected() {
        if (s.phone().self() == null) return;
        selectedPerson().ifPresent(p -> askSafetyCode(p.key(), p.name()));
    }

    /** Shows the code and keeps what the user said; true when the person counts as verified afterwards. */
    private boolean askSafetyCode(PeerKey peer, String name) {
        SafetyCode code = SafetyCode.of(s.phone().self(), peer);
        boolean verified = s.known().verified(peer);
        MainView.Verdict verdict = view.askSafetyCode(name, code, verified);
        if (verdict == MainView.Verdict.MATCH) {
            if (s.known().get(peer).isEmpty()) s.known().remember(peer, name, Optional.empty());
            s.known().setVerified(peer, true);
            verified = true;
        } else if (verdict == MainView.Verdict.DIFFERENT) {
            s.known().setVerified(peer, false);
            verified = false;
            view.showError(msg.format("safety.warning", name));
        }
        refreshPeople();
        return verified;
    }

    public void forgetSelected() {
        selectedPerson().ifPresent(p -> {
            if (!view.confirmForget(p.name())) return;
            s.known().forget(p.key());
            s.background().execute(() -> s.chats().delete(p.key()));
            refreshPeople();
        });
    }

    /** Deletes the kept chat with the selected person. */
    public void clearChatSelected() {
        selectedPerson().ifPresent(p -> {
            if (!view.confirmClearChat(p.name())) return;
            s.background().execute(() -> s.chats().delete(p.key()));
        });
    }

    /** Listens to one call and keeps its window up to date. */
    private final class CallEvents implements Call.Listener {
        Call call;
        CallView window;
        /** Every chat line of this call, mine and theirs. */
        final List<ChatLine> lines = new ArrayList<>();
        /** The kept chat with this person was asked for. */
        private boolean historyAsked;
        private final AtomicReference<BufferedImage> remote = new AtomicReference<>();
        private final AtomicReference<BufferedImage> local = new AtomicReference<>();
        private final AtomicBoolean remoteQueued = new AtomicBoolean();
        private final AtomicBoolean localQueued = new AtomicBoolean();

        CallEvents(CallView window) {
            this.window = window;
        }

        @Override
        public void state(Call c, Call.State state) {
            s.ui().execute(() -> {
                if (window == null) return;
                switch (state) {
                    case CALLING -> window.showPhase(msg.format("phase.calling", c.peerName()));
                    case CONNECTING -> {
                        s.ringer().stop();
                        window.showPhase(msg.get("phase.connecting"));
                        showHistory(c);
                    }
                    case ACTIVE -> {
                        window.showPhase("");
                        window.showActive(s.clock().get());
                        showHistory(c);
                    }
                    default -> {
                    }
                }
            });
        }

        @Override
        public void ended(Call c, EndReason reason) {
            s.ui().execute(() -> callEnded(c, reason, window));
        }

        @Override
        public void remoteFrame(BufferedImage frame) {
            remote.set(frame);
            if (remoteQueued.compareAndSet(false, true)) {
                s.ui().execute(() -> {
                    remoteQueued.set(false);
                    if (window != null) window.showRemoteFrame(remote.get());
                });
            }
        }

        @Override
        public void localFrame(BufferedImage frame) {
            local.set(frame);
            if (localQueued.compareAndSet(false, true)) {
                s.ui().execute(() -> {
                    localQueued.set(false);
                    if (window != null) window.showLocalFrame(local.get());
                });
            }
        }

        @Override
        public void chat(ChatLine line) {
            s.ui().execute(() -> {
                int same = -1;
                for (int i = 0; i < lines.size(); i++) if (lines.get(i).sameFile(line)) same = i;
                if (same >= 0) lines.set(same, line);
                else lines.add(line);
                if (window != null) window.showChat(line);
                // A file is kept once it is there; a failed one is not worth keeping.
                boolean keep = line.file() == null || line.file().status() == ChatFile.Status.DONE;
                if (keep && s.preferences().keepChats()) {
                    call.peer().ifPresent(k -> s.background().execute(() -> s.chats().add(k, List.of(line))));
                }
            });
        }

        @Override
        public Optional<Path> fileTarget(String name) {
            return downloadPath(name);
        }

        /** Once the other side is known: the kept chat with them, above the lines of this call. */
        private void showHistory(Call c) {
            if (historyAsked || window == null || !s.preferences().keepChats() || c.peer().isEmpty()) return;
            historyAsked = true;
            PeerKey peer = c.peer().get();
            CallView w = window;
            s.background().execute(() -> {
                List<ChatLine> earlier = s.chats().load(peer);
                if (earlier.isEmpty()) return;
                s.ui().execute(() -> w.showHistory(earlier.stream().filter(l -> !lines.contains(l)).toList()));
            });
        }

        @Override
        public void remoteCamera(boolean on) {
            on(w -> w.showRemoteCamera(on));
        }

        @Override
        public void remoteScreen(boolean on) {
            on(w -> w.showRemoteScreen(on));
        }

        @Override
        public void remoteMicrophone(boolean on) {
            on(w -> w.showRemoteMuted(!on));
        }

        @Override
        public void cameraFailed() {
            on(w -> {
                w.showInfo(msg.get("info.camera-failed"));
                showSwitches();
            });
        }

        @Override
        public void screenFailed() {
            on(w -> {
                w.showInfo(msg.get("info.screen-failed"));
                showSwitches();
            });
        }

        private void on(Consumer<CallView> action) {
            s.ui().execute(() -> {
                if (window != null) action.accept(window);
            });
        }
    }

    private void callEnded(Call c, EndReason reason, CallView window) {
        s.ringer().stop();
        CallEvents events = listeners.remove(c);
        if (events != null && !c.kind().media() && !events.lines.isEmpty()) {
            c.peer().ifPresent(k -> recentChats.put(k, new RecentChat(List.copyOf(events.lines), s.clock().get(),
                    window)));
        }
        if (c == call && incomingView != null) {
            incomingView.close();
            incomingView = null;
        }
        if (window != null) window.showEnded(msg.get("ended." + reason.name().toLowerCase(Locale.ROOT).replace('_', '-')));
        if (window == callView) callView = null;
        record(c, reason);
        if (reason == EndReason.MISSED) {
            Optional<Person> back = c.peer().flatMap(k -> s.known().get(k));
            view.announceMissed(c.peerName(), () -> back.ifPresent(p -> call(p, c.kind())));
        }
        if (c == call) call = null;
        refreshPeople();
        CallKind next = switchTo;
        switchTo = null;
        if (next != null && !c.kind().media() && call == null) {
            c.peer().flatMap(k -> s.known().get(k)).filter(Person::callable).ifPresent(p -> call(p, next));
        }
    }

    /**
     * From the running chat to a video or sound call with the same person: the chat ends, the call starts, and
     * the call window shows the chat so far.
     */
    public void switchCall(CallKind kind) {
        if (call == null || !call.live() || call.kind().media() || !kind.media()) return;
        switchTo = kind;
        call.hangUp();
    }

    /** A new call with {@code peer}: the lines of a chat that just ended move into its window. */
    private void carryChat(PeerKey peer, CallView window) {
        RecentChat recent = recentChats.remove(peer);
        if (recent == null || recent.ended().plus(CHAT_CARRIES_OVER).isBefore(s.clock().get())) return;
        if (recent.window() != null) recent.window().close();
        listenerOf(call).ifPresent(e -> e.lines.addAll(recent.lines()));
        for (ChatLine line : recent.lines()) window.showChat(line);
    }

    /** Keeps the call in the list of recent calls and the person in the list of people. */
    private void record(Call c, EndReason reason) {
        if (c.peer().isEmpty() || reason == EndReason.UNREACHABLE || reason == EndReason.WRONG_KEY) return;
        PeerKey key = c.peer().get();
        String name = c.peerName().isBlank() ? key.shortText() : c.peerName();
        Optional<Endpoint> endpoint = Optional.ofNullable(nearby.get(key)).map(NearbyPeer::endpoint).or(c::endpoint);
        s.known().remember(key, name, endpoint);
        CallRecord.Direction direction = c.direction() == Call.Direction.OUTGOING ? CallRecord.Direction.OUTGOING
                : reason == EndReason.MISSED ? CallRecord.Direction.MISSED : CallRecord.Direction.INCOMING;
        s.log().add(new CallRecord(key, name, c.created(), c.length(), direction, c.kind()));
    }

    // ---------------------------------------------------------------- settings, language, about, exit

    public void openSettings() {
        s.background().execute(() -> {
            // Each question gets its own thread and at most a few seconds: a camera or sound system that does not
            // answer, or fails, leaves its list empty, but the dialog opens.
            Future<List<Devices.Choice>> cameras = s.sources() == Sources.DEVICES
                    ? ask("cameras", () -> s.devices().cameras()) : CompletableFuture.completedFuture(List.of());
            Future<List<Devices.Choice>> mics = ask("microphones", () -> s.devices().microphones());
            Future<List<Devices.Choice>> speakers = ask("speakers", () -> s.devices().speakers());
            Future<Boolean> echo = ask("echo", () -> s.media().missing(Sources.HEADLESS).isEmpty() && hasEcho());
            Future<Boolean> keyring = ask("keyring", () -> s.chats().available());
            long deadline = System.nanoTime() + DEVICE_WAIT.toNanos();
            List<Devices.Choice> cameraList = answer("cameras", cameras, deadline, List.of());
            List<Devices.Choice> micList = answer("microphones", mics, deadline, List.of());
            List<Devices.Choice> speakerList = answer("speakers", speakers, deadline, List.of());
            boolean echoAvailable = answer("echo", echo, deadline, false);
            // The keyring may ask to be unlocked; that may take longer than the devices.
            boolean chatsAvailable = answer("keyring", keyring, deadline + KEYRING_WAIT.toNanos(), false);
            s.ui().execute(() -> {
                try {
                    CallPreferences p = s.preferences();
                    SettingsModel model = new SettingsModel(p.name(), p.camera(), p.microphone(), p.speaker(),
                            p.cameraOnAtStart(), p.echoCancellation(), echoAvailable, p.port(),
                            s.autostart().enabled(), s.phone().self() == null ? "" : s.phone().self().hex(),
                            p.keepChats(), chatsAvailable, cameraList, micList, speakerList);
                    view.showSettings(model, this::applySettings);
                } catch (RuntimeException e) {
                    LOG.error("The settings could not be opened", e);
                    view.showError(msg.format("error.settings", String.valueOf(e.getMessage())));
                }
            });
        });
    }

    /** How long the settings wait for the lists of cameras, microphones and speakers. */
    static final Duration DEVICE_WAIT = Duration.ofSeconds(5);
    static final Duration KEYRING_WAIT = Duration.ofSeconds(25);

    /** Runs one question on a thread of its own (GStreamer and pactl block in native code). */
    private static <T> Future<T> ask(String what, Callable<T> question) {
        FutureTask<T> task = new FutureTask<>(question);
        Thread.ofPlatform().daemon().name("amade-call-settings-" + what).start(task);
        return task;
    }

    private static <T> T answer(String what, Future<T> future, long deadline, T fallback) {
        try {
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            LOG.warn("No answer for the {} within {} seconds", what, DEVICE_WAIT.toSeconds());
        } catch (ExecutionException e) {
            LOG.warn("No list of {}", what, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return fallback;
    }

    private static boolean hasEcho() {
        try {
            return org.freedesktop.gstreamer.ElementFactory.find("webrtcdsp") != null;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    void applySettings(SettingsModel m) {
        CallPreferences p = s.preferences();
        boolean renamed = !m.name().strip().equals(p.name());
        boolean portChanged = m.port() != p.port();
        p.setName(m.name());
        p.setCamera(m.camera().orElse(null));
        p.setMicrophone(m.microphone().orElse(null));
        p.setSpeaker(m.speaker().orElse(null));
        p.setCameraOnAtStart(m.cameraOnAtStart());
        p.setEchoCancellation(m.echoCancellation());
        p.setPort(m.port());
        p.setKeepChats(m.keepChats());
        try {
            s.autostart().set(m.waitAfterLogin());
        } catch (IOException e) {
            view.showError(msg.format("error.autostart", e.getMessage()));
        }
        if (renamed) s.phone().rename(p.name());
        showStatus();
        if (portChanged) view.showError(msg.get("settings.port.restart"));
    }

    public void setLanguage(Optional<Language> language) {
        try {
            locales.setApplicationLanguage(language);
        } catch (IOException e) {
            view.showError(e.getMessage());
            return;
        }
        if (locales.restartRequired()) view.showError(msg.get("dialog.language.restart"));
    }

    public void about() {
        view.showAbout(s.phone().self() == null ? "" : s.phone().self().shortText());
    }

    /**
     * Closing the window: with "wait for calls after login" the program keeps waiting in the background, else it
     * ends. During a call, the call ends first.
     */
    public void windowClosing() {
        if (s.autostart().enabled() && (call == null || !call.live())) {
            view.hideWindow();
        } else {
            exit();
        }
    }

    public void exit() {
        s.ringer().stop();
        if (call != null && call.live()) call.hangUp();
        s.phone().close();
        view.close();
        s.exit().run();
    }

    // ---------------------------------------------------------------- for tests

    Optional<Call> currentCall() {
        return Optional.ofNullable(call);
    }
}
