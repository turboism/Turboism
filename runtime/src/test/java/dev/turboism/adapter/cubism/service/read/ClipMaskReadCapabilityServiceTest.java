package dev.turboism.adapter.cubism.service.read;

import dev.turboism.adapter.cubism.ClipMaskReadAdapter;
import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.cubism.RenderStatusAdapter;
import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.adapter.ui.ThemeStatusAdapterImpl;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipMaskReadCapabilityServiceTest {

    @Test
    void availableEmptyDirectAdapterDoesNotFallBack() {
        AtomicInteger fallbackReads = new AtomicInteger();
        CubismReadCapabilityServiceImpl service = service(
            ClipMaskReadAdapter.Impl.connected(new FixedClipHost(List.of())),
            fallback(fallbackReads)
        );

        assertEquals(List.of(), service.clipMasks());
        assertEquals(0, fallbackReads.get());
        assertTrue(service.clipMaskDiagnostics().isEmpty());
    }

    @Test
    void unavailableDirectAdapterRecordsDiagnosticAndFallsBack() {
        AtomicInteger fallbackReads = new AtomicInteger();
        CubismReadCapabilityServiceImpl service = service(
            ClipMaskReadAdapter.Impl.safeMode(),
            fallback(fallbackReads)
        );

        assertEquals("fallback-target", service.clipMasks().get(0).targetMeshId());
        assertEquals(1, fallbackReads.get());
        assertEquals(
            SafeModeDiagnostic.Code.ADAPTER_UNAVAILABLE,
            service.clipMaskDiagnostics().get(0).code()
        );
    }

    @Test
    void deniedClipMaskReadAuditsRealIdsBeforeTouchingAdapterOrFallback() {
        final AtomicInteger adapterReads = new AtomicInteger();
        final AtomicInteger fallbackReads = new AtomicInteger();
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismPermissionGate gate = gate(List.of(), auditEvents);
        final CubismReadCapabilityServiceImpl service = new CubismReadCapabilityServiceImpl(
            new CubismFacadeImpl(emptySource(), gate),
            fallback(fallbackReads),
            ThemeStatusAdapterImpl.safeMode(),
            RenderStatusAdapter.Impl.safeMode(),
            ProjectWorkspaceAdapter.Impl.safeMode(),
            ClipMaskReadAdapter.Impl.connected(new ClipMaskReadAdapter.HostOperations() {
                @Override public String hostVersion() { return "5.3.02"; }
                @Override public boolean supportsClipMaskRead() { return true; }
                @Override public List<ClipMaskSnapshot> clipMasks() {
                    adapterReads.incrementAndGet();
                    return List.of();
                }
            }),
            "plugin.clipmask.test",
            gate
        );

        assertThrows(CubismPermissionException.class, service::clipMasks);

        assertEquals(0, adapterReads.get());
        assertEquals(0, fallbackReads.get());
        final CubismFacadeAuditEvent event = auditEvents.get(0);
        assertEquals(CubismFacadeImpl.MODEL_READ_PERMISSION, event.permissionId());
        assertEquals("cubismRead.clipMasks", event.operationId());
        assertEquals("cubism.clipmask.read", event.capabilityId());
    }

    private static CubismReadCapabilityServiceImpl service(
        final ClipMaskReadAdapter adapter,
        final M12ReadSnapshotSource fallback
    ) {
        final CubismPermissionGate gate = modelReadGate();
        return new CubismReadCapabilityServiceImpl(
            new CubismFacadeImpl(emptySource(), gate),
            fallback,
            ThemeStatusAdapterImpl.safeMode(),
            RenderStatusAdapter.Impl.safeMode(),
            ProjectWorkspaceAdapter.Impl.safeMode(),
            adapter,
            "plugin.clipmask.test",
            gate
        );
    }

    private static HostSnapshotSource emptySource() {
        return new HostSnapshotSource() {
            @Override public Optional<HostProject> activeProject() { return Optional.empty(); }
            @Override public Optional<HostDocument> activeDocument() { return Optional.empty(); }
            @Override public Optional<HostModel> activeModel() { return Optional.empty(); }
            @Override public HostSelection selection() {
                return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
            }
            @Override public boolean isHostPresent() { return false; }
            @Override public long invalidationToken() { return 0L; }
        };
    }

    private static M12ReadSnapshotSource fallback(final AtomicInteger reads) {
        return new M12ReadSnapshotSource() {
            @Override
            public List<ClipMaskSnapshot> clipMasks() {
                reads.incrementAndGet();
                return List.of(new ClipMaskSnapshot("fallback-target", List.of("fallback-source"), false));
            }
        };
    }

    private static CubismPermissionGate modelReadGate() {
        return gate(List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)), new ArrayList<>());
    }

    private static CubismPermissionGate gate(
        final List<PluginPermission> permissions,
        final List<CubismFacadeAuditEvent> auditEvents
    ) {
        return new CubismPermissionGate(
            "plugin.clipmask.test",
            permissions,
            auditEvents::add,
            Clock.fixed(Instant.parse("2026-07-11T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override public String id() { return id; }
            @Override public String scope() { return "read"; }
            @Override public String reason() { return "test"; }
        };
    }

    private record FixedClipHost(List<ClipMaskSnapshot> snapshots)
        implements ClipMaskReadAdapter.HostOperations {
        @Override public String hostVersion() { return "5.3.02"; }
        @Override public boolean supportsClipMaskRead() { return true; }
        @Override public List<ClipMaskSnapshot> clipMasks() { return snapshots; }
    }
}
