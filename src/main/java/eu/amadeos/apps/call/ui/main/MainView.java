package eu.amadeos.apps.call.ui.main;

import eu.amadeos.apps.call.domain.CallKind;
import eu.amadeos.apps.call.domain.CallRecord;
import eu.amadeos.apps.call.domain.PeerKey;
import eu.amadeos.apps.call.domain.Person;
import eu.amadeos.apps.call.domain.SafetyCode;
import eu.amadeos.apps.call.ui.call.CallView;
import eu.amadeos.apps.call.ui.dialogs.IncomingView;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** The main window as the presenter sees it. All methods run on the EDT. */
public interface MainView {
    /** What the user decided about a safety code. */
    enum Verdict { MATCH, DIFFERENT, LATER }

    /** The list on the left; {@code selected} stays selected if it is still there. */
    void showPeople(List<Person> people, Optional<PeerKey> selected);

    /** The right side: the chosen person and the calls with them; empty shows a short hint. */
    void showDetails(Optional<Person> person, List<CallRecord> calls, boolean inCall);

    /** The status bar: name, port and whether the computer is seen in the network. */
    void showStatus(String text);

    void showError(String message);

    /** The GStreamer parts that are missing for calls. */
    void showMissing(List<String> parts);

    /** Asks for an address to call (Call → Call Address); empty when cancelled. */
    Optional<String> askAddress(String previous);

    /** The settings dialog; {@code apply} gets the new values when the user presses OK. */
    void showSettings(SettingsModel model, Consumer<SettingsModel> apply);

    /** Shows the safety code of a call (or of an earlier call) and asks whether it is the same at the other side. */
    Verdict askSafetyCode(String name, SafetyCode code, boolean verified);

    boolean confirmForget(String name);

    /** Opens the window of a call. */
    CallView openCall(String name, CallKind kind, boolean verified);

    /** The window of a ringing call. */
    IncomingView showIncoming(String name, CallKind kind, boolean known, boolean verified);

    /** A desktop notification about a missed call; {@code callBack} calls back. */
    void announceMissed(String name, Runnable callBack);

    void showAbout(String ownKey);

    /** Shows the window (from the background, or when the program is started a second time). */
    void bringToFront();

    /** Hides the window; the program keeps waiting for calls. */
    void hideWindow();

    void close();
}
