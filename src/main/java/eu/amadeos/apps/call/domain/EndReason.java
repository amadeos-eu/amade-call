package eu.amadeos.apps.call.domain;

/** Why a call ended, or why it never began. */
public enum EndReason {
    /** I hung up, or cancelled the call before it was answered. */
    HUNG_UP,
    /** The other side hung up. */
    REMOTE_HUNG_UP,
    /** The other side declined the call. */
    DECLINED,
    /** The other side is already in a call. */
    BUSY,
    /** Nobody answered in time. */
    NO_ANSWER,
    /** The caller gave up before I answered. */
    MISSED,
    /** The computer could not be reached. */
    UNREACHABLE,
    /** The computer answered with another key than the one known for this person. */
    WRONG_KEY,
    /** The connection broke during the call. */
    CONNECTION_LOST,
    /** Picture and sound could not be set up (missing GStreamer parts, no route between the computers). */
    MEDIA_FAILED
}
