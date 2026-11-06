package eu.amadeos.apps.call.media;

import eu.amadeos.apps.call.domain.CallKind;

import java.util.Objects;
import java.util.Optional;

/**
 * How to set up picture and sound for one call.
 *
 * @param kind             video and sound, or sound only
 * @param offerer          the caller makes the WebRTC offer, the callee answers
 * @param cameraOn         whether the camera starts on (a video call answered "sound only" starts without)
 * @param camera           the camera's device node; empty for the first one
 * @param microphone       the PulseAudio/PipeWire source; empty for the default
 * @param speaker          the PulseAudio/PipeWire sink; empty for the default
 * @param echoCancellation removes the far end's voice from the microphone when GStreamer offers it
 * @param sources          real devices or test sources
 */
public record MediaSettings(CallKind kind, boolean offerer, boolean cameraOn, Optional<String> camera,
                            Optional<String> microphone, Optional<String> speaker, boolean echoCancellation,
                            Sources sources) {
    public MediaSettings {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sources, "sources");
        camera = camera == null ? Optional.empty() : camera;
        microphone = microphone == null ? Optional.empty() : microphone;
        speaker = speaker == null ? Optional.empty() : speaker;
    }

    public MediaSettings withOfferer(boolean value) {
        return new MediaSettings(kind, value, cameraOn, camera, microphone, speaker, echoCancellation, sources);
    }

    public MediaSettings withKind(CallKind value) {
        return new MediaSettings(value, offerer, cameraOn, camera, microphone, speaker, echoCancellation, sources);
    }

    public MediaSettings withCameraOn(boolean value) {
        return new MediaSettings(kind, offerer, value, camera, microphone, speaker, echoCancellation, sources);
    }
}
