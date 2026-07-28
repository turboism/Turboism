package dev.turboism.adapter.cubism;

import dev.turboism.adapter.ui.AdapterHostException;
import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.mapping.verification.ProjectWorkspaceVerificationManifest;
import dev.turboism.mapping.verification.VerifiedAccessException;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Project/workspace HostOperations backed only by exact verified selectors. */
public final class VerifiedProjectWorkspaceHostOperations implements ProjectWorkspaceAdapter.HostOperations {

    private static final String APP_INSTANCE = "cubism.app-controller.instance";
    private static final String CURRENT_PROJECT = "cubism.app-controller.current-project";
    private static final String CURRENT_DOCUMENT = "cubism.app-controller.current-document";
    private static final String MAIN_FRAME = "cubism.app-controller.main-frame";
    private static final String PROJECT_DOCUMENTS = "cubism.project.documents";
    private static final String DOCUMENT_FILE_CONTENT = "cubism.document.file-content";
    private static final String FILE_CONTENT_FILE = "cubism.file-content.file";
    private static final String DOCK_MANAGER = "cubism.main-frame.dock-manager";
    private static final String LAST_WORKSPACE = "cubism.dock-wrapper.last-workspace";
    private static final String WORKSPACE_ID = "cubism.workspace.id";
    private static final String WORKSPACE_NAME = "cubism.workspace.name";
    private static final String WORKSPACE_GUID = "cubism.workspace.guid";
    private static final String ID_VALUE = "cubism.id.value";
    private static final String GUID_VALUE = "cubism.guid.value";

    private static final java.util.Set<String> METHOD_ALIASES_USED = java.util.Set.of(
        APP_INSTANCE,
        CURRENT_PROJECT,
        CURRENT_DOCUMENT,
        MAIN_FRAME,
        PROJECT_DOCUMENTS,
        DOCUMENT_FILE_CONTENT,
        FILE_CONTENT_FILE,
        DOCK_MANAGER,
        LAST_WORKSPACE,
        WORKSPACE_ID,
        WORKSPACE_NAME,
        WORKSPACE_GUID,
        ID_VALUE,
        GUID_VALUE
    );
    private static final java.util.Set<String> CLASS_ALIASES_REQUIRED = java.util.Set.of(
        "cubism.app-controller.class",
        "cubism.project.class",
        "cubism.document.class",
        "cubism.file-content.class",
        "cubism.main-frame.class",
        "cubism.dock-wrapper.class",
        "cubism.workspace.class",
        "cubism.id.class",
        "cubism.guid.class"
    );
    public static final java.util.Set<String> REQUIRED_ALIASES = requiredAliases();

    private static java.util.Set<String> requiredAliases() {
        final java.util.Set<String> aliases = new java.util.HashSet<>(METHOD_ALIASES_USED);
        aliases.addAll(CLASS_ALIASES_REQUIRED);
        return java.util.Set.copyOf(aliases);
    }

    public static java.util.Set<String> methodAliasesUsed() {
        return METHOD_ALIASES_USED;
    }

    private final VerifiedMemberResolver resolver;
    private final String hostVersion;
    private final HostSessionIdentityRegistry identities = new HostSessionIdentityRegistry();

    public VerifiedProjectWorkspaceHostOperations(
        final VerifiedMemberResolver resolver,
        final String hostVersion
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.hostVersion = requireText(hostVersion, "hostVersion");
    }

    @Override
    public String hostVersion() {
        return hostVersion;
    }

    @Override
    public boolean supportsProjectWorkspaceRead() {
        return "5.2.0".equals(hostVersion) || "5.3.02".equals(hostVersion);
    }

    @Override
    public Optional<ProjectSnapshot> activeProject() {
        try {
            final Object appController = resolver.invokeStatic(APP_INSTANCE);
            if (appController == null) {
                return Optional.empty();
            }
            final Object currentDocument = resolver.invoke(CURRENT_DOCUMENT, appController);
            if (currentDocument == null) {
                return Optional.empty();
            }
            final Object project = resolver.invoke(CURRENT_PROJECT, appController);
            if (project == null) {
                return Optional.empty();
            }
            final String displayName = projectDisplayName(currentDocument);
            final List<DocumentSnapshot> documents = documents(resolver.invoke(PROJECT_DOCUMENTS, project));
            return Optional.of(new ProjectSnapshot(
                identities.idFor(project, "project"),
                displayName,
                Optional.empty(),
                documents
            ));
        } catch (VerifiedAccessException exception) {
            if (exception.failureKind() == VerifiedAccessException.FailureKind.RESOLUTION) {
                throw mappingFailure(ProjectWorkspaceAdapter.PROJECT_CAPABILITY_ID);
            }
            throw validationFailure(ProjectWorkspaceAdapter.PROJECT_CAPABILITY_ID);
        } catch (RuntimeException exception) {
            throw validationFailure(ProjectWorkspaceAdapter.PROJECT_CAPABILITY_ID);
        }
    }

