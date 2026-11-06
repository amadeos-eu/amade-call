package eu.amadeos.apps.call.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * The ring of an incoming call and the soft ring-back tone while one's own call rings at the other end, made
 * from sine tones so that no sound file has to ship. A computer without sound output stays silent.
 */
public class Ringer {
    private static final Logger LOG = LoggerFactory.getLogger(Ringer.class);
    private static final float RATE = 44100f;

    public enum Tone { RING, RINGBACK }

    private volatile Thread playing;

    public void start(Tone tone) {
        stop();
        byte[] samples = tone == Tone.RING ? ring() : ringBack();
        Thread t = Thread.ofVirtual().name("ringer").unstarted(() -> play(samples));
        playing = t;
        t.start();
    }

    public void stop() {
        Thread t = playing;
        playing = null;
        if (t != null) t.interrupt();
    }

    private void play(byte[] samples) {
        AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format, (int) RATE);
            line.start();
            while (playing == Thread.currentThread()) {
                for (int at = 0; at < samples.length && playing == Thread.currentThread(); at += 4410) {
                    line.write(samples, at, Math.min(4410, samples.length - at));
                }
            }
            line.flush();
        } catch (Exception | LinkageError e) {
            LOG.debug("No ring: {}", e.toString());
        }
    }

    /** Two short chimes (E6 and C6), then 1.6 s of silence: 2.4 s per round. */
    static byte[] ring() {
        double[][] notes = {{1318.5, 0.0, 0.35}, {1046.5, 0.4, 0.35}};
        return tones(2.4, notes, 9000);
    }

    /** A quiet 425 Hz tone for one second, then four seconds of silence, like a telephone ring-back. */
    static byte[] ringBack() {
        return tones(5.0, new double[][]{{425, 0.0, 1.0}}, 3500);
    }

    static byte[] tones(double seconds, double[][] notes, int volume) {
        int length = (int) (RATE * seconds);
        byte[] out = new byte[length * 2];
        for (double[] n : notes) {
            int from = (int) (n[1] * RATE), to = Math.min(length, (int) ((n[1] + n[2]) * RATE));
            for (int i = from; i < to; i++) {
                double t = (i - from) / RATE;
                double envelope = Math.min(1, t * 80) * Math.min(1, (to - i) / RATE * 40);
                int v = (int) (Math.sin(2 * Math.PI * n[0] * t) * volume * envelope);
                int old = (short) ((out[2 * i] & 0xff) | (out[2 * i + 1] << 8));
                int sum = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, old + v));
                out[2 * i] = (byte) sum;
                out[2 * i + 1] = (byte) (sum >> 8);
            }
        }
        return out;
    }
}
