package org.pepsoft.worldpainter;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertTrue;

/** Makes exceptions from queued Swing callbacks fail the desktop test that scheduled them. */
public final class EdtFailureRecorder implements AutoCloseable {
    public EdtFailureRecorder() {
        previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (thread.getName().startsWith("AWT-EventQueue")) failures.add(failure);
            else if (previous != null) previous.uncaughtException(thread, failure);
            else failure.printStackTrace();
        });
    }

    @Override public void close() throws Exception {
        try {
            // Utilities and focus callbacks may themselves enqueue another callback.
            for (int i = 0; i < 4; i++) SwingUtilities.invokeAndWait(() -> { });
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
        for (Throwable failure : failures) failure.printStackTrace();
        assertTrue("Uncaught Swing callback failures: " + failures, failures.isEmpty());
    }

    private final Thread.UncaughtExceptionHandler previous;
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
}
