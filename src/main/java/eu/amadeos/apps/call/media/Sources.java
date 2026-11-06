package eu.amadeos.apps.call.media;

/** Where picture and sound come from and go to. */
public enum Sources {
    /** The chosen camera, microphone and speakers. */
    DEVICES,
    /** A moving test picture instead of the camera (computers without camera, screenshots); real sound. */
    TEST_PICTURE,
    /** Test picture and test tone, nothing played or shown on a device: for the automatic tests. */
    HEADLESS
}
