package dev.turboism.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HookManifestTest {

    @Test
    void packagedManifestLoadsEveryDeclaredContributor() throws Exception {
        final List<HookContributor> contributors =
            HookManifest.load(HookManifestTest.class.getClassLoader());
        assertFalse(contributors.isEmpty());
        assertEquals(
            List.of(
                MeshMirrorHookContributor.class,
                MeshTriangulationHashHookContributor.class,
                AtlasTileBboxHookContributor.class,
                AtlasCacheReuseHookContributor.class,
                FpsHookContributor.class,
                ParameterHookContributor.class,
                ProjectLifecycleHookContributor.class,
                FileChooserHistoryHookContributor.class,
                ImageArchiveReuseHookContributor.class,
                FloatArrayParseCacheHookContributor.class,
                TextureUploadPreparationHookContributor.class,
                WarpPositionProjectionHookContributor.class,
                ModelUpdateSkipHookContributor.class,
                IncrementalUpdateHookContributor.class,
                UniformLocationCacheHookContributor.class,
                MatrixScratchHookContributor.class,
                NativeEditBeginHookContributor.class,
                TextureAtlasDataModelHookContributor.class,
                TextureAtlasAutoLayoutHookContributor.class,
                PerformanceProbeHookContributor.class,
                DockTabPopupHookContributor.class,
                FloatingFrameDisposeHookContributor.class,
                FloatingTabCloseHookContributor.class,
                ObjectContextMenuHookContributor.class,
                PhysicsEditorHookContributor.class,
                BoundingBoxOverlayHookContributor.class,
                ControlAppearanceHookContributor.class
            ),
            contributors.stream().map(HookContributor::getClass).toList()
        );
    }

    @Test
    void packagedManifestCoversEveryHookPhase() throws Exception {
        final List<HookContributor> contributors =
            HookManifest.load(HookManifestTest.class.getClassLoader());
        for (HookContributor.Phase phase : HookContributor.Phase.values()) {
            assertTrue(
                contributors.stream().anyMatch(contributor -> contributor.phase() == phase),
                "manifest declares no " + phase + " contributor"
            );
        }
    }

    @Test
    void missingManifestFailsClosed() {
        assertThrows(
            HookManifest.HookManifestException.class,
            () -> HookManifest.load(new ClassLoader(null) {
                @Override
                public java.io.InputStream getResourceAsStream(final String name) {
                    return null;
                }
            })
        );
    }

    @Test
    void manifestEntriesMustImplementHookContributor() {
        final HookManifest.HookManifestException failure = assertThrows(
            HookManifest.HookManifestException.class,
            () -> HookManifest.load(new ClassLoader(null) {
                @Override
                public java.io.InputStream getResourceAsStream(final String name) {
                    return new java.io.ByteArrayInputStream(
                        "java.lang.String\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    );
                }
            })
        );
        assertTrue(failure.getMessage().contains("HookContributor"));
    }
}
