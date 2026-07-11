package dev.turboism.tests.migration;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.service.read.CubismReadCapabilityServiceImpl;
import dev.turboism.adapter.cubism.service.read.M12ReadSnapshotSource;
import dev.turboism.core.plugin.context.CorePluginContext;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.PsdDocumentSnapshot;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.TextureAtlasSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.ui.UiHostStateSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static dev.turboism.tests.migration.M12RepresentativeFixtures.CLOCK;
import static dev.turboism.tests.migration.M12RepresentativeFixtures.facadeFor;
import static dev.turboism.tests.migration.M12RepresentativeFixtures.hostSource;
import static dev.turboism.tests.migration.M12RepresentativeFixtures.permission;
import static dev.turboism.tests.migration.M12RepresentativeFixtures.sampleHost;
import static dev.turboism.tests.migration.M12RepresentativeFixtures.scheduler;

class M12ReadCapabilityExpansionTest {

    @TempDir
    Path tempDir;

    @Test
    void readCapabilityServiceAggregatesExistingFacadeAndM12SupplementalReadFamilies() {
        CubismFacadeImpl facade = facadeFor(
            sampleHost(),
            CubismFacadeImpl.PROJECT_READ_PERMISSION,
            CubismFacadeImpl.MODEL_READ_PERMISSION
        );
        CubismReadCapabilityService service = new CubismReadCapabilityServiceImpl(facade, new SupplementalReadSource());

        assertAllReadFamilies(service);
    }

    @Test
    void corePluginContextExposesM12SupplementalReadsThroughNormalRuntimeWiring() {
        CorePluginContext context = new CorePluginContext(new CorePluginContext.Dependencies(
            descriptorWithReadPermissions(),
            logger(),
            paths(tempDir),
            uiScheduler(),
            scheduler(new M12RepresentativeFixtures.RecordingPolicy()),
            diagnostics(),
            new DisposableScope(),
            hostSource(sampleHost()),
            new SupplementalReadSource(),
            UiHostStateSource.DEFAULT,
            event -> { },
            CLOCK
        ));

        assertAllReadFamilies(context.cubismRead());
    }

    @Test
    void supplementalReadFamiliesRemainImmutableSdkDtos() {
        CubismReadCapabilityService service = new CubismReadCapabilityServiceImpl(
            facadeFor(sampleHost(), CubismFacadeImpl.PROJECT_READ_PERMISSION, CubismFacadeImpl.MODEL_READ_PERMISSION),
            new SupplementalReadSource()
        );

        assertThrows(UnsupportedOperationException.class, () -> service.psdDocuments().get(0).layers().add(new PsdDocumentSnapshot.PsdLayerSnapshot("layer-2", "Color", true)));
        assertThrows(UnsupportedOperationException.class, () -> service.clipMasks().get(0).orderedMaskSourceIds().add("mesh-3"));
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

    private static void assertAllReadFamilies(CubismReadCapabilityService service) {
        assertEquals("project-1", service.activeProject().orElseThrow().projectId());
        assertEquals("document-1", service.activeDocument().orElseThrow().documentId());
        assertEquals("model-1", service.activeModel().orElseThrow().modelId());
        assertTrue(service.selection().selectedObjectIds().isEmpty());
        assertEquals("parameter-1", service.parameters().get(0).id());
        assertEquals("mesh-1", service.meshes().get(0).id());
        assertEquals("deformer-1", service.deformers().get(0).id());
        assertEquals("psd-1", service.psdDocuments().get(0).documentId());
        assertEquals("mesh-2", service.clipMasks().get(0).targetMeshId());
        assertEquals("atlas-1", service.textureAtlases().get(0).atlasId());
        assertEquals(60.0, service.renderStatus().orElseThrow().framesPerSecond());
        assertEquals("workspace-1", service.workspace().orElseThrow().workspaceId());
        assertEquals("dark", service.themeStatus().orElseThrow().themeId());
    }

    private static PluginDescriptor descriptorWithReadPermissions() {
        return new PluginDescriptor() {
            @Override public String id() { return "dev.turboism.plugin.m12-read-test"; }
            @Override public String name() { return "M12 Read Test"; }
            @Override public String version() { return "0.1.0"; }
            @Override public String description() { return "M12 read test"; }
            @Override public Map<String, String> entrypoints() { return Map.of("plugin", "dev.turboism.test.ReadPlugin"); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Project License"; }
            @Override public Optional<String> homepage() { return Optional.empty(); }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() {
                return List.of(
                    permissionRef(CubismFacadeImpl.PROJECT_READ_PERMISSION),
                    permissionRef(CubismFacadeImpl.MODEL_READ_PERMISSION)
                );
            }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() {
                return new Environment() {
                    @Override public boolean requiresCubism() { return false; }
                    @Override public String ui() { return "none"; }
                };
            }
        };
    }

    private static PluginDescriptor.PermissionRef permissionRef(String id) {
        return new PluginDescriptor.PermissionRef() {
            @Override public String id() { return id; }
            @Override public String scope() { return "application"; }
            @Override public Optional<String> reason() { return Optional.of("M12 read test"); }
        };
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void debug(String message) { }
            @Override public void info(String message) { }
            @Override public void warn(String message) { }
            @Override public void error(String message) { }
            @Override public void error(String message, Throwable throwable) { }
        };
    }

    private static PluginPaths paths(Path dataDir) {
        return new PluginPaths() {
            @Override public Path dataDir() { return dataDir; }
            @Override public Path logsDir() { return dataDir.resolve("logs"); }
            @Override public Path stateDir() { return dataDir.resolve("state"); }
            @Override public Path cacheDir() { return dataDir.resolve("cache"); }
        };
    }

    private static UiScheduler uiScheduler() {
        return new UiScheduler() {
            @Override public dev.turboism.sdk.plugin.Registration runOnUiThread(Runnable work) { work.run(); return () -> { }; }
            @Override public dev.turboism.sdk.plugin.Registration runOnUiThreadLater(Runnable work, java.time.Duration delay) { return () -> { }; }
        };
    }

    private static DiagnosticReport diagnostics() {
        return new DiagnosticReport() {
            @Override public Instant createdAt() { return CLOCK.instant(); }
            @Override public List<Problem> problems() { return List.of(); }
        };
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
            return List.of(new ClipMaskSnapshot("mesh-2", List.of("mesh-1"), true));
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
