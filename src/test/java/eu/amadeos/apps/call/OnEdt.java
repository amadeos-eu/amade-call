package eu.amadeos.apps.call;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;

/** Runs test code on the event dispatch thread and rethrows its failures in the test thread. */
public final class OnEdt {
    private OnEdt() {
    }

    public static void run(Runnable test) throws Exception {
        try {
            SwingUtilities.invokeAndWait(test);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Error error) throw error;
            if (e.getCause() instanceof Exception exception) throw exception;
            throw e;
        }
    }
}
