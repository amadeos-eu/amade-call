package eu.amadeos.apps.call.domain;

/** A call with picture and sound, with sound only, or a chat with text only. */
public enum CallKind {
    VIDEO, AUDIO, CHAT;

    public boolean video() {
        return this == VIDEO;
    }

    /** Whether the call has sound (and maybe picture); a chat has neither. */
    public boolean media() {
        return this != CHAT;
    }
}
