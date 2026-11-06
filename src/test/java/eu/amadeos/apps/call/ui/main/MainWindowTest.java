package eu.amadeos.apps.call.ui.main;

import eu.amadeos.apps.call.OnEdt;
import eu.amadeos.apps.call.domain.CallKind;
import eu.amadeos.apps.call.domain.Endpoint;
import eu.amadeos.apps.call.ui.call.CallWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static eu.amadeos.apps.call.Wait.until;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Anna in real windows calls Ben (fake windows) and the other way round. Needs a display. */
class MainWindowTest {
    @TempDir
    Path dir;

    private MainWindow window;
    private TestSide anna;
    private TestSide ben;

    private void open() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        ben = new TestSide(dir.resolve("ben"), "Ben");
        ben.start();
        AtomicReference<TestSide> side = new AtomicReference<>();
        OnEdt.run(() -> {
            window = new MainWindow(TestSide.MSG, null);
            TestSide a = new TestSide(dir.resolve("anna"), "Anna", window, SwingUtilities::invokeLater);
            side.set(a);
            window.bind(a.presenter);
            window.setSize(760, 520);
            window.setVisible(true);
        });
        anna = side.get();
        anna.start();
        until("status", () -> onEdt(() -> window.statusText()).contains(String.valueOf(anna.phone.port())));
    }

    @AfterEach
    void close() throws Exception {
        if (anna != null) anna.shutdown();
        if (ben != null) ben.shutdown();
        for (Frame f : Frame.getFrames()) OnEdt.run(f::dispose);
    }

    @Test
    void anEmptyListExplainsAndOffersTheAddress() throws Exception {
        open();
        OnEdt.run(() -> {
            assertEquals(0, window.peopleList().getModel().getSize());
            assertNotNull(find(window.detailsPanel(), "details.address"));
            assertFalse(window.menu().getMenu(1).getItem(0).isEnabled(), "no one to verify");
        });
    }

    @Test
    void callingAPersonFromTheDetailsAndHangingUp() throws Exception {
        open();
        anna.known.remember(ben.phone.self(), "Ben", Optional.of(new Endpoint("127.0.0.1", ben.phone.port())));
        anna.on(() -> anna.presenter.select(ben.phone.self()));
        OnEdt.run(() -> {
            assertEquals("Ben", this.<JLabel>find(window.detailsPanel(), "details.name").getText());
            this.<AbstractButton>find(window.detailsPanel(), "details.video").doClick();
        });
        CallWindow call = callWindow();
        until("Ben rings", () -> ben.view.incoming != null);
        assertEquals("Anna", ben.view.incomingName);
        ben.on(() -> ben.presenter.accept(true));
        until("Ben active", () -> ben.lastWindow().active);
        OnEdt.run(() -> {
            assertEquals(TestSide.MSG.format("call.title.video", "Ben"), call.getTitle());
            assertTrue(this.<AbstractButton>find(call, "call.microphone").isSelected());
            this.<AbstractButton>find(call, "call.microphone").doClick();
        });
        until("Ben sees the microphone off", () -> ben.lastWindow().remoteMuted);
        OnEdt.run(() -> this.<AbstractButton>find(call, "call.hang-up").doClick());
        until("Ben hears the end", () -> ben.lastWindow().ended != null);
        until("buttons off", () -> onEdt(() -> !this.<AbstractButton>find(call, "call.hang-up").isEnabled()));
        until("logged", () -> anna.log.all().size() == 1);
    }

    @Test
    void answeringAnIncomingCallAndChatting() throws Exception {
        open();
        ben.view.address = "127.0.0.1:" + anna.phone.port();
        ben.on(() -> ben.presenter.callAddress(CallKind.VIDEO));
        until("ringing window", () -> onEdt(() -> frame("incoming") != null));
        OnEdt.run(() -> {
            Frame incoming = frame("incoming");
            assertEquals("Ben", this.<JLabel>find(incoming, "incoming.name").getText());
            this.<AbstractButton>find(incoming, "incoming.accept").doClick();
        });
        CallWindow call = callWindow();
        until("Anna's incoming window closes", () -> onEdt(() -> frame("incoming") == null));
        until("Ben active", () -> ben.lastWindow().active);
        ben.on(() -> ben.presenter.sendChat("Hallo Anna"));
        until("chat shown", () -> onEdt(() -> this.<Component>find(call, "chat").isShowing()
                && this.<javax.swing.text.JTextComponent>find(call, "chat.lines").getText().contains("Hallo Anna")));
        OnEdt.run(() -> {
            JTextField input = find(call, "chat.input");
            input.setText("Hallo Ben");
            input.postActionEvent();
        });
        // Ben's window shows his own line first, then Anna's answer.
        until("Ben reads it", () -> ben.lastWindow().chat.size() == 2);
        assertTrue(ben.lastWindow().chat.getFirst().mine());
        assertEquals("Hallo Ben", ben.lastWindow().chat.getLast().text());
        assertFalse(ben.lastWindow().chat.getLast().mine());
        ben.on(() -> ben.presenter.hangUp());
        until("ended", () -> onEdt(() -> !this.<AbstractButton>find(call, "call.hang-up").isEnabled()));
        until("Ben listed", () -> onEdt(() -> {
            var m = window.peopleList().getModel();
            for (int i = 0; i < m.getSize(); i++) if (m.getElementAt(i).name().equals("Ben")) return true;
            return false;
        }));
    }

    // ---------------------------------------------------------------- helpers

    private CallWindow callWindow() throws Exception {
        until("call window", () -> onEdt(() -> frame("call-window") != null));
        return (CallWindow) onEdt(() -> frame("call-window"));
    }

    private static Frame frame(String name) {
        return Arrays.stream(Frame.getFrames()).filter(f -> name.equals(f.getName()) && f.isShowing()).findFirst()
                .orElse(null);
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> c) {
        AtomicReference<T> result = new AtomicReference<>();
        try {
            OnEdt.run(() -> {
                try {
                    result.set(c.call());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return result.get();
    }

    @SuppressWarnings("unchecked")
    private <T extends Component> T find(Container root, String name) {
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
