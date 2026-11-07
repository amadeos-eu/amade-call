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
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The chat beside the picture: the lines of both sides and a field to write. */
final class ChatPanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_AND_TIME =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT);

    private final JTextPane lines = new JTextPane();
    private final JTextField input = new JTextField();
    private final Messages msg;
    private final String peer;
    private int count;
    /** Lines in the text so far, earlier and of this call. */
    private int written;
    private int historyLines;
    private final List<ChatLine> current = new ArrayList<>();

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
        current.add(line);
        write(line, false);
        count++;
        lines.setCaretPosition(lines.getStyledDocument().getLength());
    }

    /** The kept chat from earlier calls, dimmed and with the date, above the lines of this call. */
    void setHistory(List<ChatLine> earlier) {
        StyledDocument doc = lines.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
        written = 0;
        for (ChatLine line : earlier) write(line, true);
        historyLines = earlier.size();
        for (ChatLine line : current) write(line, false);
        lines.setCaretPosition(doc.getLength());
    }

    private void write(ChatLine line, boolean earlier) {
        StyledDocument doc = lines.getStyledDocument();
        SimpleAttributeSet head = new SimpleAttributeSet();
        StyleConstants.setBold(head, true);
        StyleConstants.setForeground(head, line.mine() || earlier ? AmadeUI.theme().textInactive() : lines.getForeground());
        SimpleAttributeSet body = new SimpleAttributeSet();
        if (earlier) StyleConstants.setForeground(body, AmadeUI.theme().textInactive());
        // Like a messenger: my lines on the right, theirs on the left, with room between messages.
        SimpleAttributeSet paragraph = new SimpleAttributeSet();
        StyleConstants.setAlignment(paragraph, line.mine() ? StyleConstants.ALIGN_RIGHT : StyleConstants.ALIGN_LEFT);
        float gap = 8 * AmadeUI.fontScale();
        float side = 36 * AmadeUI.fontScale();
        StyleConstants.setLeftIndent(paragraph, line.mine() ? side : 0);
        StyleConstants.setRightIndent(paragraph, line.mine() ? 0 : side);
        try {
            String who = line.mine() ? msg.get("chat.me") : peer;
            DateTimeFormatter when = earlier ? DAY_AND_TIME.withLocale(msg.locale()) : TIME;
            boolean first = written++ == 0;
            if (!first) doc.insertString(doc.getLength(), "\n", null);
            int start = doc.getLength();
            doc.insertString(start, who + "  " + when.format(line.at().atZone(ZoneId.systemDefault())) + "\n", head);
            doc.insertString(doc.getLength(), line.text(), body);
            SimpleAttributeSet top = new SimpleAttributeSet(paragraph);
            StyleConstants.setSpaceAbove(top, first ? 0 : gap);
            doc.setParagraphAttributes(start, 1, top, false);
            doc.setParagraphAttributes(start + 1, doc.getLength() - start - 1, paragraph, false);
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    int historyLines() {
        return historyLines;
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
