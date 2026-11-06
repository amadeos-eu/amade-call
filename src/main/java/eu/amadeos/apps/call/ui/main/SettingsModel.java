package eu.amadeos.apps.call.ui.main;

import eu.amadeos.apps.call.media.Devices;

import java.util.List;
import java.util.Optional;

/**
 * The values of the settings dialog, and the choices it offers.
 *
 * @param name             the name others see
 * @param camera           the chosen camera's device node; empty for the first one
 * @param microphone       the chosen source; empty for the desktop default
 * @param speaker          the chosen sink; empty for the desktop default
 * @param cameraOnAtStart  a video call starts with the camera on
 * @param echoCancellation removes the far end's voice from the microphone
 * @param echoAvailable    GStreamer offers echo cancellation
 * @param port             the call port
 * @param waitAfterLogin   start in the background at login, so that calls come in
 * @param ownKey           this computer's key fingerprint, shown for the curious
 */
public record SettingsModel(String name, Optional<String> camera, Optional<String> microphone, Optional<String> speaker,
                            boolean cameraOnAtStart, boolean echoCancellation, boolean echoAvailable, int port,
                            boolean waitAfterLogin, String ownKey, List<Devices.Choice> cameras,
                            List<Devices.Choice> microphones, List<Devices.Choice> speakers) {
    public SettingsModel {
        cameras = List.copyOf(cameras);
        microphones = List.copyOf(microphones);
        speakers = List.copyOf(speakers);
    }
}
