package dev.turboism.adapter.host;

import dev.turboism.adapter.cubism.core.OwnedModelParameterWriter;
import dev.turboism.sdk.cubism.core.CoreCapabilities;
import dev.turboism.sdk.cubism.core.CoreRuntimeInfo;
import dev.turboism.sdk.cubism.core.CoreVersion;
import dev.turboism.sdk.cubism.core.MocConsistency;
import dev.turboism.sdk.cubism.core.MocData;
import dev.turboism.sdk.cubism.core.MocInfo;
import dev.turboism.sdk.cubism.core.MocInspector;
import dev.turboism.sdk.cubism.core.MocLoader;
import dev.turboism.sdk.cubism.core.MocVersion;
import dev.turboism.sdk.cubism.core.OwnedMoc;
import dev.turboism.sdk.cubism.core.OwnedModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    @Test
    void mocLoaderExposesOwnedParameterWriterSeam() {
        final DynamicCoreRuntimeInfo dynamic = new DynamicCoreRuntimeInfo();
        dynamic.connect(runtimeWithWriter());

        final MocLoader loader = dynamic.mocLoader();
        assertInstanceOf(OwnedModelParameterWriter.class, loader);

        ((OwnedModelParameterWriter) loader).writeParameterValue(null, "ParamAngleX", 12.5f);
        assertEquals("ParamAngleX", WRITTEN_ID.get());
        assertEquals(12.5f, WRITTEN_VALUE.get());
    }

    @Test
    void guardedWriterRejectsStaleGenerationAndUnsupportedDelegates() {
        final DynamicCoreRuntimeInfo dynamic = new DynamicCoreRuntimeInfo();
        dynamic.connect(runtimeWithWriter());
        final MocLoader loader = dynamic.mocLoader();

        dynamic.connect(runtime(6));
        assertThrows(IllegalStateException.class, () -> loader.load(MocData.copyOf(new byte[] {1})));
        assertThrows(
            IllegalStateException.class,
            () -> ((OwnedModelParameterWriter) loader)
                .writeParameterValue(null, "ParamAngleX", 0f)
        );

        dynamic.connect(runtimeWithPlainLoader());
        final MocLoader plainLoader = dynamic.mocLoader();
        assertInstanceOf(OwnedModelParameterWriter.class, plainLoader);
        assertThrows(
            IllegalStateException.class,
            () -> ((OwnedModelParameterWriter) plainLoader)
                .writeParameterValue(null, "ParamAngleX", 0f)
        );
    }

    private static final java.util.concurrent.atomic.AtomicReference<String> WRITTEN_ID =
        new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicReference<Float> WRITTEN_VALUE =
        new java.util.concurrent.atomic.AtomicReference<>();

    private static CoreRuntimeInfo runtimeWithWriter() {
        final CoreRuntimeInfo base = runtime(6);
        return new CoreRuntimeInfo() {
            @Override public CoreVersion version() { return base.version(); }
            @Override public CoreCapabilities capabilities() { return base.capabilities(); }
            @Override public MocInspector mocInspector() { return base.mocInspector(); }
            @Override public MocLoader mocLoader() { return new LoaderWithWriter(); }
        };
    }

    private static CoreRuntimeInfo runtimeWithPlainLoader() {
        final CoreRuntimeInfo base = runtime(6);
        final MocLoader plain = data -> {
            throw new UnsupportedOperationException();
        };
        return new CoreRuntimeInfo() {
            @Override public CoreVersion version() { return base.version(); }
            @Override public CoreCapabilities capabilities() { return base.capabilities(); }
            @Override public MocInspector mocInspector() { return base.mocInspector(); }
            @Override public MocLoader mocLoader() { return plain; }
        };
    }

    private static final class LoaderWithWriter
        implements MocLoader, OwnedModelParameterWriter {
        @Override public OwnedMoc load(final MocData data) {
            throw new UnsupportedOperationException();
        }

        @Override public void writeParameterValue(
            final OwnedModel model,
            final String parameterId,
            final float value
        ) {
            WRITTEN_ID.set(parameterId);
            WRITTEN_VALUE.set(value);
        }
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
