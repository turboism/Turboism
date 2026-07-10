package dev.turboism.adapter.cubism;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedProjectWorkspaceHostOperationsTest {

    @Test
    void convertsSyntheticHostGraphToSdkOnlySnapshots() {
        SyntheticAppCtrl.instance = new SyntheticAppCtrl(
            new SyntheticProject("Demo Project", List.of(
                new SyntheticDocument(new SyntheticFileContent(new File("C:/models/demo/model.cmo3")))
            )),
            new SyntheticMainFrame(new SyntheticDockWrapper(
                new SyntheticWorkspace("workspace-model", "Modeling", "workspace-guid")
            ))
        );
        VerifiedProjectWorkspaceHostOperations operations = new VerifiedProjectWorkspaceHostOperations(
            resolver(),
            "5.3.02"
        );

        var project = operations.activeProject().orElseThrow();
        var workspace = operations.workspace().orElseThrow();

        assertEquals("Demo Project", project.name());
        assertEquals(1, project.documents().size());
        assertTrue(project.projectDirectory().isEmpty());
        assertTrue(project.documents().get(0).filePath().isEmpty());
        assertFalse(project.documents().get(0).relativePath().contains("C:"));
        assertEquals("workspace-model", workspace.workspaceId());
        assertEquals("workspaces/workspace-model", workspace.rootRelativePath());
        assertTrue(workspace.recentProjectIds().isEmpty());
    }

    @Test
    void runtimeSelectorFailureIsReportedAsMappingNotVerified() {
        SyntheticAppCtrl.instance = new SyntheticAppCtrl(
            new SyntheticProject("Demo", List.of()),
            new SyntheticMainFrame(new SyntheticDockWrapper(null))
        );
        VerifiedProjectWorkspaceHostOperations operations = new VerifiedProjectWorkspaceHostOperations(
            resolverWithout("cubism.project.name"),
            "5.3.02"
        );
        ProjectWorkspaceAdapter adapter = ProjectWorkspaceAdapter.Impl.connected(operations);

        assertEquals(
            dev.turboism.adapter.ui.SafeModeDiagnostic.Code.MAPPING_NOT_VERIFIED,
            adapter.activeProject().diagnostic().orElseThrow().code()
        );
    }

    @Test
    void verifiedHostGetterFailureIsValidationFailureNotMappingFailure() {
        SyntheticAppCtrl.instance = new SyntheticAppCtrl(
            new SyntheticProject("Demo", List.of()) {
                @Override public String name() {
                    throw new IllegalStateException("private-host-detail");
                }
            },
            null
        );
        VerifiedProjectWorkspaceHostOperations operations = new VerifiedProjectWorkspaceHostOperations(resolver(), "5.3.02");
        ProjectWorkspaceAdapter adapter = ProjectWorkspaceAdapter.Impl.connected(operations);

        var diagnostic = adapter.activeProject().diagnostic().orElseThrow();
        assertEquals(dev.turboism.adapter.ui.SafeModeDiagnostic.Code.VALIDATION_FAILURE, diagnostic.code());
        assertFalse(diagnostic.message().contains("private-host-detail"));
    }

    @Test
    void usesGuidWhenWorkspaceIdIsBlankAndFailsClosedWithoutEitherIdentity() {
        SyntheticAppCtrl.instance = new SyntheticAppCtrl(
            new SyntheticProject("Demo", List.of(new SyntheticDocument(
                new SyntheticFileContent(new File("C:/models/demo/model.cmo3"))
            ))),
            new SyntheticMainFrame(new SyntheticDockWrapper(
                new SyntheticWorkspace(new SyntheticId(""), "Modeling", new SyntheticGuid("workspace-guid"))
            ))
        );
        VerifiedProjectWorkspaceHostOperations operations = new VerifiedProjectWorkspaceHostOperations(
            resolver(),
            "5.3.02"
        );
        String guidFallback = operations.workspace().orElseThrow().workspaceId();
        assertTrue(guidFallback.startsWith("workspace-"));
        assertFalse(guidFallback.equals("workspace-unknown"));

        SyntheticAppCtrl.instance = new SyntheticAppCtrl(
            null,
            new SyntheticMainFrame(new SyntheticDockWrapper(
                new SyntheticWorkspace(new SyntheticId(""), "Modeling", new SyntheticGuid(""))
            ))
        );
        assertTrue(operations.workspace().isEmpty());
    }

    @Test
    void documentIdentityDoesNotChangeWhenDocumentOrderChanges() {
        SyntheticDocument first = new SyntheticDocument(new SyntheticFileContent(new File("C:/models/demo/first.cmo3")));
        SyntheticDocument second = new SyntheticDocument(new SyntheticFileContent(new File("C:/models/demo/second.cmo3")));
        SyntheticAppCtrl.instance = new SyntheticAppCtrl(
            new SyntheticProject("Demo", List.of(first, second)),
            new SyntheticMainFrame(new SyntheticDockWrapper(null))
        );
        VerifiedProjectWorkspaceHostOperations operations = new VerifiedProjectWorkspaceHostOperations(resolver(), "5.3.02");
        var firstOrder = operations.activeProject().orElseThrow().documents().stream()
            .collect(java.util.stream.Collectors.toMap(dev.turboism.sdk.cubism.DocumentSnapshot::name, dev.turboism.sdk.cubism.DocumentSnapshot::documentId));

        SyntheticAppCtrl.instance = new SyntheticAppCtrl(
            new SyntheticProject("Renamed Demo", List.of(second, first)),
            new SyntheticMainFrame(new SyntheticDockWrapper(null))
        );
        var secondOrder = operations.activeProject().orElseThrow().documents().stream()
            .collect(java.util.stream.Collectors.toMap(dev.turboism.sdk.cubism.DocumentSnapshot::name, dev.turboism.sdk.cubism.DocumentSnapshot::documentId));

        assertEquals(firstOrder, secondOrder);
    }

    @Test
    void projectAndDocumentIdsRemainStableAcrossRenameAddRemoveAndSaveAsWithinSession() {
        SyntheticDocument first = new SyntheticDocument(new SyntheticFileContent(new File("C:/models/demo/first.cmo3")));
        SyntheticProject project = new SyntheticProject("Demo", new java.util.ArrayList<>(List.of(first)));
        SyntheticAppCtrl.instance = new SyntheticAppCtrl(project, null);
        VerifiedProjectWorkspaceHostOperations operations = new VerifiedProjectWorkspaceHostOperations(resolver(), "5.3.02");

        var initial = operations.activeProject().orElseThrow();
        String projectId = initial.projectId();
        String documentId = initial.documents().get(0).documentId();

        project.setName("Renamed Demo");
        first.setFileContent(new SyntheticFileContent(new File("D:/saved-as/renamed.cmo3")));
        project.documents().add(new SyntheticDocument(new SyntheticFileContent(null)));
        var changed = operations.activeProject().orElseThrow();

        assertEquals(projectId, changed.projectId());
        assertEquals(documentId, changed.documents().stream()
            .filter(document -> document.name().equals("renamed.cmo3"))
            .findFirst().orElseThrow().documentId());
        assertTrue(changed.documents().stream().anyMatch(document -> document.name().equals("untitled")));

        project.documents().remove(first);
        assertEquals(projectId, operations.activeProject().orElseThrow().projectId());
    }

    @Test
    void returnsEmptySnapshotsWhenNoHostInstanceExists() {
        SyntheticAppCtrl.instance = null;
        VerifiedProjectWorkspaceHostOperations operations = new VerifiedProjectWorkspaceHostOperations(
            resolver(),
            "5.3.02"
        );

        assertTrue(operations.activeProject().isEmpty());
        assertTrue(operations.workspace().isEmpty());
    }

    private static VerifiedMemberResolver resolver() {
        return resolverWithout("");
    }

    private static VerifiedMemberResolver resolverWithout(final String omittedAlias) {
        List<StaticSelector> selectors = List.of(
            StaticSelector.staticMethod("cubism.app-controller.instance", name(SyntheticAppCtrl.class), "instance", "()L" + name(SyntheticAppCtrl.class) + ";", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("cubism.app-controller.current-project", name(SyntheticAppCtrl.class), "currentProject", "()L" + name(SyntheticProject.class) + ";", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("cubism.app-controller.main-frame", name(SyntheticAppCtrl.class), "mainFrame", "()L" + name(SyntheticMainFrame.class) + ";", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("cubism.project.name", name(SyntheticProject.class), "name", "()Ljava/lang/String;", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("cubism.project.documents", name(SyntheticProject.class), "documents", "()Ljava/util/List;", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("cubism.document.file-content", name(SyntheticDocument.class), "fileContent", "()L" + name(SyntheticFileContent.class) + ";", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("cubism.file-content.file", name(SyntheticFileContent.class), "file", "()Ljava/io/File;", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("cubism.main-frame.dock-manager", name(SyntheticMainFrame.class), "dockManager", "()L" + name(SyntheticDockWrapper.class) + ";", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("cubism.dock-wrapper.last-workspace", name(SyntheticDockWrapper.class), "lastWorkspace", "()L" + name(SyntheticWorkspace.class) + ";", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("cubism.workspace.id", name(SyntheticWorkspace.class), "id", "()L" + name(SyntheticId.class) + ";", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("cubism.workspace.guid", name(SyntheticWorkspace.class), "guid", "()L" + name(SyntheticGuid.class) + ";", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("cubism.id.value", name(SyntheticId.class), "idString", "()Ljava/lang/String;", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("cubism.guid.value", name(SyntheticGuid.class), "uuidString", "()Ljava/lang/String;", StaticSelector.ACCESS_PUBLIC)
        ).stream().filter(selector -> !selector.alias().equals(omittedAlias)).toList();
        return TestVerifiedResolvers.create(
            ProjectWorkspaceAdapter.ADAPTER_SLICE_ID,
            java.util.Set.of(
                ProjectWorkspaceAdapter.PROJECT_CAPABILITY_ID,
                ProjectWorkspaceAdapter.WORKSPACE_CAPABILITY_ID
            ),
            selectors,
            SyntheticAppCtrl.class.getClassLoader()
        );
    }

    private static String name(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    public static final class SyntheticAppCtrl {
        private static SyntheticAppCtrl instance;
        private final SyntheticProject project;
        private final SyntheticMainFrame mainFrame;
        SyntheticAppCtrl(SyntheticProject project, SyntheticMainFrame mainFrame) { this.project = project; this.mainFrame = mainFrame; }
        public static SyntheticAppCtrl instance() { return instance; }
        public SyntheticProject currentProject() { return project; }
        public SyntheticMainFrame mainFrame() { return mainFrame; }
    }

    public static class SyntheticProject {
        private String name;
        private final List<SyntheticDocument> documents;
        SyntheticProject(String name, List<SyntheticDocument> documents) { this.name = name; this.documents = documents; }
        public String name() { return name; }
        public void setName(String name) { this.name = name; }
        public List<SyntheticDocument> documents() { return documents; }
    }
    public static class SyntheticDocument {
        private SyntheticFileContent fileContent;
        SyntheticDocument(SyntheticFileContent fileContent) { this.fileContent = fileContent; }
        public SyntheticFileContent fileContent() { return fileContent; }
        public void setFileContent(SyntheticFileContent fileContent) { this.fileContent = fileContent; }
    }
    public record SyntheticFileContent(File file) { }
    public record SyntheticMainFrame(SyntheticDockWrapper dockManager) { }
    public record SyntheticDockWrapper(SyntheticWorkspace lastWorkspace) { }
    public record SyntheticWorkspace(SyntheticId id, String name, SyntheticGuid guid) {
        SyntheticWorkspace(String id, String name, String guid) { this(new SyntheticId(id), name, new SyntheticGuid(guid)); }
    }
    public record SyntheticId(String idString) { }
    public record SyntheticGuid(String uuidString) { }
}
