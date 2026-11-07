package eu.amadeos.apps.call.ui.dialogs;

import eu.amadeos.apps.call.domain.Endpoint;
import eu.amadeos.apps.call.media.Devices;
import eu.amadeos.apps.call.ui.main.SettingsModel;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.swing.AmadeUI;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Optional;

/**
 * Settings: the name others see, camera, microphone and speakers, how a call starts, the call port and whether
 * the program waits for calls after login. OK keeps the values, Cancel forgets them.
 */
public final class SettingsDialog extends JDialog {
    private final JTextField name = new JTextField(24);
    private final JComboBox<Devices.Choice> camera = new JComboBox<>();
    private final JComboBox<Devices.Choice> microphone = new JComboBox<>();
    private final JComboBox<Devices.Choice> speaker = new JComboBox<>();
    private final JCheckBox cameraOn = new JCheckBox();
    private final JCheckBox echo = new JCheckBox();
    private final JCheckBox waitAfterLogin = new JCheckBox();
    private final JCheckBox keepChats = new JCheckBox();
    private final JSpinner port = new JSpinner(new SpinnerNumberModel(Endpoint.DEFAULT_PORT, 1024, 65535, 1));
    private final SettingsModel model;
    private Optional<SettingsModel> result = Optional.empty();

    public SettingsDialog(Window owner, Messages msg, SettingsModel model) {
        super(owner, msg.get("settings.title"), ModalityType.APPLICATION_MODAL);
        this.model = model;
        setName("settings");
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 14, 6, 14));
        int row = 0;
        row = section(form, row, msg.get("settings.section.you"));
        name.setText(model.name());
        name.setName("settings.name");
        row = field(form, row, msg.get("settings.name"), name, msg.get("settings.name.hint"));

        row = section(form, row, msg.get("settings.section.devices"));
        Devices.Choice firstCamera = new Devices.Choice("", msg.get("settings.camera.first"));
        fill(camera, firstCamera, model.cameras(), model.camera());
        camera.setName("settings.camera");
        row = field(form, row, msg.get("settings.camera"), camera, null);
        fill(microphone, new Devices.Choice("", msg.get("settings.default")), model.microphones(), model.microphone());
        microphone.setName("settings.microphone");
        row = field(form, row, msg.get("settings.microphone"), microphone, null);
        fill(speaker, new Devices.Choice("", msg.get("settings.default")), model.speakers(), model.speaker());
        speaker.setName("settings.speaker");
        row = field(form, row, msg.get("settings.speaker"), speaker, null);

        row = section(form, row, msg.get("settings.section.calls"));
        Mnemonics.apply(cameraOn, msg.get("settings.camera-on"));
        cameraOn.setSelected(model.cameraOnAtStart());
        cameraOn.setName("settings.camera-on");
        row = wide(form, row, cameraOn);
        Mnemonics.apply(echo, msg.get("settings.echo"));
        echo.setSelected(model.echoCancellation() && model.echoAvailable());
        echo.setEnabled(model.echoAvailable());
        echo.setName("settings.echo");
        if (!model.echoAvailable()) echo.setToolTipText(msg.get("settings.echo.missing"));
        row = wide(form, row, echo);
        Mnemonics.apply(waitAfterLogin, msg.get("settings.wait"));
        waitAfterLogin.setSelected(model.waitAfterLogin());
        waitAfterLogin.setName("settings.wait");
        row = wide(form, row, waitAfterLogin);
        row = wide(form, row, hint(msg.get("settings.wait.hint")));
        Mnemonics.apply(keepChats, msg.get("settings.keep-chats"));
        keepChats.setSelected(model.keepChats() && model.chatsAvailable());
        keepChats.setEnabled(model.chatsAvailable());
        keepChats.setName("settings.keep-chats");
        row = wide(form, row, keepChats);
        row = wide(form, row, hint(msg.get(model.chatsAvailable() ? "settings.keep-chats.hint"
                : "settings.keep-chats.no-keyring")));

        row = section(form, row, msg.get("settings.section.network"));
        port.setValue(model.port());
        port.setName("settings.port");
        port.setEditor(new JSpinner.NumberEditor(port, "#"));
        row = field(form, row, msg.get("settings.port"), port, msg.get("settings.port.hint"));
        if (!model.ownKey().isEmpty()) {
            JLabel key = new JLabel(group(model.ownKey()));
            key.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.round(11 * AmadeUI.fontScale())));
            key.setName("settings.key");
            field(form, row, msg.get("settings.key"), key, msg.get("settings.key.hint"));
        }

        JButton ok = new JButton(msg.get("dialog.ok"));
        ok.setName("settings.ok");
        ok.addActionListener(e -> {
            result = Optional.of(values());
            dispose();
        });
        JButton cancel = new JButton(msg.get("dialog.cancel"));
        cancel.setName("settings.cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
        buttons.add(ok);
        buttons.add(cancel);
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        pack();
        setResizable(false);
    }

    /** The key in groups of four, two lines. */
    static String group(String hex) {
        StringBuilder b = new StringBuilder("<html>");
        for (int i = 0; i < hex.length(); i += 4) {
            b.append(hex, i, Math.min(hex.length(), i + 4)).append(i == 28 ? "<br>" : " ");
        }
        return b.append("</html>").toString();
    }

    SettingsModel values() {
        return new SettingsModel(name.getText().strip(), chosen(camera), chosen(microphone), chosen(speaker),
                cameraOn.isSelected(), echo.isSelected() || (!model.echoAvailable() && model.echoCancellation()),
                model.echoAvailable(), ((Number) port.getValue()).intValue(), waitAfterLogin.isSelected(),
                model.ownKey(), keepChats.isSelected() || (!model.chatsAvailable() && model.keepChats()),
                model.chatsAvailable(), model.cameras(), model.microphones(), model.speakers());
    }

    public Optional<SettingsModel> result() {
        return result;
    }

    private static Optional<String> chosen(JComboBox<Devices.Choice> box) {
        Devices.Choice c = (Devices.Choice) box.getSelectedItem();
        return c == null || c.id().isEmpty() ? Optional.empty() : Optional.of(c.id());
    }

    private static void fill(JComboBox<Devices.Choice> box, Devices.Choice first, List<Devices.Choice> choices,
                             Optional<String> current) {
        box.addItem(first);
        for (Devices.Choice c : choices) box.addItem(c);
        box.setSelectedIndex(0);
        if (current.isPresent()) {
            boolean found = false;
            for (int i = 0; i < box.getItemCount(); i++) {
                if (box.getItemAt(i).id().equals(current.get())) {
                    box.setSelectedIndex(i);
                    found = true;
                }
            }
            // A device that is not plugged in now stays chosen.
            if (!found) {
                Devices.Choice missing = new Devices.Choice(current.get(), current.get());
                box.addItem(missing);
                box.setSelectedItem(missing);
            }
        }
    }

    private static int section(JPanel form, int row, String title) {
        JLabel l = new JLabel(title);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(row == 0 ? 0 : 10, 0, 2, 0),
                BorderFactory.createMatteBorder(0, 0, 1, 0, AmadeUI.theme().shadow())));
        GridBagConstraints c = constraints(0, row);
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(l, c);
        return row + 1;
    }

    private static int field(JPanel form, int row, String label, JComponent field, String hint) {
        JLabel l = Mnemonics.apply(new JLabel(), label);
        l.setLabelFor(field);
        form.add(l, constraints(0, row));
        GridBagConstraints c = constraints(1, row);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(field, c);
        row++;
        if (hint != null) {
            GridBagConstraints h = constraints(1, row);
            form.add(hint(hint), h);
            row++;
        }
        return row;
    }

    private static int wide(JPanel form, int row, JComponent component) {
        GridBagConstraints c = constraints(0, row);
        c.gridwidth = 2;
        form.add(component, c);
        return row + 1;
    }

    private static JLabel hint(String text) {
        JLabel l = new JLabel("<html><div style='width:" + Math.round(300 * AmadeUI.fontScale()) + "px'>" + text + "</div></html>");
        l.setFont(l.getFont().deriveFont(l.getFont().getSize2D() * 0.9f));
        l.setForeground(AmadeUI.theme().textInactive());
        return l;
    }

    private static GridBagConstraints constraints(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 4, 3, 4);
        return c;
    }
}
