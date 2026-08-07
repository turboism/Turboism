package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.ui.StatusToolbarAdapter;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectFileOperation;
import dev.turboism.sdk.cubism.ProjectFileOperationType;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.appearance.control.RuntimeModelAppearanceAccess;
import dev.turboism.adapter.cubism.NativeLabelColorAuthoring;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.workspace.WorkspaceId;
import dev.turboism.sdk.ui.workspace.WorkspaceOperationResult;
import dev.turboism.sdk.ui.workspace.WorkspaceStatus;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.workspace.WorkspaceHostProvider;
import org.junit.jupiter.api.Test;
import dev.turboism.sdk.ui.appearance.PaletteEntry;

import java.io.File;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class HostSessionTest {

    @Test
    void adapterAccessReturnsEmptyWhenActiveConnectionHasNoOptionalOverlayResolver() {
        HostSession session = new HostSession(
            () -> Optional.of(descriptor("session-a")),
            ignored -> new HostAdapterConnection() {
                @Override public RuntimeHostAdapters adapters() { return HostSessionTest.adapters("session-a"); }
                @Override public void close() { }
            }
        );

        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertTrue(session.adapterAccess().boundingBoxOverlayResolver().isEmpty());
    }

    @Test
    void adapterAccessViewsShareHostOwnedModelAppearanceSource() {
        final HostSession session = new HostSession(() -> Optional.empty());

        final RuntimeHostAdapterAccess firstView = session.adapterAccess();
        final RuntimeHostAdapterAccess secondView = session.adapterAccess();

        assertSame(session.modelAppearanceSource(), firstView.modelAppearanceSource());
        assertSame(firstView.modelAppearanceSource(), secondView.modelAppearanceSource());
        session.close();
    }

    @Test
    void projectCloseListenerRemovesOnlySuccessfulContentAndInvalidateStillClearsAll() {
        final HostSession session = new HostSession(() -> Optional.empty());
        final String[] contentId = {"content-a"};
        final String[] modelId = {"model-a"};
        final long[] token = {1L};
        final HostSnapshotSource source = new HostSnapshotSource() {
            @Override public Optional<HostProject> activeProject() { return Optional.empty(); }
            @Override public Optional<HostDocument> activeDocument() {
                return Optional.of(new HostDocument(
                    "document-" + contentId[0], "Model", DocumentKind.MODEL,
                    "models/" + contentId[0] + ".cmo3", Optional.empty(), Optional.of(contentId[0]),
                    Optional.of(new HostModel(modelId[0], "Model", List.of(), List.of(), List.of())),
                    Optional.empty()
                ));
            }
            @Override public Optional<HostModel> activeModel() {
                return Optional.of(new HostModel(modelId[0], "Model", List.of(), List.of(), List.of()));
            }
            @Override public HostSelection selection() {
                return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
            }
            @Override public boolean isHostPresent() { return true; }
            @Override public long invalidationToken() { return token[0]; }
        };
        final RuntimeModelAppearanceAccess accessA = RuntimeModelAppearanceAccess.create(
            "plugin-a", 1L, PermissionChecker.allowAll(), source,
            session.paletteAppearanceCoordinator(), () -> 0L, () -> 0L, () -> 0L,
            NativeLabelColorAuthoring.unavailable()
        );
        final PaletteEntry aEntry = accessA.part("model-a", "PartA", 0L)
            .partPaletteEntry().orElseThrow();
        aEntry.overrideBold(true);

        contentId[0] = "content-b";
        modelId[0] = "model-b";
        token[0] = 2L;
        final RuntimeModelAppearanceAccess accessB = RuntimeModelAppearanceAccess.create(
            "plugin-b", 1L, PermissionChecker.allowAll(), source,
            session.paletteAppearanceCoordinator(), () -> 0L, () -> 0L, () -> 0L,
            NativeLabelColorAuthoring.unavailable()
        );
        final PaletteEntry bEntry = accessB.part("model-b", "PartA", 0L)
            .partPaletteEntry().orElseThrow();
        bEntry.overrideBold(false);

        final ProjectFileOperation closeA = new ProjectFileOperation(
            ProjectContentKind.MODEL, ProjectFileOperationType.CLOSE,
            Optional.of("content-a"), "Model A", Optional.empty()
        );
        final ProjectContentSnapshot contentA = new ProjectContentSnapshot(
            "content-a", "Model A", ProjectContentKind.MODEL, Optional.empty(), List.of(), List.of()
        );
        session.projectFileLifecycle().complete(
            session.projectFileLifecycle().begin(closeA), contentA, true, null
        );
        session.projectFileLifecycle().awaitIdle();
        assertEquals(Optional.of(false), bEntry.resolved().bold());

        contentId[0] = "content-a";
        modelId[0] = "model-a";
        token[0] = 3L;
        final RuntimeModelAppearanceAccess afterCloseAccess = RuntimeModelAppearanceAccess.create(
            "plugin-closed", 1L, PermissionChecker.allowAll(), source,
            session.paletteAppearanceCoordinator(), () -> 0L, () -> 0L, () -> 0L,
            NativeLabelColorAuthoring.unavailable()
        );
        final PaletteEntry afterCloseEntry = afterCloseAccess.part("model-a", "PartA", 0L)
            .partPaletteEntry().orElseThrow();
        assertTrue(afterCloseEntry.resolved().bold().isEmpty());
        final RuntimeModelAppearanceAccess rejectedAccess = RuntimeModelAppearanceAccess.create(
            "plugin-c", 1L, PermissionChecker.allowAll(), source,
            session.paletteAppearanceCoordinator(), () -> 0L, () -> 0L, () -> 0L,
            NativeLabelColorAuthoring.unavailable()
        );
        final PaletteEntry rejectedEntry = rejectedAccess.part("model-a", "PartA", 0L)
            .partPaletteEntry().orElseThrow();
        rejectedEntry.overrideBold(true);
        final ProjectFileOperation closeRejected = new ProjectFileOperation(
            ProjectContentKind.MODEL, ProjectFileOperationType.CLOSE,
            Optional.of("content-a"), "Model A", Optional.empty()
        );
        session.projectFileLifecycle().complete(
            session.projectFileLifecycle().begin(closeRejected), contentA, false, null
        );
        session.projectFileLifecycle().complete(
            session.projectFileLifecycle().begin(closeRejected), contentA, false,
            new IllegalStateException("native close failed")
        );
        assertEquals(Optional.of(true), rejectedEntry.resolved().bold());

        assertEquals(HostSession.State.SAFE_MODE, session.refresh());
        assertTrue(rejectedEntry.resolved().bold().isEmpty());
        session.close();
    }

    @Test
    void dynamicAdaptersFollowConnectDisconnectAndCloseWithoutLeakingOldDelegate() {
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>();
        HostInstanceSource source = () -> Optional.ofNullable(current.get());
        HostSession session = new HostSession(
            source,
            descriptor -> HostAdapterConnection.of(adapters(descriptor.sessionId()))
        );
        RuntimeHostAdapters dynamic = session.adapters();

        assertEquals(HostSession.State.SAFE_MODE, session.state());
        assertFalse(dynamic.projectWorkspace().activeProject().isAvailable());

        current.set(descriptor("session-a"));
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        final long editorUiGeneration = session.editorUiLifecycle().snapshot().generation();
        assertTrue(editorUiGeneration > 0);
        assertEquals(editorUiGeneration, session.paletteAppearanceCoordinator().hostGeneration());
        assertEquals("session-a", dynamic.projectWorkspace().activeProject()
            .value().orElseThrow().orElseThrow().projectId());
        session.meshMirrorAxisService().setCurrentAngleDegrees(45.0f);

        current.set(descriptor("session-b"));
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals("session-b", dynamic.projectWorkspace().activeProject()
            .value().orElseThrow().orElseThrow().projectId());
        assertEquals(0.0f, session.meshMirrorAxisService().currentAngleDegrees());
        session.meshMirrorAxisService().setCurrentAngleDegrees(30.0f);

        current.set(null);
        assertEquals(HostSession.State.SAFE_MODE, session.refresh());
        assertEquals(0L, session.paletteAppearanceCoordinator().hostGeneration());
        assertFalse(dynamic.projectWorkspace().activeProject().isAvailable());
        assertEquals(0.0f, session.meshMirrorAxisService().currentAngleDegrees());

        session.close();
        session.close();
        assertEquals(HostSession.State.CLOSED, session.state());
        assertFalse(dynamic.projectWorkspace().activeProject().isAvailable());
        assertSame(dynamic, session.adapters());
    }

    @Test
    void sameSessionIdReconnectsWhenVerifiedConnectionMaterialChanges() {
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>(
            descriptor("session-a", "host-a.jar", HostSessionTest.class.getClassLoader())
        );
        AtomicInteger connections = new AtomicInteger();
        HostSession session = new HostSession(
            () -> Optional.of(current.get()),
            descriptor -> {
                connections.incrementAndGet();
                return HostAdapterConnection.of(
                    adapters(descriptor.verificationEvidence().projectWorkspace()
                        .verifiedArtifact().getFileName().toString())
                );
            }
        );

        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals("host-a.jar", session.adapters().projectWorkspace().activeProject()
            .value().orElseThrow().orElseThrow().projectId());

        current.set(descriptor("session-a", "host-b.jar", new ClassLoader() { }));
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(2, connections.get());
        assertEquals("host-b.jar", session.adapters().projectWorkspace().activeProject()
            .value().orElseThrow().orElseThrow().projectId());

        session.refresh();
        assertEquals(2, connections.get());
    }

    @Test
    void sameSessionReconnectsForClipEvidencePresenceAndRecordChanges() {
        ClassLoader hostClassLoader = HostSessionTest.class.getClassLoader();
        Path artifact = Path.of("host/Live2D_Cubism.jar");
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>(descriptor(
            "session-a", projectOnlyEvidence("project-record-a.json", artifact, hostClassLoader)
        ));
        AtomicInteger connections = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        HostSession session = new HostSession(
            () -> Optional.of(current.get()),
            ignored -> {
                int marker = connections.incrementAndGet();
                return connection(adapters("connection-" + marker), closes);
            }
        );

        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals("connection-1", activeProjectId(session));

        current.set(descriptor(
            "session-a", projectOnlyEvidence("project-record-a.json", artifact, hostClassLoader)
        ));
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(1, connections.get(), "unchanged project-only material must retain the connection");
        assertEquals(0, closes.get());

        current.set(descriptor(
            "session-a",
            clipEvidence("project-record-a.json", "clip-record-a.json", artifact, hostClassLoader)
        ));
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(2, connections.get());
        assertEquals(1, closes.get(), "adding clip evidence must close the old connection once");
        assertEquals("connection-2", activeProjectId(session));

        current.set(descriptor(
            "session-a",
            clipEvidence("project-record-a.json", "clip-record-b.json", artifact, hostClassLoader)
        ));
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(3, connections.get());
        assertEquals(2, closes.get(), "changing the clip record must replace the complete connection");
        assertEquals("connection-3", activeProjectId(session));

        current.set(descriptor(
            "session-a", projectOnlyEvidence("project-record-a.json", artifact, hostClassLoader)
        ));
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(4, connections.get());
        assertEquals(3, closes.get(), "removing clip evidence must close the old connection once");
        assertEquals("connection-4", activeProjectId(session));

        current.set(descriptor(
            "session-a", projectOnlyEvidence("project-record-b.json", artifact, hostClassLoader)
        ));
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(5, connections.get());
        assertEquals(4, closes.get(), "changing the reviewed record must reconnect");
        assertEquals("connection-5", activeProjectId(session));

        session.close();
        assertEquals(5, closes.get(), "the final active connection must close exactly once");
    }
    @Test
    void sameSessionReconnectsForStatusEvidencePresenceAndRecordChanges() {
        ClassLoader hostClassLoader = HostSessionTest.class.getClassLoader();
        Path artifact = Path.of("host/Live2D_Cubism.jar");
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>(descriptor(
            "session-a", clipEvidence("project-record.json", "clip-record.json", artifact, hostClassLoader)
        ));
        AtomicInteger connections = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        HostSession session = new HostSession(
            () -> Optional.of(current.get()),
            ignored -> {
                int marker = connections.incrementAndGet();
                return connection(adapters("connection-" + marker), closes);
            }
        );

        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(1, connections.get());

        current.set(descriptor(
            "session-a",
            clipEvidence("project-record.json", "clip-record.json", artifact, hostClassLoader)
                .addingStatusBar(statusBarEvidence("status-record-a.json", artifact, hostClassLoader))
        ));
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(2, connections.get(), "adding status evidence must replace the connection");
        assertEquals(1, closes.get());

        current.set(descriptor(
            "session-a",
            clipEvidence("project-record.json", "clip-record.json", artifact, hostClassLoader)
                .addingStatusBar(statusBarEvidence("status-record-b.json", artifact, hostClassLoader))
        ));
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(3, connections.get(), "changing the status record must reconnect");
        assertEquals(2, closes.get());

        current.set(descriptor(
            "session-a", clipEvidence("project-record.json", "clip-record.json", artifact, hostClassLoader)
        ));
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(4, connections.get(), "removing status evidence must reconnect");
        assertEquals(3, closes.get());

        session.close();
        assertEquals(4, closes.get(), "the final active connection must close exactly once");
    }

    private static HostVerificationEvidence.Slice statusBarEvidence(
        final String statusRecord,
        final Path artifact,
        final ClassLoader classLoader
    ) {
        return new HostVerificationEvidence.Slice(
            Path.of("records").resolve(statusRecord), artifact, classLoader
        );
    }

    @Test
    void sameSessionReconnectsForWorkspaceOverlayAndPanelSlicePresenceAndRecordChanges() {
        ClassLoader hostClassLoader = HostSessionTest.class.getClassLoader();
        Path artifact = Path.of("host/Live2D_Cubism.jar");
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>(descriptor(
            "session-a", projectOnlyEvidence("project-record-a.json", artifact, hostClassLoader)
        ));
        AtomicInteger connections = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        HostSession session = new HostSession(
            () -> Optional.of(current.get()),
            ignored -> {
                connections.incrementAndGet();
                return connection(adapters("connection-" + connections.get()), closes);
            }
        );

        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(1, connections.get());

        current.set(descriptor(
            "session-a",
            projectOnlyEvidence("project-record-a.json", artifact, hostClassLoader)
                .addingWorkspaceControl(slice("workspace-a.json", artifact, hostClassLoader))
        ));
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(2, connections.get(), "adding workspace-control evidence must replace the connection");
        assertEquals(1, closes.get());

        current.set(descriptor(
            "session-a",
            projectOnlyEvidence("project-record-a.json", artifact, hostClassLoader)
                .addingWorkspaceControl(slice("workspace-b.json", artifact, hostClassLoader))
        ));
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(3, connections.get(), "changing the workspace-control record must replace the connection");
        assertEquals(2, closes.get());

        current.set(descriptor(
            "session-a",
            projectOnlyEvidence("project-record-a.json", artifact, hostClassLoader)
                .addingWorkspaceControl(slice("workspace-b.json", artifact, hostClassLoader))
                .addingBoundingBoxOverlayButton(slice("overlay-a.json", artifact, hostClassLoader))
        ));
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(4, connections.get(), "adding bounding-box overlay evidence must replace the connection");
        assertEquals(3, closes.get());

        current.set(descriptor(
            "session-a",
            projectOnlyEvidence("project-record-a.json", artifact, hostClassLoader)
                .addingWorkspaceControl(slice("workspace-b.json", artifact, hostClassLoader))
                .addingBoundingBoxOverlayButton(slice("overlay-a.json", artifact, hostClassLoader))
                .addingEmbeddedPanel(slice("panel-a.json", artifact, hostClassLoader))
        ));
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(5, connections.get(), "adding embedded-panel evidence must replace the connection");
        assertEquals(4, closes.get());

        current.set(descriptor(
            "session-a",
            projectOnlyEvidence("project-record-a.json", artifact, hostClassLoader)
                .addingWorkspaceControl(slice("workspace-b.json", artifact, hostClassLoader))
                .addingBoundingBoxOverlayButton(slice("overlay-a.json", artifact, hostClassLoader))
                .addingEmbeddedPanel(slice("panel-a.json", artifact, hostClassLoader))
                .addingTopMenu(slice("topmenu-a.json", artifact, hostClassLoader))
        ));
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(6, connections.get(), "adding top-menu evidence must replace the connection");
        assertEquals(5, closes.get());

        current.set(descriptor(
            "session-a",
            projectOnlyEvidence("project-record-a.json", artifact, hostClassLoader)
                .addingBoundingBoxOverlayButton(slice("overlay-a.json", artifact, hostClassLoader))
                .addingEmbeddedPanel(slice("panel-a.json", artifact, hostClassLoader))
                .addingTopMenu(slice("topmenu-a.json", artifact, hostClassLoader))
        ));
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(7, connections.get(), "removing workspace-control evidence must replace the connection");
        assertEquals(6, closes.get(), "removing a slice must close the old connection once");

        session.close();
        assertEquals(7, closes.get(), "the final active connection must close exactly once");
    }

    @Test
    void workspaceProviderStaysUnavailableWhileRefreshIsInProgress() throws Exception {
        ClassLoader hostClassLoader = HostSessionTest.class.getClassLoader();
        Path artifact = Path.of("host/Live2D_Cubism.jar");
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>(descriptor(
            "session-a",
            projectOnlyEvidence("project-record-a.json", artifact, hostClassLoader)
                .addingWorkspaceControl(slice("workspace-a.json", artifact, hostClassLoader))
        ));
        CountDownLatch installStarted = new CountDownLatch(1);
        CountDownLatch installRelease = new CountDownLatch(1);
        HostSession session = new HostSession(
            () -> Optional.of(current.get()),
            ignored -> workspaceConnection(installStarted, installRelease)
        );

        AtomicReference<HostSession.State> refreshResult = new AtomicReference<>();
        Thread refresher = new Thread(() -> refreshResult.set(session.refresh()), "workspace-refresh");
        refresher.start();
        try {
            assertTrue(installStarted.await(5, TimeUnit.SECONDS),
                "candidate installation must block inside refresh");

            assertEquals(
                WorkspaceStatus.Availability.UNAVAILABLE,
                session.workspaceCoordinator().current().availability(),
                "workspace must remain UNAVAILABLE while refresh is in progress"
            );

            installRelease.countDown();
            refresher.join(TimeUnit.SECONDS.toMillis(5));
            assertEquals(HostSession.State.ACTIVE, refreshResult.get(),
                "refresh must commit ACTIVE after the installation is released");
            assertEquals(
                WorkspaceStatus.Availability.AVAILABLE,
                session.workspaceCoordinator().current().availability(),
                "workspace becomes AVAILABLE only after refresh returned ACTIVE"
            );
        } finally {
            installRelease.countDown();
            refresher.join(TimeUnit.SECONDS.toMillis(5));
            session.close();
        }
    }

    @Test
    void failedCandidateInstallationNeverPublishesWorkspaceProvider() throws Exception {
        ClassLoader hostClassLoader = HostSessionTest.class.getClassLoader();
        Path artifact = Path.of("host/Live2D_Cubism.jar");
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>(descriptor(
            "session-a",
            projectOnlyEvidence("project-record-a.json", artifact, hostClassLoader)
                .addingWorkspaceControl(slice("workspace-a.json", artifact, hostClassLoader))
        ));
        HostSession session = new HostSession(
            () -> Optional.of(current.get()),
            ignored -> workspaceConnectionThrowingInstall()
        );

        assertEquals(HostSession.State.FAILED, session.refresh());
        assertEquals(
            HostSessionFailure.Code.CONNECTION_FAILED,
            session.lastFailure().orElseThrow().code()
        );
        assertEquals(
            WorkspaceStatus.Availability.UNAVAILABLE,
            session.workspaceCoordinator().current().availability(),
            "a failed candidate must never leave a workspace provider published"
        );
        session.close();
    }

    @Test
    void activeSessionInvalidSourceEvidenceFailsClosedWithoutHalfBundleOrRepeatedCleanup() {
        ClassLoader hostClassLoader = HostSessionTest.class.getClassLoader();
        Path artifact = Path.of("host/Live2D_Cubism.jar");
        AtomicInteger sourceReads = new AtomicInteger();
        AtomicInteger connections = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        HostInstanceSource source = () -> {
            if (sourceReads.incrementAndGet() == 1) {
                return Optional.of(descriptor(
                    "session-a",
                    clipEvidence("project.json", "clip.json", artifact, hostClassLoader)
                ));
            }
            return Optional.of(descriptor(
                "session-a",
                HostVerificationEvidence.withClipMask(
                    new HostVerificationEvidence.Slice(
                        Path.of("records/project.json"), artifact, hostClassLoader
                    ),
                    new HostVerificationEvidence.Slice(
                        Path.of("records/clip.json"), Path.of("host/other.jar"), hostClassLoader
                    )
                )
            ));
        };
        HostSession session = new HostSession(
            source,
            ignored -> {
                connections.incrementAndGet();
                return connection(adaptersWithClip("connected"), closes);
            }
        );

        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertTrue(session.adapters().projectWorkspace().activeProject().isAvailable());
        assertTrue(session.adapters().clipMaskRead().clipMasks().isAvailable());

        assertEquals(HostSession.State.FAILED, session.refresh());
        HostSessionFailure failure = session.lastFailure().orElseThrow();
        assertEquals(HostSessionFailure.Code.SOURCE_FAILED, failure.code());
        assertEquals("Host instance source failed safely.", failure.message());
        assertFalse(failure.message().contains("other.jar"));
        assertEquals(1, connections.get(), "invalid source evidence must fail before connector creation");
        assertEquals(1, closes.get(), "the old complete connection must close exactly once");
        assertFalse(session.adapters().projectWorkspace().activeProject().isAvailable());
        assertFalse(session.adapters().clipMaskRead().clipMasks().isAvailable());

        session.close();
        session.close();
        assertEquals(HostSession.State.CLOSED, session.state());
        assertEquals(1, closes.get(), "closing after source failure must not repeat old cleanup");
    }

    @Test
    void failedReplacementLeavesNoProjectOnlyHalfBundleAndCleansOldConnectionOnce() {
        ClassLoader hostClassLoader = HostSessionTest.class.getClassLoader();
        Path artifact = Path.of("host/Live2D_Cubism.jar");
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>(descriptor(
            "session-a", projectOnlyEvidence("project-record.json", artifact, hostClassLoader)
        ));
        AtomicInteger connections = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        HostSession session = new HostSession(
            () -> Optional.of(current.get()),
            ignored -> {
                if (connections.incrementAndGet() == 2) {
                    throw new IllegalStateException("replacement failed");
                }
                return connection(adapters("old-project"), closes);
            }
        );

        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals("old-project", activeProjectId(session));

        current.set(descriptor(
            "session-a",
            clipEvidence("project-record.json", "clip-record.json", artifact, hostClassLoader)
        ));
        assertEquals(HostSession.State.FAILED, session.refresh());
        assertEquals(HostSessionFailure.Code.CONNECTION_FAILED, session.lastFailure().orElseThrow().code());
        assertEquals(2, connections.get());
        assertEquals(1, closes.get(), "the replaced connection must be cleaned exactly once");
        assertFalse(session.adapters().projectWorkspace().activeProject().isAvailable(),
            "failed replacement must expose safe mode, not the old project adapter");
        assertFalse(session.adapters().clipMaskRead().clipMasks().isAvailable(),
            "failed replacement must not expose a new project-only half bundle");

        session.close();
        assertEquals(1, closes.get(), "failed candidate created no additional closeable connection");
    }

    @Test
    void clearClosesDynamicRegistrationExactlyOnce() {
        AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>(descriptor("session-a"));
        AtomicInteger registrationCloses = new AtomicInteger();
        HostSession session = new HostSession(
            () -> Optional.ofNullable(current.get()),
            descriptor -> HostAdapterConnection.of(adaptersWithStatusRegistration(registrationCloses))
        );
        session.refresh();
        Registration registration = session.adapters().statusToolbar().notifyStatus(
            new StatusNotification("status-1", "INFO", "Connected")
        ).value().orElseThrow();

        current.set(null);
        assertEquals(HostSession.State.SAFE_MODE, session.refresh());
        assertEquals(1, registrationCloses.get());

        registration.close();
        session.close();
        assertEquals(1, registrationCloses.get());
    }

    @Test
    void connectionKeyUsesNormalizedStringsAndNeverPathEquals() {
        ThrowingEqualsPath record = new ThrowingEqualsPath(Path.of("records/reviewed.json"));
        ThrowingEqualsPath artifact = new ThrowingEqualsPath(Path.of("host/Live2D_Cubism.jar"));
        HostInstanceDescriptor descriptor = new HostInstanceDescriptor(
            "session-a",
            HostVerificationEvidence.projectOnly(new HostVerificationEvidence.Slice(
                record, artifact, getClass().getClassLoader()
            ))
        );
        AtomicInteger connections = new AtomicInteger();
        HostSession session = new HostSession(
            () -> Optional.of(descriptor),
            ignored -> {
                connections.incrementAndGet();
                return HostAdapterConnection.of(adapters("session-a"));
            }
        );

        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(HostSession.State.ACTIVE, session.refresh());
        assertEquals(1, connections.get());
    }

    @Test
    void connectionKeyNullNormalizationFailsClosed() {
        HostInstanceDescriptor descriptor = new HostInstanceDescriptor(
            "broken-path",
            HostVerificationEvidence.projectOnly(new HostVerificationEvidence.Slice(
                new NullNormalizingPath(Path.of("record.json")),
                Path.of("host/Live2D_Cubism.jar"),
                getClass().getClassLoader()
            ))
        );
        HostSession session = new HostSession(
            () -> Optional.of(descriptor),
            ignored -> fail("connector must not run")
        );

        assertEquals(HostSession.State.FAILED, session.refresh());
        assertEquals(HostSessionFailure.Code.CONNECTION_FAILED, session.lastFailure().orElseThrow().code());
    }

    @Test
    void connectionKeyPathFailureIsSanitizedAndFailsClosed() {
        HostInstanceDescriptor descriptor = new HostInstanceDescriptor(
            "broken-path",
            HostVerificationEvidence.projectOnly(new HostVerificationEvidence.Slice(
                new ThrowingAbsolutePath(Path.of("record.json")),
                Path.of("host/Live2D_Cubism.jar"),
                getClass().getClassLoader()
            ))
        );
        HostSession session = new HostSession(
            () -> Optional.of(descriptor),
            ignored -> fail("connector must not run")
        );

        assertEquals(HostSession.State.FAILED, session.refresh());
        assertEquals(HostSessionFailure.Code.CONNECTION_FAILED, session.lastFailure().orElseThrow().code());
        assertFalse(session.lastFailure().orElseThrow().message().contains("private-path-detail"));
    }

    static void assertCallbackCanReadState(final HostSession session) {
        Thread callback = new Thread(session::state);
        callback.start();
        join(callback);
    }

    static void captureFailure(final Runnable action, final AtomicReference<Throwable> failure) {
        try {
            action.run();
        } catch (Throwable throwable) {
            failure.set(throwable);
        }
    }

    static void awaitLatch(final CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                fail("latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    static void join(final Thread thread) {
        try {
            thread.join(5_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(exception);
        }
        if (thread.isAlive()) {
            fail("thread did not finish");
        }
    }

    static StatusToolbarAdapter statusAdapter(final Registration registration) {
        return new StatusToolbarAdapter() {
            @Override public AdapterResult<Registration> notifyStatus(StatusNotification ignored) {
                return AdapterResult.available(registration);
            }
        };
    }

    private static class DelegatingPath implements Path {
        final Path delegate;
        private DelegatingPath(final Path delegate) { this.delegate = delegate; }
        @Override public FileSystem getFileSystem() { return delegate.getFileSystem(); }
        @Override public boolean isAbsolute() { return delegate.isAbsolute(); }
        @Override public Path getRoot() { return delegate.getRoot(); }
        @Override public Path getFileName() { return delegate.getFileName(); }
        @Override public Path getParent() { return delegate.getParent(); }
        @Override public int getNameCount() { return delegate.getNameCount(); }
        @Override public Path getName(int index) { return delegate.getName(index); }
        @Override public Path subpath(int beginIndex, int endIndex) { return delegate.subpath(beginIndex, endIndex); }
        @Override public boolean startsWith(Path other) { return delegate.startsWith(other); }
        @Override public boolean endsWith(Path other) { return delegate.endsWith(other); }
        @Override public Path normalize() { return delegate.normalize(); }
        @Override public Path resolve(Path other) { return delegate.resolve(other); }
        @Override public Path relativize(Path other) { return delegate.relativize(other); }
        @Override public URI toUri() { return delegate.toUri(); }
        @Override public Path toAbsolutePath() { return delegate.toAbsolutePath(); }
        @Override public Path toRealPath(LinkOption... options) throws java.io.IOException { return delegate.toRealPath(options); }
        @Override public File toFile() { return delegate.toFile(); }
        @Override public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) throws java.io.IOException { return delegate.register(watcher, events, modifiers); }
        @Override public int compareTo(Path other) { return delegate.compareTo(other); }
        @Override public Iterator<Path> iterator() { return delegate.iterator(); }
        @Override public boolean equals(Object other) { return delegate.equals(other); }
        @Override public int hashCode() { return delegate.hashCode(); }
        @Override public String toString() { return delegate.toString(); }
    }

    private static final class ThrowingEqualsPath extends DelegatingPath {
        private ThrowingEqualsPath(final Path delegate) { super(delegate); }
        @Override public Path toAbsolutePath() { return this; }
        @Override public Path normalize() { return this; }
        @Override public boolean equals(Object other) { throw new IllegalStateException("path equals called"); }
    }

    private static final class NullNormalizingPath extends DelegatingPath {
        private NullNormalizingPath(final Path delegate) { super(delegate); }
        @Override public Path toAbsolutePath() { return this; }
        @Override public Path normalize() { return null; }
    }

    private static final class ThrowingAbsolutePath extends DelegatingPath {
        private ThrowingAbsolutePath(final Path delegate) { super(delegate); }
        @Override public Path toAbsolutePath() { throw new IllegalStateException("private-path-detail"); }
    }

    static void awaitWaiting(final Thread thread) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            final Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(1);
        }
        fail("thread did not wait for the in-flight registration close");
    }

    static HostInstanceDescriptor descriptor(final String sessionId) {
        return descriptor(sessionId, "Live2D_Cubism.jar", HostSessionTest.class.getClassLoader());
    }

    static HostInstanceDescriptor descriptor(
        final String sessionId,
        final HostVerificationEvidence evidence
    ) {
        return new HostInstanceDescriptor(sessionId, evidence);
    }

    static HostVerificationEvidence projectOnlyEvidence(
        final String projectRecord,
        final Path artifact,
        final ClassLoader classLoader
    ) {
        return HostVerificationEvidence.projectOnly(new HostVerificationEvidence.Slice(
            Path.of("records").resolve(projectRecord), artifact, classLoader
        ));
    }

    static HostVerificationEvidence.Slice slice(
        final String record,
        final Path artifact,
        final ClassLoader classLoader
    ) {
        return new HostVerificationEvidence.Slice(
            Path.of("records").resolve(record), artifact, classLoader
        );
    }

    static HostVerificationEvidence clipEvidence(
        final String projectRecord,
        final String clipRecord,
        final Path artifact,
        final ClassLoader classLoader
    ) {
        return HostVerificationEvidence.withClipMask(
            new HostVerificationEvidence.Slice(
                Path.of("records").resolve(projectRecord), artifact, classLoader
            ),
            new HostVerificationEvidence.Slice(
                Path.of("records").resolve(clipRecord), artifact, classLoader
            )
        );
    }

    static String activeProjectId(final HostSession session) {
        return session.adapters().projectWorkspace().activeProject()
            .value().orElseThrow().orElseThrow().projectId();
    }

    static HostInstanceDescriptor descriptor(
        final String sessionId,
        final String artifactName,
        final ClassLoader classLoader
    ) {
        return new HostInstanceDescriptor(
            sessionId,
            HostVerificationEvidence.projectOnly(new HostVerificationEvidence.Slice(
                Path.of("records/reviewed.json"),
                Path.of("host").resolve(artifactName),
                classLoader
            ))
        );
    }

    static HostAdapterConnection workspaceConnection(
        final CountDownLatch installStarted,
        final CountDownLatch installRelease
    ) {
        return new HostAdapterConnection() {
            private final WorkspaceHostProvider provider = availableWorkspaceProvider();

            @Override
            public RuntimeHostAdapters adapters() {
                return RuntimeHostAdapters.safeMode();
            }

            @Override
            public WorkspaceHostProvider workspaceProvider() {
                return provider;
            }

            @Override
            public List<EditorUiContributionProvider> editorUiProviders(final long hostGeneration) {
                installStarted.countDown();
                try {
                    installRelease.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }

            @Override
            public void close() {
            }
        };
    }

    static HostAdapterConnection workspaceConnectionThrowingInstall() {
        return new HostAdapterConnection() {
            @Override
            public RuntimeHostAdapters adapters() {
                return RuntimeHostAdapters.safeMode();
            }

            @Override
            public WorkspaceHostProvider workspaceProvider() {
                return availableWorkspaceProvider();
            }

            @Override
            public List<EditorUiContributionProvider> editorUiProviders(final long hostGeneration) {
                throw new IllegalStateException("candidate installation failed");
            }

            @Override
            public void close() {
            }
        };
    }

    static WorkspaceHostProvider availableWorkspaceProvider() {
        return new WorkspaceHostProvider() {
            @Override public WorkspaceStatus readStatus() {
                return new WorkspaceStatus(
                    WorkspaceStatus.Availability.AVAILABLE,
                    Optional.empty(),
                    List.of(),
                    Optional.empty()
                );
            }
            @Override public WorkspaceOperationResult.Outcome switchTo(final WorkspaceId workspaceId) {
                return WorkspaceOperationResult.Outcome.CHANGED;
            }
            @Override public WorkspaceOperationResult.Outcome updateDefault() {
                return WorkspaceOperationResult.Outcome.CHANGED;
            }
            @Override public WorkspaceOperationResult.Outcome resetToDefault() {
                return WorkspaceOperationResult.Outcome.CHANGED;
            }
        };
    }

    static HostAdapterConnection connection(
        final RuntimeHostAdapters adapters,
        final AtomicInteger closes
    ) {
        return new HostAdapterConnection() {
            @Override public RuntimeHostAdapters adapters() { return adapters; }
            @Override public void close() { closes.incrementAndGet(); }
        };
    }

    static RuntimeHostAdapters adaptersWithFailingStatusRegistration(
        final AtomicInteger registrationCloses
    ) {
        RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        StatusToolbarAdapter statusToolbar = new StatusToolbarAdapter() {
            @Override
            public AdapterResult<Registration> notifyStatus(StatusNotification notification) {
                return AdapterResult.available(() -> {
                    if (registrationCloses.incrementAndGet() == 1) {
                        throw new IllegalStateException("first-registration-close-failed");
                    }
                });
            }
        };
        return new RuntimeHostAdapters(
            safe.themeStatus(), safe.renderStatus(), safe.projectWorkspace(), safe.clipMaskRead(),
            statusToolbar, safe.uiSurface()
        );
    }

    static RuntimeHostAdapters adaptersWithStatusRegistration(
        final AtomicInteger registrationCloses
    ) {
        RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        StatusToolbarAdapter statusToolbar = new StatusToolbarAdapter() {
            @Override
            public AdapterResult<Registration> notifyStatus(StatusNotification notification) {
                return AdapterResult.available(registrationCloses::incrementAndGet);
            }
        };
        return new RuntimeHostAdapters(
            safe.themeStatus(),
            safe.renderStatus(),
            safe.projectWorkspace(),
            safe.clipMaskRead(),
            statusToolbar,
                        safe.uiSurface()
        );
    }

    static RuntimeHostAdapters adaptersWithClip(final String sessionId) {
        RuntimeHostAdapters safe = adapters(sessionId);
        dev.turboism.adapter.cubism.ClipMaskReadAdapter clipMask =
            dev.turboism.adapter.cubism.ClipMaskReadAdapter.Impl.connected(
                new dev.turboism.adapter.cubism.ClipMaskReadAdapter.HostOperations() {
                    @Override public String hostVersion() { return "5.3.02"; }
                    @Override public boolean supportsClipMaskRead() { return true; }
                    @Override public List<ClipMaskSnapshot> clipMasks() {
                        return List.of(new ClipMaskSnapshot("target", List.of("source"), false));
                    }
                }
            );
        return new RuntimeHostAdapters(
            safe.themeStatus(), safe.renderStatus(), safe.projectWorkspace(), clipMask,
            safe.statusToolbar(), safe.uiSurface()
        );
    }

    static RuntimeHostAdapters adapters(final String sessionId) {
        RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        ProjectWorkspaceAdapter projectWorkspace = ProjectWorkspaceAdapter.Impl.connected(
            new ProjectWorkspaceAdapter.HostOperations() {
                @Override public String hostVersion() { return "5.3.02"; }
                @Override public boolean supportsProjectWorkspaceRead() { return true; }
                @Override public Optional<ProjectSnapshot> activeProject() {
                    return Optional.of(new ProjectSnapshot(sessionId, "Demo", Optional.empty(), List.of()));
                }
                @Override public Optional<WorkspaceSnapshot> workspace() { return Optional.empty(); }
            }
        );
        return new RuntimeHostAdapters(
            safe.themeStatus(),
            safe.renderStatus(),
            projectWorkspace,
            safe.clipMaskRead(),
            safe.statusToolbar(),
                        safe.uiSurface()
        );
    }
}
