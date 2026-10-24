package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.plugin.Registration;

/** Independent mirror editing for ArtMesh vertices in ordinary modeling mode. */
@CubismEditor(from = "5.0.00")
public interface ArtMeshMirrorService {
    /** Enables this plugin's participation until the returned registration is closed. */
    Registration participate();
    /** Toggles ArtMesh mirroring without changing Warp Deformer mirror mode. */
    void setEnabled(boolean enabled);
    /** Returns whether ArtMesh mirroring is enabled. */
    boolean isEnabled();
    /** Resets axes to the selected meshes' center on their next render. */
    void resetAxis();
    /** Returns whether the native mirror hook is installed. */
    boolean nativeMirrorActive();
    /** Returns whether this service has a runtime implementation. */
    default boolean isAvailable() {
        return true;
    }
    /** Returns the unavailable service sentinel. */
    static ArtMeshMirrorService unavailable() {
        return Unavailable.INSTANCE;
    }
    /** Unavailable service sentinel. */
    enum Unavailable implements ArtMeshMirrorService {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Registration participate() {
            throw failure();
        }

        @Override
        public void setEnabled(boolean enabled) {
            throw failure();
        }

        @Override
        public boolean isEnabled() {
            throw failure();
        }

        @Override
        public void resetAxis() {
            throw failure();
        }

        @Override
        public boolean nativeMirrorActive() {
            throw failure();
        }

        private static UnsupportedOperationException failure() {
            return new UnsupportedOperationException("ArtMesh mirror service is not available");
        }
    }
}
