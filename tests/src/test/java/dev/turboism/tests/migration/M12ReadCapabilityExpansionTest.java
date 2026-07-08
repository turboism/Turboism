package dev.turboism.tests.migration;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.service.read.CubismReadCapabilityServiceImpl;
import dev.turboism.adapter.cubism.service.read.M12ReadSnapshotSource;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.PsdDocumentSnapshot;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.TextureAtlasSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static dev.turboism.tests.migration.M12RepresentativeFixtures.facadeFor;
import static dev.turboism.tests.migration.M12RepresentativeFixtures.sampleHost;

class M12ReadCapabilityExpansionTest {

    @Test
    void readCapabilityServiceAggregatesExistingFacadeAndM12SupplementalReadFamilies() {
        CubismFacadeImpl facade = facadeFor(
            sampleHost(),
            CubismFacadeImpl.PROJECT_READ_PERMISSION,
            CubismFacadeImpl.MODEL_READ_PERMISSION
        );
        CubismReadCapabilityService service = new CubismReadCapabilityServiceImpl(facade, new SupplementalReadSource());

        assertEquals("project-1", service.activeProject().orElseThrow().projectId());
        assertEquals("document-1", service.activeDocument().orElseThrow().documentId());
        assertEquals("model-1", service.activeModel().orElseThrow().modelId());
        assertTrue(service.selection().selectedObjectIds().isEmpty());
        assertEquals("parameter-1", service.parameters().get(0).id());
        assertEquals("mesh-1", service.meshes().get(0).id());
        assertEquals("deformer-1", service.deformers().get(0).id());
        assertEquals("psd-1", service.psdDocuments().get(0).documentId());
        assertEquals("mask-1", service.clipMasks().get(0).clipMaskId());
        assertEquals("atlas-1", service.textureAtlases().get(0).atlasId());
        assertEquals(60.0, service.renderStatus().orElseThrow().framesPerSecond());
        assertEquals("workspace-1", service.workspace().orElseThrow().workspaceId());
        assertEquals("dark", service.themeStatus().orElseThrow().themeId());
    }

    @Test
    void supplementalReadFamiliesRemainImmutableSdkDtos() {
        CubismReadCapabilityService service = new CubismReadCapabilityServiceImpl(
            facadeFor(sampleHost(), CubismFacadeImpl.PROJECT_READ_PERMISSION, CubismFacadeImpl.MODEL_READ_PERMISSION),
            new SupplementalReadSource()
        );

        assertThrows(UnsupportedOperationException.class, () -> service.psdDocuments().get(0).layers().add(new PsdDocumentSnapshot.PsdLayerSnapshot("layer-2", "Color", true)));
        assertThrows(UnsupportedOperationException.class, () -> service.clipMasks().get(0).sourceMeshIds().add("mesh-3"));
        assertThrows(UnsupportedOperationException.class, () -> service.textureAtlases().get(0).textureIds().add("texture-2"));
        assertThrows(UnsupportedOperationException.class, () -> service.workspace().orElseThrow().recentProjectIds().add("project-2"));
    }

    @Test
    void m12SupplementalModelReadsUseExistingModelReadGateUntilNarrowPermissionsAreApproved() {
        CubismReadCapabilityService service = new CubismReadCapabilityServiceImpl(
            facadeFor(sampleHost(), CubismFacadeImpl.PROJECT_READ_PERMISSION),
            new SupplementalReadSource()
        );

        assertThrows(CubismPermissionException.class, service::psdDocuments);
        assertThrows(CubismPermissionException.class, service::clipMasks);
        assertThrows(CubismPermissionException.class, service::textureAtlases);
        assertThrows(CubismPermissionException.class, service::renderStatus);
    }

    @Test
    void m12SupplementalWorkspaceAndThemeReadsUseExistingProjectReadGateUntilNarrowPermissionsAreApproved() {
        CubismReadCapabilityService service = new CubismReadCapabilityServiceImpl(
            facadeFor(sampleHost(), CubismFacadeImpl.MODEL_READ_PERMISSION),
            new SupplementalReadSource()
        );

        assertThrows(CubismPermissionException.class, service::workspace);
        assertThrows(CubismPermissionException.class, service::themeStatus);
    }

    private static final class SupplementalReadSource implements M12ReadSnapshotSource {
        @Override
        public List<PsdDocumentSnapshot> psdDocuments() {
            return List.of(new PsdDocumentSnapshot(
                "psd-1",
                "psd/source.psd",
                List.of(new PsdDocumentSnapshot.PsdLayerSnapshot("layer-1", "Line", true))
            ));
        }

        @Override
        public List<ClipMaskSnapshot> clipMasks() {
            return List.of(new ClipMaskSnapshot("mask-1", List.of("mesh-1"), List.of("mesh-2"), true));
        }

        @Override
        public List<TextureAtlasSnapshot> textureAtlases() {
            return List.of(new TextureAtlasSnapshot("atlas-1", 1024, 1024, List.of("texture-1")));
        }

        @Override
        public Optional<RenderStatusSnapshot> renderStatus() {
            return Optional.of(new RenderStatusSnapshot(true, 60.0, "fake-renderer"));
        }

        @Override
        public Optional<WorkspaceSnapshot> workspace() {
            return Optional.of(new WorkspaceSnapshot("workspace-1", "workspace", List.of("project-1")));
        }

        @Override
        public Optional<ThemeStatusSnapshot> themeStatus() {
            return Optional.of(new ThemeStatusSnapshot("dark", "Dark", true));
        }
    }
}
