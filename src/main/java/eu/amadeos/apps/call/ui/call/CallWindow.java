package eu.amadeos.apps.call.ui.call;

import eu.amadeos.apps.call.domain.CallKind;
import eu.amadeos.apps.call.domain.ChatLine;
import eu.amadeos.apps.call.ui.main.MainPresenter;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.swing.AmadeUI;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The window of a call: the picture, the switches under it (microphone, camera, screen, chat, safety code,
 * hang up), the chat at the side when it is open, and a status line. Closing the window hangs up.
 */
public final class CallWindow extends JFrame implements CallView {
    /** How long the reason of the end stays visible before the window closes. */
    static final int CLOSE_DELAY_MILLIS = 2500;

    private final Messages msg;
    private final MainPresenter presenter;
    private final CallKind kind;
    private final String name;
    private final VideoPanel picture;
    private final ChatPanel chat;
    private final JPanel pictureHolder = new JPanel(new BorderLayout());
    private final JToggleButton microphone = new JToggleButton();
    private final JToggleButton camera = new JToggleButton();
    private final JToggleButton screen = new JToggleButton();
    private final JToggleButton chatButton = new JToggleButton();
    private final JButton verify = new JButton();
    private final JButton hangUp = new JButton();
    private final List<JButton> switchButtons = new ArrayList<>();
    private final JLabel status = new JLabel(" ");
    private final Timer clock;
    private Instant since;
    private boolean ended;
    private boolean updating;
    private JFrame fullScreen;

    public CallWindow(Messages msg, MainPresenter presenter, String name, CallKind kind, boolean verified, Image icon) {
        super(msg.format("call.title." + kind.name().toLowerCase(Locale.ROOT), name));
        this.msg = msg;
        this.presenter = presenter;
        this.kind = kind;
        this.name = name;
        if (icon != null) setIconImage(icon);
        setName("call-window");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        picture = new VideoPanel(name);
        picture.setName("call.picture");
        picture.setTexts(msg.get(verified ? "call.encrypted.verified" : "call.encrypted"), msg.get("call.remote-muted"),
                msg.format("call.remote-screen", name), msg.get("call.self"));
        picture.setVerified(verified);
        picture.setShowSelf(kind.video(), true);
        chat = new ChatPanel(msg, name, presenter::sendChat);
        chat.setVisible(!kind.media());

        pictureHolder.setBorder(AmadeUI.sunken());
        pictureHolder.add(picture);
        JPanel centre = new JPanel(new BorderLayout(0, 4));
        centre.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        JPanel top = new JPanel(new BorderLayout());
        if (kind.media()) {
            top.add(pictureHolder, BorderLayout.CENTER);
            top.add(chat, BorderLayout.EAST);
        } else {
            // A chat: the conversation fills the window.
            top.add(chat, BorderLayout.CENTER);
        }
        centre.add(top, BorderLayout.CENTER);
        centre.add(controls(), BorderLayout.SOUTH);

        status.setName("call.status");
        status.setBorder(BorderFactory.createCompoundBorder(AmadeUI.sunken(), BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
        statusBar.add(status);
        add(centre, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        bindKeys(getRootPane());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (ended) {
                    dispose();
                } else {
                    presenter.hangUp();
                }
            }

            @Override
            public void windowClosed(WindowEvent e) {
                clock().stop();
                showFullScreen(false);
            }
        });
        clock = new Timer(1000, e -> tick());
        status.setText(msg.get("call.status." + kind.name().toLowerCase(Locale.ROOT)));
        float scale = AmadeUI.fontScale();
        setSize(Math.round((kind.video() ? 840 : kind.media() ? 520 : 460) * scale),
                Math.round((kind.video() ? 600 : kind.media() ? 420 : 480) * scale));
        setMinimumSize(new Dimension(420, 320));
    }

    private Timer clock() {
        return clock;
    }

