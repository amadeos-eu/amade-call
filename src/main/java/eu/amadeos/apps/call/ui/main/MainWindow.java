package eu.amadeos.apps.call.ui.main;

import eu.amadeos.apps.call.bootstrap.AmadeCallApplication;
import eu.amadeos.apps.call.domain.CallKind;
import eu.amadeos.apps.call.domain.CallRecord;
import eu.amadeos.apps.call.domain.PeerKey;
import eu.amadeos.apps.call.domain.Person;
import eu.amadeos.apps.call.domain.SafetyCode;
import eu.amadeos.apps.call.ui.CallGlyph;
import eu.amadeos.apps.call.ui.call.CallView;
import eu.amadeos.apps.call.ui.call.CallWindow;
import eu.amadeos.apps.call.ui.dialogs.IncomingCallDialog;
import eu.amadeos.apps.call.ui.dialogs.IncomingView;
import eu.amadeos.apps.call.ui.dialogs.SafetyCodeDialog;
import eu.amadeos.apps.call.ui.dialogs.SettingsDialog;
import eu.amadeos.commons.desktop.Notification;
import eu.amadeos.commons.desktop.Notifications;
import eu.amadeos.commons.i18n.LocaleSettings;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.swing.EdtExecutor;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Frame;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URL;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The main window: the people on the left (seen in the network, called before), the chosen one on the right
 * with the call buttons and the calls with them, and the status bar.
 */
public final class MainWindow extends JFrame implements MainView {
    private final Messages msg;
    private final LocaleSettings locales;
    private final DefaultListModel<Person> model = new DefaultListModel<>();
    private final JList<Person> list = new JList<>(model);
    private final JTextField search = new JTextField();
    private final JLabel status = new JLabel(" ");
    private final JPanel details = new JPanel(new BorderLayout());
    private List<Person> people = List.of();
    private MainPresenter presenter;
    private MainMenuBar menuBar;
    private boolean updating;
    private Optional<PeerKey> selected = Optional.empty();
    private final List<CallWindow> calls = new ArrayList<>();

    public MainWindow(Messages msg, LocaleSettings locales) {
        super(msg.get("window.title"));
        EdtExecutor.requireEdt();
        this.msg = msg;
        this.locales = locales;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        URL icon = getClass().getResource("/icons/amade-call.png");
        if (icon != null) setIconImage(new ImageIcon(icon).getImage());
    }

