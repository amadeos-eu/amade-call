package eu.amadeos.apps.call.ui.dialogs;

import eu.amadeos.apps.call.domain.SafetyCode;
import eu.amadeos.apps.call.ui.main.MainView;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.swing.AmadeUI;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;

/**
 * The safety code: six groups of five digits that both people read to each other. If they are the same on both
 * screens, the call goes straight from one computer to the other and nobody sits in between.
 */
public final class SafetyCodeDialog extends JDialog {
    private MainView.Verdict verdict = MainView.Verdict.LATER;

    public SafetyCodeDialog(Window owner, Messages msg, String name, SafetyCode code, boolean verified) {
        super(owner, msg.get("safety.title"), ModalityType.APPLICATION_MODAL);
        setName("safety");
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        p.add(left(new JLabel("<html><div style='width:" + Math.round(330 * AmadeUI.fontScale()) + "px'>"
                + msg.format("safety.text", escape(name)) + "</div></html>")));
        p.add(Box.createVerticalStrut(12));
        JPanel groups = new JPanel(new GridLayout(2, 3, 8, 8));
        groups.setName("safety.code");
        for (String group : code.groups()) {
            JLabel l = new JLabel(group, SwingConstants.CENTER);
            l.setFont(new Font(Font.MONOSPACED, Font.BOLD, Math.round(18 * AmadeUI.fontScale())));
            l.setBorder(BorderFactory.createCompoundBorder(AmadeUI.sunken(), BorderFactory.createEmptyBorder(8, 6, 8, 6)));
            groups.add(l);
        }
        p.add(left(groups));
        p.add(Box.createVerticalStrut(10));
        JLabel state = new JLabel(msg.get(verified ? "safety.state.verified" : "safety.state.open"));
        state.setForeground(AmadeUI.theme().textInactive());
        p.add(left(state));
        p.add(Box.createVerticalStrut(12));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(button(msg.get("safety.match"), "safety.match", MainView.Verdict.MATCH));
        buttons.add(button(msg.get("safety.different"), "safety.different", MainView.Verdict.DIFFERENT));
        buttons.add(button(msg.get("safety.later"), "safety.later", MainView.Verdict.LATER));
        p.add(left(buttons));
        add(p);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    /** Shows the dialog and waits for the answer. */
    public static MainView.Verdict ask(Component owner, Messages msg, String name, SafetyCode code, boolean verified) {
        Window window = owner == null ? null : owner instanceof Window w ? w : SwingUtilities.getWindowAncestor(owner);
        SafetyCodeDialog dialog = new SafetyCodeDialog(window, msg, name, code, verified);
        dialog.setVisible(true);
        return dialog.verdict;
    }

    private JButton button(String text, String name, MainView.Verdict value) {
        JButton b = Mnemonics.apply(new JButton(), text);
        b.setName(name);
        b.addActionListener(e -> {
            verdict = value;
            dispose();
        });
        return b;
    }

    private static <C extends javax.swing.JComponent> C left(C c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    MainView.Verdict verdict() {
        return verdict;
    }
}