    private JComponent controls() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setName("call.controls");
        bar.setBorder(BorderFactory.createCompoundBorder(AmadeUI.raised(), BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        toggle(microphone, "call.microphone", presenter::toggleMicrophone);
        toggle(camera, "call.camera", presenter::toggleCamera);
        toggle(screen, "call.screen", presenter::toggleScreen);
        toggle(chatButton, "call.chat", this::toggleChat);
        Mnemonics.apply(verify, msg.get("call.verify"));
        verify.setName("call.verify");
        verify.setToolTipText(msg.get("call.verify.tip"));
        verify.addActionListener(e -> presenter.verifyCall());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        if (kind.media()) {
            left.add(microphone);
            if (kind.video()) {
                left.add(camera);
                left.add(screen);
            }
            left.add(chatButton);
        } else {
            // From a chat to a call with the same person; the chat so far moves into the call window.
            for (CallKind k : List.of(CallKind.VIDEO, CallKind.AUDIO)) {
                JButton b = new JButton();
                String key = "call.switch." + k.name().toLowerCase(Locale.ROOT);
                Mnemonics.apply(b, msg.get(key));
                b.setName(key);
                b.setToolTipText(msg.get(key + ".tip"));
                b.addActionListener(e -> presenter.switchCall(k));
                b.setFocusable(false);
                switchButtons.add(b);
                left.add(b);
            }
        }
        left.add(verify);
        bar.add(left, BorderLayout.WEST);
        Mnemonics.apply(hangUp, msg.get(kind.media() ? "call.hang-up" : "call.end-chat"));
        hangUp.setName("call.hang-up");
        hangUp.setBackground(new Color(0xc62828));
        hangUp.setForeground(Color.WHITE);
        hangUp.setFont(hangUp.getFont().deriveFont(Font.BOLD));
        hangUp.addActionListener(e -> presenter.hangUp());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.add(hangUp);
        bar.add(right, BorderLayout.EAST);
        for (JComponent c : List.of(microphone, camera, screen, chatButton, verify)) c.setFocusable(false);
        return bar;
    }

    private void toggle(JToggleButton button, String key, Runnable action) {
        Mnemonics.apply(button, msg.get(key));
        button.setName(key);
        button.setToolTipText(msg.get(key + ".tip"));
        button.addActionListener(e -> {
            if (!updating) action.run();
        });
    }

    private void toggleChat() {
        chat.setVisible(chatButton.isSelected());
        if (chatButton.isSelected()) chat.focusInput();
        revalidate();
    }

    private void bindKeys(JRootPane root) {
        Map<KeyStroke, Runnable> keys = new LinkedHashMap<>();
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK), presenter::toggleMicrophone);
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK), presenter::toggleCamera);
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), () -> showFullScreen(fullScreen == null));
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), () -> showFullScreen(false));
        int i = 0;
        for (Map.Entry<KeyStroke, Runnable> key : keys.entrySet()) {
            String name = "key." + i++;
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key.getKey(), name);
            root.getActionMap().put(name, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    key.getValue().run();
                }
            });
        }
    }

    /** The picture alone on the whole screen; F11 or Escape brings it back. */
    void showFullScreen(boolean on) {
        GraphicsDevice device = getGraphicsConfiguration() == null
                ? GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                : getGraphicsConfiguration().getDevice();
        if (on && fullScreen == null && !GraphicsEnvironment.isHeadless()) {
            fullScreen = new JFrame(getTitle());
            fullScreen.setUndecorated(true);
            fullScreen.setName("call.full-screen");
            pictureHolder.remove(picture);
            fullScreen.add(picture);
            bindKeys(fullScreen.getRootPane());
            if (device.isFullScreenSupported()) {
                device.setFullScreenWindow(fullScreen);
            } else {
                fullScreen.setBounds(device.getDefaultConfiguration().getBounds());
                fullScreen.setVisible(true);
            }
        } else if (!on && fullScreen != null) {
            if (device.getFullScreenWindow() == fullScreen) device.setFullScreenWindow(null);
            fullScreen.remove(picture);
            fullScreen.dispose();
            fullScreen = null;
            pictureHolder.add(picture);
            pictureHolder.revalidate();
            toFront();
        }
    }

    private void tick() {
        if (since == null) return;
        picture.setTime(clockText(Duration.between(since, Instant.now())));
    }

    static String clockText(Duration d) {
        long s = Math.max(0, d.toSeconds());
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format("%02d:%02d", s / 60, s % 60);
    }

    // ---------------------------------------------------------------- CallView

    @Override
    public void showPhase(String text) {
        picture.setPhase(text);
    }

    @Override
    public void showActive(Instant start) {
        since = start;
        tick();
        clock.start();
    }

    @Override
    public void showRemoteFrame(BufferedImage frame) {
        if (frame != null) picture.setRemote(frame);
    }

    @Override
    public void showLocalFrame(BufferedImage frame) {
        if (frame != null && (camera.isSelected() || screen.isSelected())) picture.setLocal(frame);
    }

    @Override
    public void showRemoteCamera(boolean on) {
        picture.setRemoteCamera(on);
    }

    @Override
    public void showRemoteScreen(boolean sharing) {
        picture.setRemoteScreen(sharing);
    }

    @Override
    public void showRemoteMuted(boolean muted) {
        picture.setRemoteMuted(muted);
    }

    @Override
    public void showSwitches(boolean mic, boolean cam, boolean sharing, boolean video) {
        updating = true;
        try {
            microphone.setSelected(mic);
            camera.setSelected(cam);
            screen.setSelected(sharing);
            camera.setEnabled(video && !ended);
            screen.setEnabled(video && !ended);
            microphone.setText(Mnemonics.strip(msg.get(mic ? "call.microphone" : "call.microphone.off")));
            camera.setText(Mnemonics.strip(msg.get(cam ? "call.camera" : "call.camera.off")));
            picture.setShowSelf(video && (cam || sharing), !sharing);
            status.setText(msg.get(sharing ? "call.status.sharing" : !kind.media() ? "call.status.chat"
                    : video ? "call.status.video" : "call.status.audio"));
        } finally {
            updating = false;
        }
    }

    @Override
    public void showChat(ChatLine line) {
        chat.add(line);
        if (!line.mine() && !chat.isVisible()) {
            chatButton.setSelected(true);
            toggleChat();
        }
    }

    @Override
    public void showVerified(boolean verified) {
        picture.setVerified(verified);
        picture.setTexts(msg.get(verified ? "call.encrypted.verified" : "call.encrypted"), msg.get("call.remote-muted"),
                msg.format("call.remote-screen", name), msg.get("call.self"));
    }

    @Override
    public void showInfo(String text) {
        picture.setInfo(text);
        Timer t = new Timer(5000, e -> picture.setInfo(""));
        t.setRepeats(false);
        t.start();
    }

    @Override
    public void showEnded(String text) {
        ended = true;
        clock.stop();
        picture.setRemoteCamera(false);
        picture.setShowSelf(false, true);
        picture.setPhase(text);
        for (JComponent c : List.of(microphone, camera, screen, verify, hangUp)) c.setEnabled(false);
        for (JButton b : switchButtons) b.setEnabled(false);
        Timer t = new Timer(CLOSE_DELAY_MILLIS, e -> {
            if (!chat.isVisible() || chat.lineCount() == 0) dispose();
        });
        t.setRepeats(false);
        t.start();
    }

    @Override
    public void close() {
        dispose();
    }

    // ---------------------------------------------------------------- for tests and screenshots

    VideoPanel picture() {
        return picture;
    }

    ChatPanel chatPanel() {
        return chat;
    }

    boolean ended() {
        return ended;
    }

    JToggleButton microphoneButton() {
        return microphone;
    }

    JToggleButton cameraButton() {
        return camera;
    }

    JToggleButton screenButton() {
        return screen;
    }

    JToggleButton chatButton() {
        return chatButton;
    }

    JButton hangUpButton() {
        return hangUp;
    }

    String statusText() {
        return status.getText();
    }
}
