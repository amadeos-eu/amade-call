package eu.amadeos.apps.call.bootstrap;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import eu.amadeos.apps.call.media.Devices;
import eu.amadeos.apps.call.media.GstMedia;
import eu.amadeos.apps.call.media.MediaFactory;
import eu.amadeos.apps.call.media.Sources;
import eu.amadeos.apps.call.repository.AddressBook;
import eu.amadeos.apps.call.repository.CallLog;
import eu.amadeos.apps.call.repository.CallPreferences;
import eu.amadeos.apps.call.repository.ChatHistory;
import eu.amadeos.apps.call.repository.ChatKey;
import eu.amadeos.apps.call.repository.KnownPeers;
import eu.amadeos.apps.call.service.Autostart;
import eu.amadeos.apps.call.service.AvahiDiscovery;
import eu.amadeos.apps.call.service.IdentityStore;
import eu.amadeos.apps.call.service.Phone;
import eu.amadeos.apps.call.service.Ringer;
import eu.amadeos.apps.call.service.SingleInstance;
import eu.amadeos.apps.call.ui.main.MainPresenter;
import eu.amadeos.apps.call.ui.main.MainWindow;
import eu.amadeos.commons.app.Ui;
import eu.amadeos.commons.config.XdgDirectories;
import eu.amadeos.commons.desktop.SecretToolPasswordStore;
import eu.amadeos.commons.desktop.DesktopLauncher;
import eu.amadeos.commons.i18n.LocaleSettings;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.process.CommandRunner;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * amade-call bindings. Theme, language, directories and the other shared services come from
 * {@link eu.amadeos.commons.app.CommonsModule}.
 */
public final class CallModule extends AbstractModule {
    /** The texts, {@code messages.properties} and its translations. */
    public static final String MESSAGES = "eu.amadeos.apps.call.messages";

    private final Sources sources;
    private final Optional<Path> testPicture;
    private final SingleInstance instance;

    public CallModule(Sources sources, Optional<Path> testPicture, SingleInstance instance) {
        this.sources = sources;
        this.testPicture = testPicture;
        this.instance = instance;
    }

    @Override
    protected void configure() {
        bind(CallPreferences.class).in(Scopes.SINGLETON);
        bind(KnownPeers.class).in(Scopes.SINGLETON);
        bind(CallLog.class).in(Scopes.SINGLETON);
    }

    /** The texts in the language {@code AmadeApplication} chose. */
    @Provides
    @Singleton
    Messages messages(Locale locale) {
        return Messages.load(CallModule.class, MESSAGES, locale);
    }

    @Provides
    @Singleton
    MainWindow window(Messages msg, LocaleSettings locales) {
        return new MainWindow(msg, locales);
    }

    // --8<-- [start:presenter]
    @Provides
    @Singleton
    MainPresenter presenter(MainWindow window, Messages msg, LocaleSettings locales, CallPreferences preferences,
                            KnownPeers known, CallLog log, XdgDirectories xdg, ExecutorService background,
                            @Ui Executor ui) {
        MediaFactory media = new GstMedia(testPicture);
        // The key lives with the data, not with the settings: ~/.local/share/amadeos/call/identity.p12.
        IdentityStore identity = new IdentityStore(xdg.dataHome().resolve("amadeos").resolve("call"));
        Phone phone = new Phone(identity, new AvahiDiscovery(), media, Instant::now);
        // The kept chats: encrypted files with the data, their key in the login keyring.
        ChatHistory chats = new ChatHistory(xdg.dataHome().resolve("amadeos").resolve("call").resolve("chats"),
                new ChatKey(() -> new SecretToolPasswordStore("amade-call")));
        return new MainPresenter(window, msg, locales, new MainPresenter.Services(phone, media,
                new Devices(CommandRunner.system()), known, log, preferences, chats,
                new AddressBook(xdg.dataHome().resolve("amadeos").resolve("contacts")), xdg.downloads(),
                new DesktopLauncher(), new Autostart(xdg.configHome()),
                new Ringer(), sources, background, ui, Instant::now, () -> {
                    instance.close();
                    System.exit(0);
                }));
    }
    // --8<-- [end:presenter]
}
