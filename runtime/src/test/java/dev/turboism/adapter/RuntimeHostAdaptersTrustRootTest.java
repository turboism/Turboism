package dev.turboism.adapter;

import dev.turboism.adapter.cubism.ClipMaskReadAdapter;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.mapping.verification.ClipMaskVerificationManifest;
import dev.turboism.mapping.verification.ProjectWorkspaceVerificationManifest;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeHostAdaptersTrustRootTest {

    @AfterEach
    void clearHost() {
        SyntheticHost.instance = null;
    }

    @Test
    void projectOnlyBundleLeavesClipMaskUnavailable() {
        SyntheticHost.instance = host();

        RuntimeHostAdapters adapters = RuntimeHostAdapters.withVerifiedProjectWorkspace(projectResolver());

        assertEquals("project-session-1", adapters.projectWorkspace().activeProject().value().orElseThrow().orElseThrow().projectId());
        assertFalse(adapters.clipMaskRead().clipMasks().isAvailable());
    }

    @Test
    void clipMaskOnlyBundleLeavesProjectWorkspaceUnavailable() {
        SyntheticHost.instance = host();

        RuntimeHostAdapters adapters = RuntimeHostAdapters.withVerifiedClipMask(clipResolver());

        assertEquals("target", adapters.clipMaskRead().clipMasks().value().orElseThrow().get(0).targetMeshId());
        assertFalse(adapters.projectWorkspace().activeProject().isAvailable());
        assertFalse(adapters.projectWorkspace().workspace().isAvailable());
    }

    @Test
    void resolverCannotBeUsedInTheWrongTrustRootSlot() {
        assertThrows(IllegalArgumentException.class,
            () -> RuntimeHostAdapters.withVerifiedProjectWorkspace(clipResolver()));
        assertThrows(IllegalArgumentException.class,
            () -> RuntimeHostAdapters.withVerifiedClipMask(projectResolver()));
    }

    @Test
    void independentlyAuthorizedResolversComposeOneDualBundle() {
        SyntheticHost.instance = host();

        RuntimeHostAdapters adapters = RuntimeHostAdapters.withVerifiedProjectWorkspaceAndClipMask(
            projectResolver(),
            clipResolver()
        );

        assertEquals("project-session-1", adapters.projectWorkspace().activeProject().value().orElseThrow().orElseThrow().projectId());
        assertEquals("project-session-1", adapters.projectWorkspace().workspace().value().orElseThrow().orElseThrow().workspaceId());
        assertTrue(adapters.clipMaskRead().clipMasks().isAvailable());
        assertEquals(List.of("mask-a", "mask-b"),
            adapters.clipMaskRead().clipMasks().value().orElseThrow().get(0).orderedMaskSourceIds());
    }

    private static SyntheticHost host() {
        return new SyntheticHost(
            new SyntheticProject("project-session-1", "Project"),
            new SyntheticWorkspace("project-session-1", "Workspace"),
            new SyntheticDocument(new SyntheticModel(List.of(
                new SyntheticMesh(new SyntheticGuid("target"), List.of(
                    new SyntheticGuid("mask-a"), new SyntheticGuid("mask-b")
                ), true)
            )))
        );
    }

    private static VerifiedMemberResolver projectResolver() {
        String host = name(SyntheticHost.class);
        String project = name(SyntheticProject.class);
        String workspace = name(SyntheticWorkspace.class);
        return TestVerifiedResolvers.create(
            ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
            ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
            ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES.stream()
                .sorted()
                .map(alias -> switch (alias) {
                    case "cubism.app-controller.class" -> StaticSelector.classSelector(alias, host);
                    case "cubism.project.class" -> StaticSelector.classSelector(alias, project);
                    case "cubism.document.class" -> StaticSelector.classSelector(alias, name(SyntheticProjectDocument.class));
                    case "cubism.file-content.class" -> StaticSelector.classSelector(alias, name(SyntheticFileContent.class));
                    case "cubism.main-frame.class", "cubism.dock-wrapper.class" -> StaticSelector.classSelector(alias, host);
                    case "cubism.workspace.class" -> StaticSelector.classSelector(alias, workspace);
                    case "cubism.id.class" -> StaticSelector.classSelector(alias, name(SyntheticId.class));
                    case "cubism.guid.class" -> StaticSelector.classSelector(alias, name(SyntheticGuid.class));
                    case "cubism.app-controller.instance" -> StaticSelector.staticMethod(alias, host, "instance", "()L" + host + ";", StaticSelector.ACCESS_PUBLIC);
                    case "cubism.app-controller.current-project" -> StaticSelector.method(alias, host, "currentProject", "()L" + project + ";", StaticSelector.ACCESS_PUBLIC);
                    case "cubism.app-controller.main-frame" -> StaticSelector.method(alias, host, "mainFrame", "()L" + host + ";", StaticSelector.ACCESS_PUBLIC);
                    case "cubism.project.name" -> StaticSelector.method(alias, project, "name", "()Ljava/lang/String;", StaticSelector.ACCESS_PUBLIC);
                    case "cubism.project.documents" -> StaticSelector.method(alias, project, "documents", "()Ljava/util/List;", StaticSelector.ACCESS_PUBLIC);
                    case "cubism.document.file-content" -> StaticSelector.method(alias, name(SyntheticProjectDocument.class), "fileContent", "()L" + name(SyntheticFileContent.class) + ";", StaticSelector.ACCESS_PUBLIC);
                    case "cubism.file-content.file" -> StaticSelector.method(alias, name(SyntheticFileContent.class), "file", "()Ljava/io/File;", StaticSelector.ACCESS_PUBLIC);
                    case "cubism.main-frame.dock-manager" -> StaticSelector.method(alias, host, "dockManager", "()L" + host + ";", StaticSelector.ACCESS_PUBLIC);
                    case "cubism.dock-wrapper.last-workspace" -> StaticSelector.method(alias, host, "lastWorkspace", "()L" + workspace + ";", StaticSelector.ACCESS_PUBLIC);
                    case "cubism.workspace.id" -> StaticSelector.method(alias, workspace, "id", "()L" + name(SyntheticId.class) + ";", StaticSelector.ACCESS_PUBLIC);
                    case "cubism.workspace.guid" -> StaticSelector.method(alias, workspace, "guid", "()L" + name(SyntheticGuid.class) + ";", StaticSelector.ACCESS_PUBLIC);
                    case "cubism.id.value" -> StaticSelector.method(alias, name(SyntheticId.class), "idString", "()Ljava/lang/String;", StaticSelector.ACCESS_PUBLIC);
                    case "cubism.guid.value" -> StaticSelector.method(alias, name(SyntheticGuid.class), "uuidString", "()Ljava/lang/String;", StaticSelector.ACCESS_PUBLIC);
                    default -> throw new IllegalArgumentException("unexpected project alias: " + alias);
                })
                .toList(),
            SyntheticHost.class.getClassLoader()
        );
    }

    private static VerifiedMemberResolver clipResolver() {
        String host = name(SyntheticHost.class);
        String document = name(SyntheticDocument.class);
        String model = name(SyntheticModel.class);
        String mesh = name(SyntheticMesh.class);
        String guid = name(SyntheticGuid.class);
        return TestVerifiedResolvers.create(
            ClipMaskVerificationManifest.ADAPTER_SLICE_ID,
            ClipMaskVerificationManifest.CAPABILITY_IDS,
            List.of(
                StaticSelector.classSelector("cubism.clipmask.app-controller.class", host),
                StaticSelector.staticMethod("cubism.clipmask.app-controller.instance", host, "instance", "()L" + host + ";", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.method("cubism.clipmask.app-controller.current-document", host, "currentDocument", "()Ljava/lang/Object;", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.classSelector("cubism.clipmask.document.class", SyntheticDocumentMarker.class.getName().replace('.', '/')),
                StaticSelector.classSelector("cubism.clipmask.modeling-document.class", document),
                StaticSelector.method("cubism.clipmask.modeling-document.model-source", document, "modelSource", "()L" + model + ";", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.classSelector("cubism.clipmask.model-source.class", model),
                StaticSelector.method("cubism.clipmask.model-source.all-art-meshes", model, "allArtMeshes", "()Ljava/util/List;", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.classSelector("cubism.clipmask.art-mesh-source.class", mesh),
                StaticSelector.classSelector("cubism.clipmask.drawable-source.class", mesh),
                StaticSelector.method("cubism.clipmask.drawable-source.guid", mesh, "guid", "()L" + guid + ";", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.method("cubism.clipmask.drawable-source.clip-guid-list", mesh, "clipGuidList", "()Ljava/lang/Object;", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.method("cubism.clipmask.drawable-source.invert-clipping-mask", mesh, "inverted", "()Z", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.classSelector("cubism.clipmask.drawable-guid.class", guid),
                StaticSelector.classSelector("cubism.clipmask.guid.class", guid),
                StaticSelector.method("cubism.clipmask.guid.value", guid, "uuidString", "()Ljava/lang/String;", StaticSelector.ACCESS_PUBLIC)
            ),
            SyntheticHost.class.getClassLoader()
        );
    }

    private static String name(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    interface SyntheticDocumentMarker { }

    public static final class SyntheticHost {
        private static SyntheticHost instance;
        private final SyntheticProject project;
        private final SyntheticWorkspace workspace;
        private final SyntheticDocument document;
        SyntheticHost(SyntheticProject project, SyntheticWorkspace workspace, SyntheticDocument document) {
            this.project = project;
            this.workspace = workspace;
            this.document = document;
        }
        public static SyntheticHost instance() { return instance; }
        public SyntheticProject currentProject() { return project; }
        public SyntheticHost mainFrame() { return this; }
        public SyntheticHost dockManager() { return this; }
        public SyntheticWorkspace lastWorkspace() { return workspace; }
        public Object currentDocument() { return document; }
    }

    public record SyntheticProject(String projectId, String name) {
        public List<SyntheticProjectDocument> documents() { return List.of(); }
    }
    public record SyntheticProjectDocument(SyntheticFileContent fileContent) { }
    public record SyntheticFileContent(java.io.File file) { }
    public record SyntheticWorkspace(String workspaceId, String name) {
        public SyntheticId id() { return new SyntheticId(workspaceId); }
        public SyntheticGuid guid() { return new SyntheticGuid(workspaceId); }
    }
    public record SyntheticId(String idString) { }
    public record SyntheticDocument(SyntheticModel modelSource) implements SyntheticDocumentMarker { }
    public record SyntheticModel(List<SyntheticMesh> allArtMeshes) { }
    public record SyntheticMesh(SyntheticGuid guid, Object clipGuidList, boolean inverted) { }
    public record SyntheticGuid(String uuidString) { }
}
