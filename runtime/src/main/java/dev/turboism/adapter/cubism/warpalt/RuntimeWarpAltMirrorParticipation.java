package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.sdk.cubism.warp.WarpAltMirrorParticipation;
import dev.turboism.sdk.plugin.Registration;

import java.util.concurrent.atomic.AtomicInteger;

/** Shared plugin-policy registry consulted by the exact 5.3.03 warp drag-tick hook. */
public final class RuntimeWarpAltMirrorParticipation implements WarpAltMirrorParticipation {

    private final AtomicInteger participants = new AtomicInteger();

    @Override
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

    @Override
    public boolean nativeMirrorActive() {
        return NativeWarpAltMirrorBridge.active();
    }

    @Override
    public void setArmedAxis(final int axis) {
        NativeWarpAltMirrorBridge.setArmedAxis(axis);
    }

    @Override
    public void setLiveCtrlDown(final boolean down) {
        NativeWarpAltMirrorBridge.setLiveCtrlDown(down);
    }

    boolean hasParticipants() {
        return participants.get() > 0;
    }

    void resetSession() {
        participants.set(0);
    }
}
