package eu.amadeos.apps.call.domain;

/** A call with picture and sound, or with sound only. */
public enum CallKind {
    VIDEO, AUDIO;

    public boolean video() {
        return this == VIDEO;
    }
}
