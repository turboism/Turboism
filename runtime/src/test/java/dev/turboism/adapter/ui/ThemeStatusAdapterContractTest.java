package dev.turboism.adapter.ui;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.service.read.CubismReadCapabilityServiceTestSupport;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.service.read.CubismReadCapabilityServiceImpl;
import dev.turboism.adapter.cubism.service.read.M12ReadSnapshotSource;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeStatusAdapterContractTest {

    @Test
    void capabilityIdMatchesCubismThemeStatusReadCatalog() {
        assertEquals("cubism.theme.status.read", ThemeStatusAdapter.CAPABILITY_ID);
    }

    @Test
    void themeStatusReadReturnsAdapterUnavailableWhenSafeModeIsDisconnected() {
        ThemeStatusAdapter adapter = ThemeStatusAdapterImpl.safeMode();

        ThemeStatusAdapter.AdapterResult<Optional<ThemeStatusSnapshot>> result = adapter.themeStatus();

        assertFalse(result.isAvailable());
        assertEquals(SafeModeDiagnostic.Code.ADAPTER_UNAVAILABLE, result.diagnostic().orElseThrow().code());
        assertEquals(ThemeStatusAdapter.CAPABILITY_ID, result.diagnostic().orElseThrow().capability());
    }

    @Test
    void themeStatusReadReturnsCapabilityUnavailableWhenHostOmitsCapability() {
        RecordingHost host = new RecordingHost("5.3.02", false);
        ThemeStatusAdapter adapter = ThemeStatusAdapterImpl.connected(host);

        ThemeStatusAdapter.AdapterResult<Optional<ThemeStatusSnapshot>> result = adapter.themeStatus();

        assertFalse(result.isAvailable());
        assertEquals(SafeModeDiagnostic.Code.CAPABILITY_UNAVAILABLE, result.diagnostic().orElseThrow().code());
        assertEquals(0, host.themeDelegations);
    }

    @Test
    void themeStatusReadDelegatesWhenCapabilityIsAvailable() {
        RecordingHost host = new RecordingHost("5.3.02", true);
        ThemeStatusAdapter adapter = ThemeStatusAdapterImpl.connected(host);

        ThemeStatusAdapter.AdapterResult<Optional<ThemeStatusSnapshot>> result = adapter.themeStatus();

        assertTrue(result.isAvailable());
        assertEquals(Optional.of(new ThemeStatusSnapshot("dark", "Dark", true)), result.value().orElseThrow());
        assertEquals(1, host.themeDelegations);
    }

    @Test
    void unsupportedHostVersionDoesNotDelegate() {
        RecordingHost host = new RecordingHost("5.4.0", true);
        ThemeStatusAdapter adapter = ThemeStatusAdapterImpl.connected(host);

        ThemeStatusAdapter.AdapterResult<Optional<ThemeStatusSnapshot>> result = adapter.themeStatus();

        assertFalse(result.isAvailable());
        assertEquals(SafeModeDiagnostic.Code.HOST_VERSION_UNSUPPORTED, result.diagnostic().orElseThrow().code());
        assertEquals(0, host.themeDelegations);
    }

    @Test
    void cubismReadServiceUsesThemeStatusAdapterAndDoesNotDependOnStatusToolbarAdapter() {
        RecordingHost host = new RecordingHost("5.3.02", true);
        CubismReadCapabilityServiceImpl service = CubismReadCapabilityServiceTestSupport.withThemeAdapter(
            new CubismFacadeImpl(projectOnlySource(), projectReadGate()),
            M12ReadSnapshotSource.EMPTY,
            ThemeStatusAdapterImpl.connected(host),
            projectReadGate()
        );

        Optional<ThemeStatusSnapshot> themeStatus = service.themeStatus();

        assertEquals(Optional.of(new ThemeStatusSnapshot("dark", "Dark", true)), themeStatus);
        assertTrue(service.themeStatusDiagnostics().isEmpty());
        assertEquals(1, host.themeDelegations);
    }

    @Test
    void cubismReadServiceFallsBackToM12SourceWhenThemeAdapterIsUnavailable() {
        CubismReadCapabilityServiceImpl service = CubismReadCapabilityServiceTestSupport.withThemeAdapter(
            new CubismFacadeImpl(projectOnlySource(), projectReadGate()),
            new FixedThemeSource(),
            ThemeStatusAdapterImpl.safeMode(),
            projectReadGate()
        );

        Optional<ThemeStatusSnapshot> themeStatus = service.themeStatus();

        assertEquals(Optional.of(new ThemeStatusSnapshot("aurora", "Aurora", true)), themeStatus);
        assertEquals(SafeModeDiagnostic.Code.ADAPTER_UNAVAILABLE, service.themeStatusDiagnostics().get(0).code());
    }

    private static HostSnapshotSource projectOnlySource() {
        return new TestHostSnapshotSource(
            Optional.of(new HostSnapshotSource.HostProject("project-1", "Project", Optional.empty(), List.of())),
            Optional.empty(),
            Optional.empty(),
            new HostSnapshotSource.HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty()),
            true
        );
    }

    private static CubismPermissionGate projectReadGate() {
        return new CubismPermissionGate(
            "plugin.demo",
            List.of(permission(CubismFacadeImpl.PROJECT_READ_PERMISSION)),
            ignored -> {
            },
            Clock.fixed(Instant.parse("2026-07-09T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String scope() {
                return "read";
            }

            @Override
            public String reason() {
                return "test";
            }
        };
    }

    private record FixedThemeSource() implements M12ReadSnapshotSource {
        @Override
        public Optional<ThemeStatusSnapshot> themeStatus() {
            return Optional.of(new ThemeStatusSnapshot("aurora", "Aurora", true));
        }
    }

    private record TestHostSnapshotSource(
        Optional<HostProject> project,
        Optional<HostDocument> document,
        Optional<HostModel> model,
        HostSelection selection,
        boolean hostPresent
    ) implements HostSnapshotSource {
        @Override
        public Optional<HostProject> activeProject() {
            return project;
        }

        @Override
        public Optional<HostDocument> activeDocument() {
            return document;
        }

        @Override
        public Optional<HostModel> activeModel() {
            return model;
        }

        @Override
        public boolean isHostPresent() {
            return hostPresent;
        }

        @Override
        public long invalidationToken() {
            return 0L;
        }
    }

    private static final class RecordingHost implements ThemeStatusAdapter.HostOperations {
        private final String hostVersion;
        private final boolean supportsThemeStatus;
        private int themeDelegations;

        private RecordingHost(final String hostVersion, final boolean supportsThemeStatus) {
            this.hostVersion = hostVersion;
            this.supportsThemeStatus = supportsThemeStatus;
        }

        @Override
        public String hostVersion() {
            return hostVersion;
        }

        @Override
        public boolean supportsThemeStatusRead() {
            return supportsThemeStatus;
        }

        @Override
        public Optional<ThemeStatusSnapshot> themeStatus() {
            themeDelegations++;
            return Optional.of(new ThemeStatusSnapshot("dark", "Dark", true));
        }
    }
}
