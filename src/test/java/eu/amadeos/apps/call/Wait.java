package eu.amadeos.apps.call;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/** Waits for something that happens on another thread. */
public final class Wait {
    private Wait() {
    }

    public static void until(String what, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for: " + what);
    }
}
