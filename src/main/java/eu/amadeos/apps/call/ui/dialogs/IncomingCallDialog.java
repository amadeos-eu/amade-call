package eu.amadeos.apps.call.ui.dialogs;

import eu.amadeos.apps.call.domain.CallKind;
import eu.amadeos.apps.call.ui.main.Avatar;
import eu.amadeos.apps.call.ui.main.LockIcon;
import eu.amadeos.apps.call.ui.main.MainPresenter;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.swing.AmadeUI;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * A call rings: a small window of its own, above the others, with the caller's name and avatar and Accept,
 * Sound only and Decline. It has no owner, so it shows up even while the main window waits in the background.
 */
public final class IncomingCallDialog extends JFrame implements IncomingView {
    private final JButton accept = new JButton();
    private final JButton audioOnly = new JButton();
    private final JButton decline = new JButton();

    public IncomingCallDialog(Messages msg, MainPresenter presenter, String name, CallKind kind, boolean known,
                              boolean verified, Image icon) {
        super(msg.get("incoming.title"));
        setName("incoming");
        if (icon != null) setIconImage(icon);
        setAlwaysOnTop(true);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                presenter.decline();
            }
        });
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(16, 24, 16, 24));
        p.add(centre(new JLabel(new Avatar(name, Math.round(64 * AmadeUI.fontScale())))));
        p.add(Box.createVerticalStrut(10));
        JLabel who = new JLabel(name);
        who.setName("incoming.name");
        who.setFont(who.getFont().deriveFont(Font.BOLD, who.getFont().getSize2D() * 1.5f));
        p.add(centre(who));
        p.add(centre(new JLabel(msg.get(kind.video() ? "incoming.video" : "incoming.audio"))));
        JLabel trust = new JLabel(msg.get(verified ? "incoming.verified" : known ? "incoming.known" : "incoming.unknown"));
        trust.setIcon(new LockIcon(verified ? LockIcon.VERIFIED : LockIcon.OPEN));
        if (verified) trust.setForeground(LockIcon.VERIFIED);
        p.add(Box.createVerticalStrut(4));
        p.add(centre(trust));
        p.add(Box.createVerticalStrut(16));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        button(accept, msg.get("incoming.accept"), "incoming.accept", () -> presenter.accept(true));
        accept.setFont(accept.getFont().deriveFont(Font.BOLD));
        button(audioOnly, msg.get("incoming.audio-only"), "incoming.audio-only", () -> presenter.accept(false));
        button(decline, msg.get("incoming.decline"), "incoming.decline", presenter::decline);
        buttons.add(accept);
        if (kind.video()) buttons.add(audioOnly);
        buttons.add(decline);
        p.add(centre(buttons));
        add(p);
        getRootPane().setDefaultButton(accept);
        getRootPane().registerKeyboardAction(e -> presenter.decline(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setResizable(false);
    }

    private static void button(JButton b, String text, String name, Runnable action) {
        Mnemonics.apply(b, text);
        b.setName(name);
        b.addActionListener(e -> action.run());
    }

    private static <C extends JComponent> C centre(C c) {
        c.setAlignmentX(Component.CENTER_ALIGNMENT);
        return c;
    }

    @Override
    public void close() {
        dispose();
    }

    JButton acceptButton() {
        return accept;
    }

    JButton audioOnlyButton() {
        return audioOnly;
    }

    JButton declineButton() {
        return decline;
    }
}
