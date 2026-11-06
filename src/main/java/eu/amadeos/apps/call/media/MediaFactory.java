package eu.amadeos.apps.call.media;

import java.util.List;

/** Makes the {@link MediaSession} of each call. */
public interface MediaFactory {
    MediaSession create(MediaSettings settings, MediaEvents events);

    /** The GStreamer parts that are missing for calls; empty when everything is there. */
    List<String> missing(Sources sources);
}
