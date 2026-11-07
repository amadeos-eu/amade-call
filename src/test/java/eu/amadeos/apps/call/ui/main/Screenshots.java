package eu.amadeos.apps.call.ui.main;

import eu.amadeos.apps.call.OnEdt;
import eu.amadeos.apps.call.domain.CallKind;
import eu.amadeos.apps.call.domain.CallRecord;
import eu.amadeos.apps.call.domain.Endpoint;
import eu.amadeos.apps.call.domain.PeerKey;
import eu.amadeos.apps.call.media.GstMedia;
import eu.amadeos.apps.call.media.Sources;
import eu.amadeos.apps.call.service.Discovery;
import eu.amadeos.apps.call.service.NearbyPeer;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.theme.HorizonThemes;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static eu.amadeos.apps.call.Wait.until;

/**
 * Makes the pictures in docs/screenshots: Anna in real windows (Horizon Dark) calls Ben, both with a drawn
 * portrait as camera. {@code ./gradlew screenshots} under a display (xvfb-run).
 */
public final class Screenshots {
    private Screenshots() {
    }

    public static void main(String[] args) {
        try {
            run(args);
            System.exit(0);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "docs/screenshots");
        Files.createDirectories(out);
        Path tmp = Files.createTempDirectory("amade-call-shots");
        Path annaPicture = resource(tmp, "anna.jpg");
        Path benPicture = resource(tmp, "ben.jpg");

        TestSide ben = new TestSide(tmp.resolve("ben"), "Ben Weber", null, null,
                new GstMedia(Optional.of(benPicture)), Sources.HEADLESS, Discovery.none());
        ben.preferences.setPort(Endpoint.DEFAULT_PORT + 1);
        ben.start();

        PeerKey carla = new PeerKey(HexFormat.of().formatHex(new byte[32]).replace('0', 'c'));
        PeerKey david = new PeerKey(HexFormat.of().formatHex(new byte[32]).replace('0', 'd'));
        Discovery nearby = new Discovery() {
            @Override
            public void start(String name, PeerKey self, int port, Listener listener) {
                listener.found(new NearbyPeer(ben.phone.self(), "Ben Weber", new Endpoint("127.0.0.1", ben.phone.port())));
                listener.found(new NearbyPeer(carla, "Carla Rossi", new Endpoint("192.168.1.24", Endpoint.DEFAULT_PORT)));
            }

            @Override
            public void rename(String name) {
            }

            @Override
            public void close() {
            }
        };

        AmadeUI.install(HorizonThemes.theme(HorizonThemes.Scheme.DARK, HorizonThemes.Accent.TEAL));
        AtomicReference<MainWindow> windowRef = new AtomicReference<>();
        AtomicReference<TestSide> annaRef = new AtomicReference<>();
        OnEdt.run(() -> {
            MainWindow w = new MainWindow(TestSide.MSG, null);
            TestSide a = new TestSide(tmp.resolve("anna"), "Anna Berger", w, SwingUtilities::invokeLater,
                    new GstMedia(Optional.of(annaPicture)), Sources.HEADLESS, nearby);
            Instant now = Instant.now();
            a.known.remember(ben.phone.self(), "Ben Weber", Optional.of(new Endpoint("127.0.0.1", ben.phone.port())));
            a.known.setVerified(ben.phone.self(), true);
            a.known.remember(david, "David Novak", Optional.of(new Endpoint("192.168.1.31", Endpoint.DEFAULT_PORT)));
            a.log.add(new CallRecord(ben.phone.self(), "Ben Weber", now.minus(Duration.ofDays(3)),
                    Duration.ofMinutes(12).plusSeconds(40), CallRecord.Direction.OUTGOING, CallKind.VIDEO));
            a.log.add(new CallRecord(ben.phone.self(), "Ben Weber", now.minus(Duration.ofDays(1)),
                    Duration.ZERO, CallRecord.Direction.MISSED, CallKind.AUDIO));
            a.log.add(new CallRecord(ben.phone.self(), "Ben Weber", now.minus(Duration.ofHours(5)),
                    Duration.ofMinutes(4).plusSeconds(5), CallRecord.Direction.INCOMING, CallKind.AUDIO));
            w.bind(a.presenter);
            w.setSize(Math.round(780 * AmadeUI.fontScale()), Math.round(500 * AmadeUI.fontScale()));
            w.setVisible(true);
            windowRef.set(w);
            annaRef.set(a);
        });
        MainWindow window = windowRef.get();
        TestSide anna = annaRef.get();
        anna.start();
        until("Ben nearby", () -> anna.phone.port() > 0);
        anna.on(() -> anna.presenter.select(ben.phone.self()));
        Thread.sleep(800);
        save(window, out.resolve("amade-call.png"));

        // A video call with the chat open.
        anna.on(() -> anna.presenter.callSelected(CallKind.VIDEO));
        until("Ben rings", () -> ben.view.incoming != null);
        ben.on(() -> ben.presenter.accept(true));
        until("Ben active", () -> ben.lastWindow().active);
        until("pictures", () -> ben.lastWindow().remoteFrames > 10);
        Frame call = frame("call-window");
        ben.on(() -> ben.presenter.sendChat("Hi Anna! Can you see the floor plan?"));
        Thread.sleep(500);
        OnEdt.run(() -> {
            JTextField input = find(call, "chat.input");
            input.setText("Yes, the kitchen looks great.");
            input.postActionEvent();
        });
        Thread.sleep(3000);
        save(call, out.resolve("amade-call-video.png"));
        ben.on(() -> ben.presenter.hangUp());
        Thread.sleep(3500);

        // Ben calls back: the ringing window.
        ben.view.address = "127.0.0.1:" + anna.phone.port();
        ben.on(() -> ben.presenter.callAddress(CallKind.VIDEO));
        until("ringing", () -> frame("incoming") != null);
        Thread.sleep(500);
        save(frame("incoming"), out.resolve("amade-call-incoming.png"));
        anna.on(() -> anna.presenter.decline());
        until("Ben's call over", () -> ben.lastWindow().ended != null);
        Thread.sleep(3500);

        // A chat without picture and sound.
        ben.view.incoming = null;
        anna.on(() -> anna.presenter.select(ben.phone.self()));
        anna.on(() -> anna.presenter.callSelected(CallKind.CHAT));
        until("Ben asked to chat", () -> ben.view.incoming != null);
        ben.on(() -> ben.presenter.accept(true));
        until("chat open", () -> ben.lastWindow().active);
        anna.on(() -> anna.presenter.sendChat("Do you have a minute? The plumber can come on Thursday."));
        Thread.sleep(400);
        ben.on(() -> ben.presenter.sendChat("Thursday works. Shall we talk it through?"));
        Thread.sleep(1500);
        save(frame("call-window"), out.resolve("amade-call-chat.png"));
        anna.on(() -> anna.presenter.hangUp());
        Thread.sleep(500);

        anna.shutdown();
        ben.shutdown();
    }

    private static Path resource(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        try (InputStream in = Screenshots.class.getResourceAsStream("/screenshots/" + name)) {
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    /** The newest showing window of that name. */
    private static Frame frame(String name) {
        return Arrays.stream(Frame.getFrames()).filter(f -> name.equals(f.getName()) && f.isShowing())
                .reduce((first, second) -> second).orElse(null);
    }

    private static void save(Frame frame, Path file) throws Exception {
        AtomicReference<BufferedImage> image = new AtomicReference<>();
        OnEdt.run(() -> {
            JComponent root = ((javax.swing.RootPaneContainer) frame).getRootPane();
            BufferedImage img = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            root.printAll(g);
            g.dispose();
            image.set(img);
        });
        ImageIO.write(image.get(), "png", file.toFile());
        System.out.println("Wrote " + file);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component> T find(Container root, String name) {
        for (Component c : root.getComponents()) {
            if (name.equals(c.getName())) return (T) c;
            if (c instanceof Container ct) {
                T found = find(ct, name);
                if (found != null) return found;
            }
        }
        return null;
    }
}
