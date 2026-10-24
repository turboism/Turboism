package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.sdk.cubism.mesh.ArtMeshMirrorService;
import dev.turboism.sdk.plugin.Registration;
import java.util.concurrent.atomic.AtomicInteger;

/** Session policy for ArtMesh mirroring, independent of Warp Deformer participation. */
public final class RuntimeArtMeshMirrorService implements ArtMeshMirrorService {
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
                if (participants.updateAndGet(count -> Math.max(0, count - 1)) == 0) setEnabled(false);
            }
        };
    }

    @Override
    public void setEnabled(boolean enabled) {
        NativeArtMeshSymmetryBridge.setMode(enabled ? 2 : 0);
    }

    @Override
    public boolean isEnabled() {
        return NativeArtMeshSymmetryBridge.isEnabled();
    }

    @Override
    public void resetAxis() {
        NativeArtMeshSymmetryBridge.resetAxes();
    }

    @Override
    public boolean nativeMirrorActive() {
        return NativeWarpAltMirrorBridge.active();
    }

    boolean hasParticipants() {
        return participants.get() > 0;
    }

    void resetSession() {
        participants.set(0);
        NativeArtMeshSymmetryBridge.reset();
    }
}
