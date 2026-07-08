package dev.turboism.adapter.cubism;

import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotVersioningTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void runtimeWithVersionReturnsSnapshotAndCurrentInvalidationToken() {
        final VersionedSource source = new VersionedSource();
        final CubismFacadeImpl facade = facadeWith(source, List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)));

        final SnapshotWithVersion first = facade.runtimeWithVersion();
        source.advanceInvalidationToken();
        final SnapshotWithVersion second = facade.runtimeWithVersion();

        assertEquals(0L, first.version());
        assertEquals(1L, second.version());
        assertEquals("model-1", second.snapshot().model().orElseThrow().modelId());
    }

    @Test
    void runtimeWithVersionKeepsSnapshotPermissionGate() {
        final VersionedSource source = new VersionedSource();
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismFacadeImpl facade = facadeWith(source, auditEvents, List.of());

        final CubismPermissionException error = assertThrows(
            CubismPermissionException.class,
            facade::runtimeWithVersion
        );

        assertEquals(0L, source.invalidationTokenReadCount());
        assertEquals(1, auditEvents.size());
        assertEquals(CubismFacadeImpl.MODEL_READ_PERMISSION, auditEvents.get(0).permissionId());
        assertEquals("runtime", auditEvents.get(0).methodName());
        assertEquals(FIXED_CLOCK.instant(), auditEvents.get(0).timestamp());
        assertTrue(error.getMessage().contains(CubismFacadeImpl.MODEL_READ_PERMISSION));
    }

    private static CubismFacadeImpl facadeWith(
        final HostSnapshotSource source,
        final List<PluginPermission> permissions
    ) {
        return facadeWith(source, new ArrayList<>(), permissions);
    }

    private static CubismFacadeImpl facadeWith(
        final HostSnapshotSource source,
        final List<CubismFacadeAuditEvent> auditEvents,
        final List<PluginPermission> permissions
    ) {
        return new CubismFacadeImpl(source, new CubismPermissionGate(
            "plugin.demo",
            permissions,
            auditEvents::add,
            FIXED_CLOCK
        ));
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

    private static final class VersionedSource implements HostSnapshotSource {

        private static final HostModel MODEL = new HostModel(
            "model-1",
            "Model",
            List.of(),
            List.of(),
            List.of()
        );

        private long invalidationToken;
        private long invalidationTokenReadCount;

        void advanceInvalidationToken() {
            invalidationToken++;
        }

        long invalidationTokenReadCount() {
            return invalidationTokenReadCount;
        }

        @Override
        public Optional<HostProject> activeProject() {
            return Optional.empty();
        }

        @Override
        public Optional<HostDocument> activeDocument() {
            return Optional.empty();
        }

        @Override
        public Optional<HostModel> activeModel() {
            return Optional.of(MODEL);
        }

        @Override
        public HostSelection selection() {
            return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        @Override
        public boolean isHostPresent() {
            return true;
        }

        @Override
        public long invalidationToken() {
            invalidationTokenReadCount++;
            return invalidationToken;
        }
    }
}
