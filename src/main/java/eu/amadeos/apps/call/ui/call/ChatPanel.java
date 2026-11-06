package eu.amadeos.apps.call.ui.call;

import eu.amadeos.apps.call.domain.ChatLine;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.swing.AmadeUI;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/** The chat beside the picture: the lines of both sides and a field to write. */
final class ChatPanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final JTextPane lines = new JTextPane();
    private final JTextField input = new JTextField();
    private final Messages msg;
    private final String peer;
    private int count;

    ChatPanel(Messages msg, String peer, Consumer<String> send) {
        super(new BorderLayout(0, 4));
        this.msg = msg;
        this.peer = peer;
        setName("chat");
        setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        lines.setEditable(false);
        lines.setName("chat.lines");
        JScrollPane scroll = new JScrollPane(lines);
        scroll.setPreferredSize(new Dimension(Math.round(240 * AmadeUI.fontScale()), 200));
        JLabel title = new JLabel(msg.get("chat.title"));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        add(title, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        input.setName("chat.input");
        JButton button = new JButton(msg.get("chat.send"));
        button.setName("chat.send");
        Runnable go = () -> {
            String text = input.getText();
            if (text.isBlank()) return;
            send.accept(text);
            input.setText("");
        };
        input.addActionListener(e -> go.run());
        button.addActionListener(e -> go.run());
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.add(input);
        row.add(button, BorderLayout.EAST);
        add(row, BorderLayout.SOUTH);
    }

    void add(ChatLine line) {
        StyledDocument doc = lines.getStyledDocument();
        SimpleAttributeSet head = new SimpleAttributeSet();
        StyleConstants.setBold(head, true);
        StyleConstants.setForeground(head, line.mine() ? AmadeUI.theme().textInactive() : lines.getForeground());
        SimpleAttributeSet body = new SimpleAttributeSet();
        try {
            String who = line.mine() ? msg.get("chat.me") : peer;
            doc.insertString(doc.getLength(), (count++ == 0 ? "" : "\n") + who + "  "
                    + TIME.format(line.at().atZone(ZoneId.systemDefault())) + "\n", head);
            doc.insertString(doc.getLength(), line.text(), body);
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
        lines.setCaretPosition(doc.getLength());
    }

    void focusInput() {
        input.requestFocusInWindow();
    }

    int lineCount() {
        return count;
    }

    String text() {
        return lines.getText();
    }

    JTextField input() {
        return input;
    }
}
