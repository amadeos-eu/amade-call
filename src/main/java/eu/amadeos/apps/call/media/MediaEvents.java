package eu.amadeos.apps.call.media;

import java.awt.image.BufferedImage;

/** What a {@link MediaSession} reports; called on GStreamer threads. */
public interface MediaEvents {
    /** The local session description to send to the other side: {@code type} is "offer" or "answer". */
    void localDescription(String type, String sdp);

    void iceCandidate(int index, String candidate);

    /** A picture from the other side. */
    void remoteFrame(BufferedImage frame);

    /** A small picture of what this side sends (camera, screen or nothing), for the self view. */
    void localFrame(BufferedImage frame);

    /** Picture and sound flow. */
    void connected();

    /** Picture and sound could not be set up or broke down. */
    void failed(String reason);

    /** The camera could not be opened or stopped working; the call goes on without picture. */
    default void cameraFailed(String reason) {
    }

    /** Sharing the screen does not work (no X display). */
    default void screenFailed(String reason) {
    }
}
