package eu.amadeos.apps.call.ui.main;

import eu.amadeos.apps.call.domain.CallKind;
import eu.amadeos.commons.i18n.Language;
import eu.amadeos.commons.i18n.LocaleSettings;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.swing.EdtExecutor;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import javax.swing.border.BevelBorder;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Call, Person, Options and Help. */
public final class MainMenuBar extends JMenuBar {
    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;

    private final Messages msg;
    private final Map<String, JMenuItem> items = new HashMap<>();

    public MainMenuBar(MainPresenter presenter, Messages msg, LocaleSettings locales) {
        EdtExecutor.requireEdt();
        this.msg = msg;
        setBorder(BorderFactory.createBevelBorder(BevelBorder.RAISED));

        JMenu call = menu("menu.call");
        call.add(item("menu.call.video", key(KeyEvent.VK_ENTER, CTRL), () -> presenter.callSelected(CallKind.VIDEO)));
        call.add(item("menu.call.audio", key(KeyEvent.VK_ENTER, CTRL | InputEvent.SHIFT_DOWN_MASK),
                () -> presenter.callSelected(CallKind.AUDIO)));
        call.add(item("menu.call.chat", key(KeyEvent.VK_T, CTRL), () -> presenter.callSelected(CallKind.CHAT)));
        call.add(item("menu.call.address", key(KeyEvent.VK_L, CTRL), () -> presenter.callAddress(CallKind.VIDEO)));
        call.addSeparator();
        call.add(item("menu.call.settings", key(KeyEvent.VK_COMMA, CTRL), presenter::openSettings));
        call.addSeparator();
        call.add(item("menu.call.exit", key(KeyEvent.VK_Q, CTRL), presenter::exit));

        JMenu person = menu("menu.person");
        person.add(item("menu.person.verify", null, presenter::verifySelected));
        person.add(item("menu.person.forget", null, presenter::forgetSelected));

        if (locales != null) {
            JMenu options = menu("menu.options");
            options.add(languageMenu(presenter, locales));
        }

        JMenu help = menu("menu.help");
        help.add(item("menu.help.about", null, presenter::about));
    }

    /** Enables what fits the chosen person. */
    void showPerson(boolean callable, boolean chosen, boolean inCall) {
        items.get("menu.call.video").setEnabled(callable);
        items.get("menu.call.audio").setEnabled(callable);
        items.get("menu.call.chat").setEnabled(callable);
        items.get("menu.call.address").setEnabled(!inCall);
        items.get("menu.person.verify").setEnabled(chosen);
        items.get("menu.person.forget").setEnabled(chosen && !inCall);
    }

    JMenuItem item(String name) {
        return items.get(name);
    }

    /** "Desktop setting (Deutsch)" and the five languages; the choice applies at the next start. */
    private JMenu languageMenu(MainPresenter presenter, LocaleSettings locales) {
        JMenu menu = Mnemonics.apply(new JMenu(), msg.get("menu.options.language"));
        menu.setName("menu.options.language");
        Messages commons = Messages.commons(msg.locale());
        List<Optional<Language>> choices = new ArrayList<>();
        choices.add(Optional.empty());
        for (Language language : Language.values()) choices.add(Optional.of(language));
        ButtonGroup group = new ButtonGroup();
        Optional<Language> current = locales.applicationLanguage();
        for (Optional<Language> choice : choices) {
            String label = choice.map(Language::nativeName).orElseGet(() -> commons.format("language.follow-desktop",
                    locales.desktopResolution().language().nativeName()));
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(label, choice.equals(current));
            item.setName(choice.map(Language::code).orElse(LocaleSettings.FOLLOW_DESKTOP));
            item.addActionListener(e -> presenter.setLanguage(choice));
            group.add(item);
            menu.add(item);
            if (choice.isEmpty()) menu.addSeparator();
        }
        return menu;
    }

    private static KeyStroke key(int code, int modifiers) {
        return KeyStroke.getKeyStroke(code, modifiers);
    }

    private JMenu menu(String key) {
        JMenu menu = Mnemonics.apply(new JMenu(), msg.get(key));
        menu.setName(key);
        add(menu);
        return menu;
    }

    private JMenuItem item(String key, KeyStroke accelerator, Runnable action) {
        JMenuItem item = Mnemonics.apply(new JMenuItem(), msg.get(key));
        item.setName(key);
        if (accelerator != null) item.setAccelerator(accelerator);
        item.addActionListener(e -> action.run());
        items.put(key, item);
        return item;
    }
}
