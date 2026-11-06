package eu.amadeos.apps.call.ui.call;

import eu.amadeos.apps.call.domain.ChatLine;

import java.awt.image.BufferedImage;
import java.time.Instant;

/** The window of one call as the presenter sees it. All methods run on the EDT. */
public interface CallView {
    /** What the window says above the picture: "Calling…", "Connecting…", or nothing while the call runs. */
    void showPhase(String text);

    /** The call runs since {@code since}: the window shows the time. */
    void showActive(Instant since);

    void showRemoteFrame(BufferedImage frame);

    void showLocalFrame(BufferedImage frame);

    /** The other side's camera is off: their initials instead of a black picture. */
    void showRemoteCamera(boolean on);

    void showRemoteScreen(boolean sharing);

    void showRemoteMuted(boolean muted);

    /** My switches; {@code video} false hides camera and screen (sound-only call). */
    void showSwitches(boolean microphone, boolean camera, boolean sharing, boolean video);

    void showChat(ChatLine line);

    void showVerified(boolean verified);

    void showInfo(String text);

    /** The call ended: the reason for a moment, then the window closes. */
    void showEnded(String text);

    void close();
}
