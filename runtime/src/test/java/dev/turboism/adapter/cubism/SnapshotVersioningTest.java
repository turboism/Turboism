package dev.turboism.adapter.cubism;

import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.adapter.host.HostSessionSnapshotSource;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.hostread.ProjectWorkspaceSnapshot;
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

    @Test
    void oneVersionedReadObservesTheHostProjectAndDocumentExactlyOnce() {
        final CountingWorkspaceAdapter adapter = new CountingWorkspaceAdapter();
        final CubismFacadeImpl facade = facadeWith(
            HostSessionSnapshotSource.forSession(adapter),
            List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION))
        );

        facade.runtimeWithVersion();

        assertEquals(1, adapter.pairReads,
            "one versioned read must observe the project/document pair once");
        assertEquals(0, adapter.projectReads,
            "the paired observation must not re-read the project separately");
        assertEquals(0, adapter.documentReads,
            "the paired observation must not re-read the document separately");
    }

    @Test
    void sourceReadsShareThePairedObservation() {
        final CountingWorkspaceAdapter adapter = new CountingWorkspaceAdapter();
        final HostSnapshotSource source = HostSessionSnapshotSource.forSession(adapter);

        source.observe();
        source.invalidationToken();
        source.isHostPresent();

        assertEquals(3, adapter.pairReads,
            "observe, invalidationToken and isHostPresent must each be one paired read");
        assertEquals(0, adapter.projectReads + adapter.documentReads,
            "paired reads must not fall back to the individual accessors");
    }

    @Test
    void runtimeOverSessionSourceReturnsTheAdapterSnapshotsVerbatim() {
        final CountingWorkspaceAdapter adapter = new CountingWorkspaceAdapter();
        final CubismFacadeImpl facade = facadeWith(
            HostSessionSnapshotSource.forSession(adapter),
            List.of(
                permission(CubismFacadeImpl.MODEL_READ_PERMISSION),
                permission(CubismFacadeImpl.PROJECT_READ_PERMISSION)
            )
        );

        final var snapshot = facade.runtime();

        assertEquals(1, adapter.pairReads);
        assertTrue(snapshot.project().isPresent());
        assertTrue(snapshot.document().isPresent());
        assertEquals("project-1", snapshot.project().orElseThrow().projectId());
        assertEquals("document-1", snapshot.document().orElseThrow().documentId());
        // The session adapter projects no selection and the document carries no model.
        assertTrue(snapshot.selection().selectedObjectIds().isEmpty());
        assertTrue(snapshot.model().isEmpty());
        assertTrue(snapshot.parameters().isEmpty());
    }

    @Test
    void runtimeOverSessionSourceRedactsProjectWithoutProjectRead() {
        final CountingWorkspaceAdapter adapter = new CountingWorkspaceAdapter();
        final CubismFacadeImpl facade = facadeWith(
            HostSessionSnapshotSource.forSession(adapter),
            List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION))
        );

        final var snapshot = facade.runtime();

        assertTrue(snapshot.project().isEmpty(),
            "project-read denial redacts only the project portion");
        assertTrue(snapshot.document().isPresent());
    }

    @Test
    void sessionSourceVersionsTheObservedPairNotAFreshRead() {
        final CountingWorkspaceAdapter adapter = new CountingWorkspaceAdapter();
        final CubismFacadeImpl facade = facadeWith(
            HostSessionSnapshotSource.forSession(adapter),
            List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION))
        );

        final SnapshotWithVersion first = facade.runtimeWithVersion();
        final SnapshotWithVersion second = facade.runtimeWithVersion();

        assertEquals(first.version(), second.version(),
            "an unchanged observed pair must not bump the token");
        assertEquals(2, adapter.pairReads,
            "each versioned read observes once; the version check reuses the carried evidence");
        adapter.swapDocument();
        final SnapshotWithVersion third = facade.runtimeWithVersion();
        assertEquals(first.version() + 1, third.version(),
            "a changed document must bump the token exactly once");
    }

    /** Counts host reads so the validity check cannot cost more than the work it protects. */
    private static final class CountingWorkspaceAdapter implements ProjectWorkspaceAdapter {

        private int projectReads;
        private int documentReads;
        private int pairReads;
        private Optional<DocumentSnapshot> document = Optional.of(documentSnapshot("document-1"));

        private void swapDocument() {
            document = Optional.of(documentSnapshot("document-2"));
        }

        @Override
        public AdapterResult<Optional<ProjectSnapshot>> activeProject() {
            projectReads++;
            return AdapterResult.available(Optional.of(projectSnapshot()));
        }

        @Override
        public AdapterResult<Optional<DocumentSnapshot>> activeDocument() {
            documentReads++;
            return AdapterResult.available(document);
        }

        @Override
        public AdapterResult<ActiveProjectDocument> activeProjectAndDocument() {
            pairReads++;
            return AdapterResult.available(new ActiveProjectDocument(
                Optional.of(projectSnapshot()),
                document
            ));
        }

        private static ProjectSnapshot projectSnapshot() {
            return new ProjectSnapshot(
                "project-1",
                "Project",
                Optional.empty(),
                List.of(),
                List.of()
            );
        }

        private static DocumentSnapshot documentSnapshot(final String documentId) {
            return new DocumentSnapshot(
                documentId,
                "Model",
                "model/model.cmo3",
                Optional.empty(),
                Optional.empty(),
                DocumentKind.MODEL,
                Optional.empty(),
                Optional.empty()
            );
        }

        @Override
        public AdapterResult<Optional<WorkspaceSnapshot>> workspace() {
            return AdapterResult.available(Optional.empty());
        }

        @Override
        public AdapterResult<ProjectWorkspaceSnapshot> projectWorkspaceSnapshot() {
            return AdapterResult.available(
                new ProjectWorkspaceSnapshot(Optional.empty(), Optional.empty())
            );
        }
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
