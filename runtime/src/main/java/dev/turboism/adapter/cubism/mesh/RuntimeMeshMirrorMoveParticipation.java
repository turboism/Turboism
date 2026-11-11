package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.plugin.Registration;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared plugin-policy registry consulted by the exact 5.2.03 movement hook. */
public final class RuntimeMeshMirrorMoveParticipation {

    private final AtomicInteger participants = new AtomicInteger();

    /** Counts one plugin movement policy; closing the handle releases the registration. */
    public Registration participate() {
        participants.incrementAndGet();
        return new Registration() {
            private boolean closed;

            @Override
            public synchronized void close() {
                if (closed) return;
                closed = true;
                participants.updateAndGet(count -> Math.max(0, count - 1));
            }
        };
    }

    boolean hasParticipants() {
        return participants.get() > 0;
    }

    void resetSession() {
        participants.set(0);
    }
}