    public void bind(MainPresenter newPresenter) {
        EdtExecutor.requireEdt();
        presenter = newPresenter;
        menuBar = new MainMenuBar(presenter, msg, locales);
        setJMenuBar(menuBar);

        list.setName("people");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new PersonRenderer(msg));
        list.addListSelectionListener(e -> {
            if (updating || e.getValueIsAdjusting()) return;
            Person p = list.getSelectedValue();
            selected = Optional.ofNullable(p).map(Person::key);
            presenter.select(p == null ? null : p.key());
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && list.getSelectedValue() != null) presenter.callSelected(CallKind.VIDEO);
            }
        });
        search.setName("search");
        search.setToolTipText(msg.get("people.search"));
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filter();
            }
        });

        JPanel left = new JPanel(new BorderLayout(0, 4));
        JPanel searchRow = new JPanel(new BorderLayout(4, 0));
        searchRow.add(Mnemonics.apply(new JLabel(), msg.get("people.search")), BorderLayout.WEST);
        searchRow.add(search);
        left.add(searchRow, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(Math.round(240 * AmadeUI.fontScale()), 300));
        left.add(scroll, BorderLayout.CENTER);

        details.setName("details");
        details.setBorder(BorderFactory.createCompoundBorder(AmadeUI.sunken(), BorderFactory.createEmptyBorder(14, 18, 14, 18)));

        JPanel body = new JPanel(new BorderLayout(6, 0));
        body.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 6));
        body.add(left, BorderLayout.WEST);
        body.add(details, BorderLayout.CENTER);

        status.setName("status");
        status.setBorder(BorderFactory.createCompoundBorder(AmadeUI.sunken(), BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
        statusBar.add(status);

        add(body, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                presenter.windowClosing();
            }
        });
        setSize(Math.round(780 * AmadeUI.fontScale()), Math.round(520 * AmadeUI.fontScale()));
        setMinimumSize(new Dimension(560, 380));
        setLocationRelativeTo(null);
    }

    // ---------------------------------------------------------------- the list

    @Override
    public void showPeople(List<Person> newPeople, Optional<PeerKey> newSelected) {
        people = List.copyOf(newPeople);
        selected = newSelected;
        filter();
    }

    private void filter() {
        String q = search.getText().strip().toLowerCase(Locale.ROOT);
        updating = true;
        try {
            model.clear();
            for (Person p : people) {
                if (q.isEmpty() || p.name().toLowerCase(Locale.ROOT).contains(q)
                        || p.endpoint().map(e -> e.toString().contains(q)).orElse(false)) {
                    model.addElement(p);
                }
            }
            list.clearSelection();
            for (int i = 0; i < model.size(); i++) {
                if (selected.isPresent() && model.get(i).key().equals(selected.get())) {
                    list.setSelectedIndex(i);
                    list.ensureIndexIsVisible(i);
                }
            }
        } finally {
            updating = false;
        }
    }

    // ---------------------------------------------------------------- the details

    @Override
    public void showDetails(Optional<Person> person, List<CallRecord> history, boolean inCall) {
        details.removeAll();
        if (person.isEmpty()) {
            details.add(emptyHint(), BorderLayout.CENTER);
        } else {
            details.add(personPanel(person.get(), history, inCall), BorderLayout.CENTER);
        }
        menuBar.showPerson(person.isPresent() && person.get().callable() && !inCall, person.isPresent(), inCall);
        details.revalidate();
        details.repaint();
    }

    private JComponent emptyHint() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(msg.get("people.empty"));
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() * 1.2f));
        p.add(left(title));
        p.add(Box.createVerticalStrut(8));
        for (int i = 1; msg.has("people.empty." + i); i++) {
            p.add(left(wrap(msg.get("people.empty." + i))));
            p.add(Box.createVerticalStrut(6));
        }
        p.add(Box.createVerticalStrut(12));
        JButton address = new JButton();
        Mnemonics.apply(address, msg.get("menu.call.address"));
        address.setName("details.address");
        address.addActionListener(e -> presenter.callAddress(CallKind.VIDEO));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.add(address);
        p.add(left(row));
        p.add(Box.createVerticalGlue());
        return p;
    }

    private JComponent personPanel(Person person, List<CallRecord> history, boolean inCall) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel avatar = new JLabel(new Avatar(person.name(), Math.round(72 * AmadeUI.fontScale())));
        p.add(left(avatar));
        p.add(Box.createVerticalStrut(8));
        JLabel name = new JLabel(person.name());
        name.setName("details.name");
        name.setFont(name.getFont().deriveFont(Font.BOLD, name.getFont().getSize2D() * 1.4f));
        p.add(left(name));
        String where = person.nearby() ? msg.format("details.nearby", person.endpoint().map(Object::toString).orElse(""))
                : person.endpoint().map(e -> msg.format("details.last-address", e)).orElse(msg.get("details.no-address"));
        p.add(left(new JLabel(where)));
        JLabel trust = new JLabel(msg.get(person.verified() ? "details.verified" : "details.not-verified"));
        trust.setName("details.trust");
        trust.setIcon(new LockIcon(person.verified() ? LockIcon.VERIFIED : LockIcon.OPEN));
        if (person.verified()) trust.setForeground(LockIcon.VERIFIED);
        trust.setToolTipText(msg.format("details.key", person.key().shortText()));
        p.add(Box.createVerticalStrut(4));
        p.add(left(trust));
        p.add(Box.createVerticalStrut(12));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JButton video = button("details.video", CallGlyph.CAMERA, () -> presenter.callSelected(CallKind.VIDEO));
        JButton audio = button("details.audio", CallGlyph.AUDIO_CALL, () -> presenter.callSelected(CallKind.AUDIO));
        JButton chat = button("details.chat", CallGlyph.CHAT, () -> presenter.callSelected(CallKind.CHAT));
        JButton verify = button("details.verify", CallGlyph.VERIFY, presenter::verifySelected);
        boolean callable = person.callable() && !inCall;
        video.setEnabled(callable);
        audio.setEnabled(callable);
        chat.setEnabled(callable);
        buttons.add(video);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(audio);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(chat);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(verify);
        p.add(left(buttons));

        p.add(left(section(msg.get("details.recent"))));
        if (history.isEmpty()) {
            JLabel none = new JLabel(msg.get("details.recent.none"));
            none.setForeground(AmadeUI.theme().textInactive());
            p.add(left(none));
        }
        DateTimeFormatter when = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(msg.locale());
        for (CallRecord r : history.subList(0, Math.min(8, history.size()))) {
            String time = when.format(ZonedDateTime.ofInstant(r.start(), ZoneId.systemDefault()));
            String arrow = switch (r.direction()) {
                case OUTGOING -> "↗";
                case INCOMING, MISSED -> "↙";
            };
            String text = arrow + "  " + time + "   " + switch (r.direction()) {
                case MISSED -> msg.get("recent.missed");
                default -> msg.get("recent." + r.kind().name().toLowerCase(Locale.ROOT))
                        + (r.length().isZero() ? "   " + msg.get("recent.not-answered") : "   " + length(r.length()));
            };
            JLabel line = new JLabel(text);
            line.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
            if (r.direction() == CallRecord.Direction.MISSED) line.setForeground(new Color(0xc62828));
            p.add(left(line));
        }
        p.add(Box.createVerticalGlue());
        return p;
    }

    static String length(Duration d) {
        long s = d.toSeconds();
        if (s < 60) return s + " s";
        if (s < 3600) return (s / 60) + " min";
        return String.format("%d h %02d min", s / 3600, (s / 60) % 60);
    }

    /** A button with only an icon; its text is the tooltip. */
    private JButton button(String key, CallGlyph glyph, Runnable action) {
        JButton b = glyph.on(new JButton(), msg.get(key), null);
        b.setMargin(new Insets(4, 10, 4, 10));
        b.setName(key);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JComponent section(String title) {
        JLabel l = new JLabel(title);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(14, 0, 4, 0),
                BorderFactory.createMatteBorder(0, 0, 1, 0, AmadeUI.theme().shadow())));
        return l;
    }

    private static JLabel wrap(String text) {
        return new JLabel("<html><div style='width:340px'>" + escape(text) + "</div></html>");
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static <C extends JComponent> C left(C c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    // ---------------------------------------------------------------- MainView

    @Override
    public void showStatus(String text) {
        status.setText(text == null || text.isBlank() ? " " : text);
    }

    @Override
    public void showError(String message) {
        JOptionPane.showMessageDialog(isShowing() ? this : null, plain(message), msg.get("dialog.error.title"),
                JOptionPane.ERROR_MESSAGE);
    }

    @Override
    public void showMissing(List<String> parts) {
        showError(msg.format("error.missing", String.join("\n", parts)));
    }

    @Override
    public Optional<String> askAddress(String previous) {
        Object answer = JOptionPane.showInputDialog(this, plain(msg.get("dialog.address.text")),
                msg.get("dialog.address.title"), JOptionPane.QUESTION_MESSAGE, null, null, previous);
        return Optional.ofNullable(answer).map(Object::toString);
    }

    @Override
    public void showSettings(SettingsModel model, Consumer<SettingsModel> apply) {
        SettingsDialog dialog = new SettingsDialog(this, msg, model);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        dialog.result().ifPresent(apply);
    }

    @Override
    public Verdict askSafetyCode(String name, SafetyCode code, boolean verified) {
        Component owner = calls.isEmpty() ? this : calls.getLast();
        return SafetyCodeDialog.ask(owner, msg, name, code, verified);
    }

    @Override
    public boolean confirmClearChat(String name) {
        Object[] options = {msg.get("dialog.clear-chat.clear"), msg.get("dialog.cancel")};
        return JOptionPane.showOptionDialog(this, plain(msg.format("dialog.clear-chat.text", name)),
                msg.get("dialog.clear-chat.title"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null,
                options, options[1]) == 0;
    }

    @Override
    public Optional<Integer> chooseContact(String name, List<String> contacts) {
        List<String> choices = new ArrayList<>();
        choices.add(msg.format("dialog.contact.new", name));
        choices.addAll(contacts);
        javax.swing.JComboBox<String> box = new javax.swing.JComboBox<>(choices.toArray(String[]::new));
        box.setName("dialog.contact.choice");
        // A contact of the same name is most likely the one.
        int same = contacts.indexOf(name);
        box.setSelectedIndex(same >= 0 ? same + 1 : 0);
        Object[] content = {plain(msg.format("dialog.contact.text", name)), box};
        int answer = JOptionPane.showConfirmDialog(this, content, msg.get("dialog.contact.title"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        return answer == JOptionPane.OK_OPTION ? Optional.of(box.getSelectedIndex() - 1) : Optional.empty();
    }

    @Override
    public void showInfo(String message) {
        JOptionPane.showMessageDialog(isShowing() ? this : null, plain(message), msg.get("dialog.info.title"),
                JOptionPane.INFORMATION_MESSAGE);
    }

    @Override
    public boolean confirmForget(String name) {
        Object[] options = {msg.get("dialog.forget.forget"), msg.get("dialog.cancel")};
        return JOptionPane.showOptionDialog(this, plain(msg.format("dialog.forget.text", name)),
                msg.get("dialog.forget.title"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null,
                options, options[1]) == 0;
    }

    @Override
    public CallView openCall(String name, CallKind kind, boolean verified) {
        CallWindow window = new CallWindow(msg, presenter, name, kind, verified, getIconImage());
        calls.add(window);
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                calls.remove(window);
            }
        });
        window.setLocationRelativeTo(isShowing() ? this : null);
        window.setVisible(true);
        return window;
    }

    @Override
    public IncomingView showIncoming(String name, CallKind kind, boolean known, boolean verified) {
        IncomingCallDialog dialog = new IncomingCallDialog(msg, presenter, name, kind, known, verified, getIconImage());
        dialog.setLocationRelativeTo(isShowing() ? this : null);
        dialog.setVisible(true);
        dialog.toFront();
        return dialog;
    }

    @Override
    public void announceMissed(String name, Runnable callBack) {
        Notifications.send(Notification.of(msg.get("window.title"))
                .body(Notification.escape(msg.format("missed.text", name)))
                .icon(getIconImage())
                .category("im.received")
                .action("call-back", msg.get("missed.call-back"), () -> SwingUtilities.invokeLater(callBack))
                .defaultAction(() -> SwingUtilities.invokeLater(this::bringToFront)));
    }

    @Override
    public void showAbout(String ownKey) {
        JOptionPane.showMessageDialog(this,
                "<html><center><h2>amade-call " + AmadeCallApplication.version() + "</h2>"
                        + "<p>" + msg.get("dialog.about.text") + "</p><br>"
                        + "<small>" + msg.format("dialog.about.key", ownKey) + "</small><br><br>"
                        + "<small>" + msg.get("dialog.about.libraries") + "</small><br><br>"
                        + "<small>&copy; 2026 datazuul.com &bull; EUPL-1.2</small></center></html>",
                msg.get("dialog.about.title"), JOptionPane.INFORMATION_MESSAGE);
    }

    @Override
    public void bringToFront() {
        if (!isVisible()) setVisible(true);
        if (getState() == Frame.ICONIFIED) setState(Frame.NORMAL);
        toFront();
        requestFocus();
    }

    @Override
    public void hideWindow() {
        setVisible(false);
    }

    @Override
    public void close() {
        for (CallWindow w : List.copyOf(calls)) w.dispose();
        dispose();
    }

    static JTextArea plain(String message) {
        JTextArea text = new JTextArea(message);
        text.setEditable(false);
        text.setOpaque(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setColumns(42);
        // Without a width the text area asks for one line, so the dialog cut off its buttons; with the width of
        // its columns it asks for the height of all its wrapped lines.
        text.setSize(text.getPreferredSize().width, Short.MAX_VALUE);
        return text;
    }

    // ---------------------------------------------------------------- for tests and screenshots

    JList<Person> peopleList() {
        return list;
    }

    JPanel detailsPanel() {
        return details;
    }

    String statusText() {
        return status.getText();
    }

    MainMenuBar menu() {
        return menuBar;
    }

    JTextField searchField() {
        return search;
    }
}
