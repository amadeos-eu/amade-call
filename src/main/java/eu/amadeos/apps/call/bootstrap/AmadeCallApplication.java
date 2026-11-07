package eu.amadeos.apps.call.bootstrap;

import eu.amadeos.apps.call.domain.CallLink;
import eu.amadeos.apps.call.media.Sources;
import eu.amadeos.apps.call.service.Autostart;
import eu.amadeos.apps.call.service.SingleInstance;
import eu.amadeos.apps.call.ui.main.MainPresenter;
import eu.amadeos.apps.call.ui.main.MainWindow;
import eu.amadeos.commons.app.AmadeApplication;
import eu.amadeos.commons.app.AppInfo;
import org.slf4j.LoggerFactory;

import java.awt.Frame;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

public final class AmadeCallApplication {
    public static final AppInfo APP = new AppInfo("amade-call", "Calls", "call");
    /**
     * A moving test picture instead of the camera, for computers without camera and for screenshots;
     * {@code --test-picture=FILE} shows that still picture instead.
     */
    public static final String TEST_PICTURE = "--test-picture";

    private AmadeCallApplication() {
    }

    /** The version from the jar's manifest; "dev" when run from the IDE. */
    public static String version() {
        String version = AmadeCallApplication.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    // --8<-- [start:main]
    public static void main(String[] args) {
        if (args.length > 0 && (args[0].equals("--version") || args[0].equals("-v"))) {
            System.out.println("amade-call " + version());
            return;
        }
        boolean background = Arrays.asList(args).contains(Autostart.BACKGROUND);
        // Only one amade-call per user owns the call port: a second start shows the first one's window.
        SingleInstance instance = new SingleInstance(SingleInstance.defaultSocket());
        // A link from Contacts (amadeos-call:<key>?kind=video), handed over by xdg-open.
        Optional<CallLink> link = Arrays.stream(args).map(CallLink::parse).flatMap(Optional::stream).findFirst();
        String wish = link.map(l -> "call " + l.uri()).orElse(background ? "background" : "show");
        if (instance.sendToRunning(wish)) return;
        Sources sources = Arrays.stream(args).anyMatch(a -> a.equals(TEST_PICTURE) || a.startsWith(TEST_PICTURE + "="))
                ? Sources.TEST_PICTURE : Sources.DEVICES;
        Optional<Path> still = Arrays.stream(args).filter(a -> a.startsWith(TEST_PICTURE + "="))
                .findFirst().map(a -> Path.of(a.substring(TEST_PICTURE.length() + 1)));
        AmadeApplication.launch(APP, injector -> {
            MainWindow window = injector.getInstance(MainWindow.class);
            MainPresenter presenter = injector.getInstance(MainPresenter.class);
            window.bind(presenter);
            if (background) window.setState(Frame.ICONIFIED);
            window.setVisible(!background);
            try {
                instance.listen(presenter::message);
            } catch (IOException e) {
                LoggerFactory.getLogger(AmadeCallApplication.class).warn("No single-instance socket", e);
            }
            presenter.start();
            link.ifPresent(presenter::open);
        }, new CallModule(sources, still, instance));
    }
    // --8<-- [end:main]
}
