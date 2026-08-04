package dev.turboism.adapter.host;

import dev.turboism.sdk.cubism.core.CoreCapabilities;
import dev.turboism.sdk.cubism.core.CoreRuntimeInfo;
import dev.turboism.sdk.cubism.core.CoreVersion;
import dev.turboism.sdk.cubism.core.MocConsistency;
import dev.turboism.sdk.cubism.core.MocData;
import dev.turboism.sdk.cubism.core.MocInfo;
import dev.turboism.sdk.cubism.core.MocInspector;
import dev.turboism.sdk.cubism.core.MocVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamicCoreRuntimeInfoTest {

    @Test
    void retainedInspectorsBecomeStaleOnReplacementAndDisconnect() {
        final DynamicCoreRuntimeInfo dynamic = new DynamicCoreRuntimeInfo();
        assertThrows(UnsupportedOperationException.class, dynamic::version);

        dynamic.connect(runtime(5));
        final MocInspector retained = dynamic.mocInspector();
        assertEquals(new CoreVersion(5, 2, 0), dynamic.version());
        assertEquals(MocVersion.V5_0, retained.latestVersion());

        dynamic.connect(runtime(6));
        assertThrows(IllegalStateException.class, retained::latestVersion);
        assertEquals(MocVersion.V5_3, dynamic.mocInspector().latestVersion());

        final MocInspector disconnected = dynamic.mocInspector();
        dynamic.deactivate();
        assertThrows(IllegalStateException.class, disconnected::latestVersion);
        assertThrows(UnsupportedOperationException.class, dynamic::capabilities);
    }

    private static CoreRuntimeInfo runtime(final int mocVersion) {
        return new CoreRuntimeInfo() {
            @Override public CoreVersion version() { return new CoreVersion(5, 2, 0); }
            @Override public CoreCapabilities capabilities() {
                return new CoreCapabilities(false, true, true);
            }
            @Override public MocInspector mocInspector() {
                return new MocInspector() {
                    @Override public MocVersion latestVersion() {
                        return mocVersion == 5 ? MocVersion.V5_0 : MocVersion.V5_3;
                    }
                    @Override public MocInfo inspect(final MocData data) {
                        return new MocInfo(latestVersion(), MocConsistency.CONSISTENT);
                    }
                };
            }
        };
    }
}
