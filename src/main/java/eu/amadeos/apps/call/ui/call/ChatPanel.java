package eu.amadeos.apps.call.ui.call;

import eu.amadeos.apps.call.domain.ChatFile;
import eu.amadeos.apps.call.domain.ChatLine;
import eu.amadeos.apps.call.ui.CallGlyph;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.theme.DesktopTheme;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The chat: the lines of both sides as rounded bubbles, like in a messenger (mine on the right in the accent
 * colour of the theme, theirs on the left), and a field to write. Kept lines from earlier calls come first, dimmed
 * and with the date.
 */
final class ChatPanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_AND_TIME =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT);

    private final Lines lines = new Lines();
    private final JScrollPane scroll;
    private final JTextField input = new JTextField();
    private final Messages msg;
    private final String peer;
    private final List<ChatLine> current = new ArrayList<>();
    private final Map<String, Bubble> fileBubbles = new HashMap<>();
    private final Consumer<Path> open;
    private List<ChatLine> history = List.of();

    ChatPanel(Messages msg, String peer, Consumer<String> send) {
        this(msg, peer, send, files -> {
        }, file -> {
        });
    }

    /** {@code sendFiles} gets pictures and files to send (chosen or dropped), {@code open} one to open. */
    ChatPanel(Messages msg, String peer, Consumer<String> send, Consumer<List<Path>> sendFiles, Consumer<Path> open) {
        super(new BorderLayout(0, 4));
        this.msg = msg;
        this.peer = peer;
        this.open = open;
        setName("chat");
        setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        lines.setName("chat.lines");
        scroll = new JScrollPane(lines, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(Math.round(260 * AmadeUI.fontScale()), 200));
        scroll.getVerticalScrollBar().setUnitIncrement(Math.round(16 * AmadeUI.fontScale()));
        scroll.getViewport().setBackground(AmadeUI.theme().fieldBackground());
        JLabel title = new JLabel(msg.get("chat.title"));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        add(title, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        input.setName("chat.input");
        JButton button = CallGlyph.SEND.on(new JButton(), msg.get("chat.send"), null);
        button.setName("chat.send");
        Runnable go = () -> {
            String text = input.getText();
            if (text.isBlank()) return;
            send.accept(text);
            input.setText("");
        };
        input.addActionListener(e -> go.run());
        button.addActionListener(e -> go.run());
        JButton attach = CallGlyph.ATTACH.on(new JButton(), msg.get("chat.attach"), msg.get("chat.attach.tip"));
        attach.setName("chat.attach");
        attach.addActionListener(e -> chooseFiles(sendFiles));
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.add(attach, BorderLayout.WEST);
        row.add(input);
        row.add(button, BorderLayout.EAST);
        add(row, BorderLayout.SOUTH);
        // Files dropped anywhere on the chat are sent; the text field keeps its own drop of text.
        TransferHandler drop = new FileDrop(sendFiles, null);
        setTransferHandler(drop);
        lines.setTransferHandler(drop);
        input.setTransferHandler(new FileDrop(sendFiles, input.getTransferHandler()));
    }

    private void chooseFiles(Consumer<List<Path>> sendFiles) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(msg.get("dialog.attach.title"));
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(SwingUtilities.getWindowAncestor(this)) != JFileChooser.APPROVE_OPTION) return;
        List<Path> chosen = new ArrayList<>();
        for (java.io.File f : chooser.getSelectedFiles()) chosen.add(f.toPath());
        sendFiles.accept(chosen);
    }

    /** Takes files from the file manager; anything else goes to {@code fallback} (the text field's own). */
    private static final class FileDrop extends TransferHandler {
        private final Consumer<List<Path>> sendFiles;
        private final TransferHandler fallback;

        FileDrop(Consumer<List<Path>> sendFiles, TransferHandler fallback) {
            this.sendFiles = sendFiles;
            this.fallback = fallback;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return true;
            return fallback != null && fallback.canImport(support);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                try {
                    List<?> files = (List<?>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    List<Path> paths = new ArrayList<>();
                    for (Object f : files) if (f instanceof java.io.File file && file.isFile()) paths.add(file.toPath());
                    if (!paths.isEmpty()) sendFiles.accept(paths);
                    return !paths.isEmpty();
                } catch (UnsupportedFlavorException | java.io.IOException e) {
                    return false;
                }
            }
            return fallback != null && fallback.importData(support);
        }

        @Override
        public int getSourceActions(JComponent c) {
            return fallback == null ? NONE : fallback.getSourceActions(c);
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            return null;
        }

        @Override
        public void exportToClipboard(JComponent comp, java.awt.datatransfer.Clipboard clip, int action) {
            if (fallback != null) fallback.exportToClipboard(comp, clip, action);
        }
    }

    /** A new line, or the newer state of a picture or file that is already shown. */
    void add(ChatLine line) {
        if (line.file() != null && fileBubbles.containsKey(line.file().id())) {
            for (int i = 0; i < current.size(); i++) if (current.get(i).sameFile(line)) current.set(i, line);
            Bubble b = fileBubbles.get(line.file().id());
            boolean grows = b.update(line.file());
            if (grows) refresh();
            return;
        }
        current.add(line);
        lines.add(bubble(line, false));
        refresh();
    }

    /** The kept chat from earlier calls, dimmed and with the date, above the lines of this call. */
    void setHistory(List<ChatLine> earlier) {
        history = List.copyOf(earlier);
        lines.removeAll();
        for (ChatLine line : history) lines.add(bubble(line, true));
        for (ChatLine line : current) lines.add(bubble(line, false));
        refresh();
    }

    /** Once a thumbnail has loaded: the bubble grew, the newest line stays in sight. */
    private void refresh() {
        lines.revalidate();
        lines.repaint();
        // To the newest line once the layout knows the height.
        SwingUtilities.invokeLater(() -> lines.scrollRectToVisible(new Rectangle(0, lines.getHeight() - 1, 1, 1)));
    }

    private JComponent bubble(ChatLine line, boolean earlier) {
        String who = line.mine() ? msg.get("chat.me") : peer;
        DateTimeFormatter when = earlier ? DAY_AND_TIME.withLocale(msg.locale()) : TIME;
        Bubble b = new Bubble(who + "  " + when.format(line.at().atZone(ZoneId.systemDefault())), line.text(),
                line.mine(), earlier, line.file());
        if (line.file() != null && !earlier) fileBubbles.put(line.file().id(), b);
        return b;
    }

    void focusInput() {
        input.requestFocusInWindow();
    }

    int lineCount() {
        return current.size();
    }

    int historyLines() {
        return history.size();
    }

    /** All lines as plain text, earlier first, for tests. */
    String text() {
        return java.util.stream.Stream.concat(history.stream(), current.stream()).map(ChatLine::text)
                .collect(Collectors.joining("\n"));
    }

    JTextField input() {
        return input;
    }

    // ---------------------------------------------------------------- the list of bubbles

    /** The bubbles from top to bottom, as wide as the visible part of the scroll pane. */
    private final class Lines extends JPanel implements Scrollable {
        Lines() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            setBackground(AmadeUI.theme().fieldBackground());
        }

        @Override
        public Component add(Component c) {
            if (getComponentCount() > 0) super.add(Box.createVerticalStrut(Math.round(8 * AmadeUI.fontScale())));
            return super.add(c);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return Math.round(16 * AmadeUI.fontScale());
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return visible.height - 16;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport v && getPreferredSize().height < v.getHeight();
        }
    }

    /** One message: a rounded box with who and when on top and the text below, on its side of the chat. */
    private final class Bubble extends JPanel {
        private final JLabel head;
        private final JTextArea text;
        private final boolean mine;
        private final boolean earlier;
        private final Color fill;
        /** For a picture or file instead of the text: thumbnail, name and state. */
        private final JPanel content;
        private final JLabel thumbnail = new JLabel();
        private final JLabel fileName = new JLabel();
        private final JLabel fileState = new JLabel();
        private ChatFile file;
        private boolean thumbnailAsked;

        Bubble(String headText, String body, boolean mine, boolean earlier, ChatFile file) {
            super(null);
            this.mine = mine;
            this.earlier = earlier;
            setOpaque(false);
            setName(mine ? "chat.mine" : "chat.theirs");
            DesktopTheme t = AmadeUI.theme();
            Color background = t.fieldBackground();
            Color accent = t.selectionBackground();
            Color foreground;
            if (mine) {
                fill = earlier ? mix(accent, background, 0.45f) : accent;
                foreground = t.selectionText();
            } else {
                fill = t.isDark() ? mix(background, Color.WHITE, 0.10f) : mix(background, Color.BLACK, 0.07f);
                foreground = t.fieldText();
            }
            Color dim = mix(foreground, fill, 0.35f);
            head = new JLabel(headText);
            head.setFont(head.getFont().deriveFont(Font.BOLD, head.getFont().getSize2D() * 0.85f));
            head.setForeground(dim);
            head.setHorizontalAlignment(mine ? SwingConstants.RIGHT : SwingConstants.LEFT);
            text = new JTextArea(body);
            text.setName("chat.text");
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setOpaque(false);
            text.setBorder(null);
            text.setFont(new JLabel().getFont());
            text.setForeground(earlier ? mix(foreground, fill, 0.2f) : foreground);
            text.setSelectedTextColor(foreground);
            add(head);
            if (file == null) {
                content = null;
                add(text);
            } else {
                content = new JPanel();
                content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
                content.setOpaque(false);
                content.setName("chat.file");
                thumbnail.setAlignmentX(LEFT_ALIGNMENT);
                fileName.setAlignmentX(LEFT_ALIGNMENT);
                fileState.setAlignmentX(LEFT_ALIGNMENT);
                fileName.setForeground(text.getForeground());
                fileName.setFont(fileName.getFont().deriveFont(Font.BOLD));
                fileState.setForeground(dim);
                fileState.setFont(fileState.getFont().deriveFont(fileState.getFont().getSize2D() * 0.9f));
                content.add(thumbnail);
                content.add(fileName);
                content.add(fileState);
                add(content);
                addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override
                    public void mouseClicked(java.awt.event.MouseEvent e) {
                        if (box().contains(e.getPoint()) && openable()) open.accept(Bubble.this.file.path());
                    }
                });
                update(file);
            }
        }

        private boolean openable() {
            return file != null && file.status() == ChatFile.Status.DONE && file.path() != null
                    && java.nio.file.Files.exists(file.path());
        }

        /** Shows the file as it stands now; true when the bubble changed its size. */
        boolean update(ChatFile now) {
            Dimension before = content.getPreferredSize();
            file = now;
            fileName.setText(now.name());
            String size = ChatFile.sizeText(now.size(), msg.locale());
            fileState.setText(switch (now.status()) {
                case RUNNING -> msg.format(mine ? "chat.file.sending" : "chat.file.receiving", now.percent(), size);
                case FAILED -> msg.get("chat.file.failed");
                case DONE -> openable() ? size : msg.get("chat.file.missing");
            });
            boolean canOpen = openable();
            setCursor(canOpen ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR) : null);
            setToolTipText(canOpen ? msg.get("chat.file.open") : null);
            if (canOpen && now.picture() && !thumbnailAsked && now.size() < 40_000_000L) {
                thumbnailAsked = true;
                Path at = now.path();
                int max = Math.round(220 * AmadeUI.fontScale());
                Thread.ofVirtual().name("chat-thumbnail").start(() -> {
                    Image small = thumbnail(at, max);
                    if (small != null) SwingUtilities.invokeLater(() -> {
                        thumbnail.setIcon(new ImageIcon(small));
                        thumbnail.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
                        revalidate();
                        refresh();
                    });
                });
            }
            revalidate();
            repaint();
            return !before.equals(content.getPreferredSize());
        }

        private Insets pad() {
            int v = Math.round(5 * AmadeUI.fontScale());
            int h = Math.round(9 * AmadeUI.fontScale());
            return new Insets(v, h, v, h);
        }

        /** The width of the box: as wide as the text needs, at most three quarters of the chat. */
        private int boxWidth(int available) {
            Insets p = pad();
            if (content != null) {
                int max = Math.max(Math.round(120 * AmadeUI.fontScale()), available * 3 / 4);
                int w = Math.max(head.getPreferredSize().width, content.getPreferredSize().width);
                return Math.min(w + p.left + p.right, max);
            }
            FontMetrics fm = text.getFontMetrics(text.getFont());
            int widest = head.getPreferredSize().width;
            for (String l : text.getText().split("\n", -1)) widest = Math.max(widest, fm.stringWidth(l) + 2);
            int max = Math.max(Math.round(80 * AmadeUI.fontScale()), available * 3 / 4);
            return Math.min(widest + p.left + p.right, max);
        }

        private int boxHeight(int width) {
            Insets p = pad();
            if (content != null) return p.top + head.getPreferredSize().height + content.getPreferredSize().height + p.bottom;
            int inner = width - p.left - p.right;
            text.setSize(inner, Short.MAX_VALUE);
            return p.top + head.getPreferredSize().height + text.getPreferredSize().height + p.bottom;
        }

        private int available() {
            java.awt.Container parent = getParent();
            int w = parent == null ? 0 : parent.getWidth() - parent.getInsets().left - parent.getInsets().right;
            return w > 0 ? w : Math.round(260 * AmadeUI.fontScale());
        }

        @Override
        public Dimension getPreferredSize() {
            int width = available();
            return new Dimension(width, boxHeight(boxWidth(width)));
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        public float getAlignmentX() {
            return LEFT_ALIGNMENT;
        }

        private Rectangle box() {
            int w = boxWidth(getWidth());
            return new Rectangle(mine ? getWidth() - w : 0, 0, w, boxHeight(w));
        }

        @Override
        public void doLayout() {
            Rectangle b = box();
            Insets p = pad();
            int headHeight = head.getPreferredSize().height;
            head.setBounds(b.x + p.left, p.top, b.width - p.left - p.right, headHeight);
            (content != null ? content : text).setBounds(b.x + p.left, p.top + headHeight,
                    b.width - p.left - p.right, b.height - p.top - p.bottom - headHeight);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Rectangle b = box();
            int arc = Math.round(14 * AmadeUI.fontScale());
            g2.setColor(fill);
            g2.fillRoundRect(b.x, b.y, b.width - 1, b.height - 1, arc, arc);
            g2.dispose();
        }
    }

    /** The picture at most {@code max} pixels wide and three quarters of that high; null if it cannot be read. */
    private static Image thumbnail(Path file, int max) {
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) return null;
            double k = Math.min(1.0, Math.min((double) max / image.getWidth(), max * 0.75 / image.getHeight()));
            int w = Math.max(1, (int) Math.round(image.getWidth() * k));
            int h = Math.max(1, (int) Math.round(image.getHeight() * k));
            BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = small.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, w, h, null);
            g.dispose();
            return small;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    private static Color mix(Color a, Color b, float amountOfB) {
        float k = 1 - amountOfB;
        return new Color(Math.round(a.getRed() * k + b.getRed() * amountOfB),
                Math.round(a.getGreen() * k + b.getGreen() * amountOfB),
                Math.round(a.getBlue() * k + b.getBlue() * amountOfB));
    }
}