    @Override
    public Optional<WorkspaceSnapshot> workspace() {
        try {
            final Object appController = resolver.invokeStatic(APP_INSTANCE);
            if (appController == null) {
                return Optional.empty();
            }
            final Object mainFrame = resolver.invoke(MAIN_FRAME, appController);
            if (mainFrame == null) {
                return Optional.empty();
            }
            final Object dockManager = resolver.invoke(DOCK_MANAGER, mainFrame);
            if (dockManager == null) {
                return Optional.empty();
            }
            final Object workspace = resolver.invoke(LAST_WORKSPACE, dockManager);
            if (workspace == null) {
                return Optional.empty();
            }
            final Object id = resolver.invoke(WORKSPACE_ID, workspace);
            final String idValue = id == null ? "" : text(resolver.invoke(ID_VALUE, id), "");
            final String displayName = text(resolver.invoke(WORKSPACE_NAME, workspace), "Workspace");
            final Object guid = resolver.invoke(WORKSPACE_GUID, workspace);
            final String guidValue = guid == null ? "" : text(resolver.invoke(GUID_VALUE, guid), "");
            if (idValue.isBlank() && guidValue.isBlank()) {
                return Optional.empty();
            }
            final String stableWorkspaceId = idValue.isBlank()
                ? "workspace-" + safeSegment(guidValue)
                : idValue;
            return Optional.of(new WorkspaceSnapshot(
                stableWorkspaceId,
                displayName,
                "layouts/" + safeSegment(stableWorkspaceId),
                List.of()
            ));
        } catch (VerifiedAccessException exception) {
            if (exception.failureKind() == VerifiedAccessException.FailureKind.RESOLUTION) {
                throw mappingFailure(ProjectWorkspaceAdapter.WORKSPACE_CAPABILITY_ID);
            }
            throw validationFailure(ProjectWorkspaceAdapter.WORKSPACE_CAPABILITY_ID);
        } catch (RuntimeException exception) {
            throw validationFailure(ProjectWorkspaceAdapter.WORKSPACE_CAPABILITY_ID);
        }
    }

    private String projectDisplayName(final Object currentDocument) {
        final File currentFile = documentFile(currentDocument);
        if (currentFile != null && !currentFile.getName().isBlank()) {
            return currentFile.getName();
        }
        return "Untitled";
    }

    private File documentFile(final Object document) {
        if (document == null) {
            return null;
        }
        final Object fileContent = resolver.invoke(DOCUMENT_FILE_CONTENT, document);
        return fileContent == null ? null : asFile(resolver.invoke(FILE_CONTENT_FILE, fileContent));
    }

    private List<DocumentSnapshot> documents(final Object rawDocuments) {
        if (!(rawDocuments instanceof Iterable<?> iterable)) {
            return List.of();
        }
        final List<DocumentSnapshot> documents = new ArrayList<>();
        for (Object document : iterable) {
            if (document == null) {
                continue;
            }
            final File file = documentFile(document);
            final String fileName = file == null || file.getName().isBlank()
                ? "untitled"
                : file.getName();
            final String documentId = identities.idFor(document, "document");
            final String relativePath = "documents/" + documentId + "/" + safeSegment(fileName);
            documents.add(new DocumentSnapshot(
                documentId,
                fileName,
                relativePath,
                Optional.<Path>empty(),
                Optional.empty()
            ));
        }
        return List.copyOf(documents);
    }

    private static File asFile(final Object value) {
        return value instanceof File file ? file : null;
    }

    private static String safeSegment(final String source) {
        final String sanitized = source.replaceAll("[^A-Za-z0-9._-]", "-")
            .replaceAll("-+", "-")
            .replaceAll("^[-.]+|[-.]+$", "");
        return sanitized.isBlank() ? "unknown" : sanitized;
    }

    private static String text(final Object value, final String fallback) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return fallback;
    }

    private static AdapterHostException mappingFailure(final String capabilityId) {
        return new AdapterHostException(
            SafeModeDiagnostic.Code.MAPPING_NOT_VERIFIED,
            capabilityId,
            "Verified project/workspace selector could not be resolved at runtime."
        );
    }

    private static AdapterHostException validationFailure(final String capabilityId) {
        return new AdapterHostException(
            SafeModeDiagnostic.Code.VALIDATION_FAILURE,
            capabilityId,
            "Project/workspace host data could not be converted safely."
        );
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
