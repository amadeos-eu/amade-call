package eu.amadeos.apps.call.media;

/** Picture and sound of one call. All methods may be called from any thread. */
public interface MediaSession extends AutoCloseable {
    /** Starts the devices and, for the caller, the offer. */
    void start();

    /** The offer or answer of the other side; an offer is answered through {@link MediaEvents#localDescription}. */
    void remoteDescription(String type, String sdp);

    void iceCandidate(int index, String candidate);

    void setMicrophoneOn(boolean on);

    /** Sends the camera picture, or a black picture instead. */
    void setCameraOn(boolean on);

    /** Sends the screen instead of the camera, or the camera again. */
    void setScreenSharing(boolean on);

    @Override
    void close();
}
