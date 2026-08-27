package dev.turboism.bootstrap;

import java.util.Objects;

/** Constructs the daemon thread that waits for and binds the Cubism host runtime. */
final class BootstrapThreadFactory {

    private BootstrapThreadFactory() {
    }

    static Thread create(final Runnable action) {
        final Thread thread = new Thread(
            Objects.requireNonNull(action, "action"),
            "turboism-bootstrap"
        );
        thread.setDaemon(true);
        // The production agent is bootstrap-loaded through Boot-Class-Path, so its defining
        // loader is null. Publishing that value as this thread's context loader lets any first
        // Swing access seed the shared EDT with a null loader, preventing FlatLaf delegates from
        // resolving host classes. The system loader is Cubism's application/host loader.
        thread.setContextClassLoader(ClassLoader.getSystemClassLoader());
        return thread;
    }
}
