package dev.turboism.adapter.cubism;

import dev.turboism.adapter.ui.AdapterHostException;
import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.mapping.verification.VerifiedAccessException;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.adapter.cubism.lifecycle.ProjectContentIdentity;
import dev.turboism.sdk.cubism.AnimationSnapshot;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectResourceSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.ResourceKind;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Project/workspace HostOperations backed only by exact verified selectors and reviewed host types. */
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

    private static final String MODELING_DOCUMENT_CLASS =
        "com.live2d.cubism.doc.modeling.CModelingDocument";
    private static final String ANIMATION_SCENE_DOCUMENT_CLASS =
        "com.live2d.cubism.doc.animation.CSceneDocument";
    private static final String ANIMATION_CONTENT_CLASS =
        "com.live2d.cubism.doc.animation.CAnimationFileContent";
    private static final String GAME_DATA_DOCUMENT_CLASS =
        "com.live2d.cubism.doc.gameData.CGameDataDocument";
    private static final String PHYSICS_SETTINGS_DOCUMENT_CLASS =
        "com.live2d.cubism.doc.gameData.physics.CPhysicsSettingsDocument";
    private static final String IMAGE_DOCUMENT_CLASS = "com.live2d.cubism.doc.resources.g";
    private static final String IMAGE_PROJECT_ENTRY_CLASS =
        "com.live2d.cubism.doc.resources.CImageDocumentProjectEntry";
    private static final String LAYERED_IMAGE_CLASS =
        "com.live2d.cubism.doc.resources.CLayeredImage";
    private static final String FILE_CONTENT_CLASS = "com.live2d.cubism.doc.IFileContent";

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

    /**
     * The method selector aliases this slice invokes on the host.
     *
     * <p>Used by mapping verification to prove that every host member this class reaches for is
     * covered by reviewed evidence. Class-level aliases are not included; {@code REQUIRED_ALIASES} is
     * the union of both.
     *
     * @return the immutable alias set; never null and never modified at runtime
     */
    public static java.util.Set<String> methodAliasesUsed() {
        return METHOD_ALIASES_USED;
    }

    private final VerifiedMemberResolver resolver;
    private final String hostVersion;
    private final HostSessionIdentityRegistry identities = new HostSessionIdentityRegistry();
    private final Optional<Class<?>> modelingDocumentType;
    private final Optional<Class<?>> animationSceneDocumentType;
    private final Optional<Class<?>> animationContentType;
    private final Optional<Class<?>> gameDataDocumentType;
    private final Optional<Class<?>> physicsSettingsDocumentType;
    private final Optional<Class<?>> imageDocumentType;
    private final Optional<Class<?>> imageProjectEntryType;
    private final Optional<Class<?>> layeredImageType;
    private final Optional<Class<?>> fileContentType;

    public VerifiedProjectWorkspaceHostOperations(
        final VerifiedMemberResolver resolver,
        final String hostVersion
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.hostVersion = requireText(hostVersion, "hostVersion");
        final ClassLoader hostClassLoader = resolver.hostClassLoader();
        this.modelingDocumentType = hostClass(hostClassLoader, MODELING_DOCUMENT_CLASS);
        this.animationSceneDocumentType = hostClass(
            hostClassLoader,
            ANIMATION_SCENE_DOCUMENT_CLASS
        );
        this.animationContentType = hostClass(hostClassLoader, ANIMATION_CONTENT_CLASS);
        this.gameDataDocumentType = hostClass(hostClassLoader, GAME_DATA_DOCUMENT_CLASS);
        this.physicsSettingsDocumentType = hostClass(
            hostClassLoader,
            PHYSICS_SETTINGS_DOCUMENT_CLASS
        );
        this.imageDocumentType = hostClass(hostClassLoader, IMAGE_DOCUMENT_CLASS);
        this.imageProjectEntryType = hostClass(hostClassLoader, IMAGE_PROJECT_ENTRY_CLASS);
        this.layeredImageType = hostClass(hostClassLoader, LAYERED_IMAGE_CLASS);
        this.fileContentType = hostClass(hostClassLoader, FILE_CONTENT_CLASS);
    }

    @Override
    public String hostVersion() {
        return hostVersion;
    }

    @Override
    public boolean supportsProjectWorkspaceRead() {
        return "5.2.03".equals(hostVersion) || "5.3.02".equals(hostVersion);
    }

    @Override
    public Optional<ProjectSnapshot> activeProject() {
        try {
            final Object appController = resolver.invokeStatic(APP_INSTANCE);
            if (appController == null) return Optional.empty();
            final Object project = resolver.invoke(CURRENT_PROJECT, appController);
            if (project == null) return Optional.empty();
            final String projectId = identities.idFor(project, "project");
            final Object currentDocument = resolver.invoke(CURRENT_DOCUMENT, appController);
            final List<DocumentSnapshot> documents = new ArrayList<>(documents(
                resolver.invoke(PROJECT_DOCUMENTS, project)
            ));
            if (currentDocument != null) {
                final DocumentSnapshot active = document(currentDocument);
                if (documents.stream().noneMatch(existing ->
                    existing.documentId().equals(active.documentId()))) {
                    documents.add(active);
                }
            }
            final List<ProjectContentSnapshot> contents = contents(
                invokePublic(project, "getChildren").orElse(List.of()),
                documents
            );
            if (documents.isEmpty() && contents.isEmpty()) return Optional.empty();
            return Optional.of(new ProjectSnapshot(
                projectId,
                projectDisplayName(currentDocument, documents),
                Optional.empty(),
                documents,
                contents
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
    public Optional<DocumentSnapshot> activeDocument() {
        try {
            final Object appController = resolver.invokeStatic(APP_INSTANCE);
            if (appController == null) return Optional.empty();
            final Object currentDocument = resolver.invoke(CURRENT_DOCUMENT, appController);
            return currentDocument == null
                ? Optional.empty()
                : Optional.of(document(currentDocument));
        } catch (VerifiedAccessException exception) {
            if (exception.failureKind() == VerifiedAccessException.FailureKind.RESOLUTION) {
                throw mappingFailure(ProjectWorkspaceAdapter.DOCUMENT_CAPABILITY_ID);
            }
            throw validationFailure(ProjectWorkspaceAdapter.DOCUMENT_CAPABILITY_ID);
        } catch (RuntimeException exception) {
            throw validationFailure(ProjectWorkspaceAdapter.DOCUMENT_CAPABILITY_ID);
        }
    }

    @Override
    public Optional<WorkspaceSnapshot> workspace() {
        try {
            final Object appController = resolver.invokeStatic(APP_INSTANCE);
            if (appController == null) return Optional.empty();
            final Object mainFrame = resolver.invoke(MAIN_FRAME, appController);
            if (mainFrame == null) return Optional.empty();
            final Object dockManager = resolver.invoke(DOCK_MANAGER, mainFrame);
            if (dockManager == null) return Optional.empty();
            final Object workspace = resolver.invoke(LAST_WORKSPACE, dockManager);
            if (workspace == null) return Optional.empty();
            final Object id = resolver.invoke(WORKSPACE_ID, workspace);
            final String idValue = id == null ? "" : text(resolver.invoke(ID_VALUE, id), "");
            final String displayName = text(resolver.invoke(WORKSPACE_NAME, workspace), "Workspace");
            final Object guid = resolver.invoke(WORKSPACE_GUID, workspace);
            final String guidValue = guid == null ? "" : text(resolver.invoke(GUID_VALUE, guid), "");
            if (idValue.isBlank() && guidValue.isBlank()) return Optional.empty();
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

    private String projectDisplayName(
        final Object currentDocument,
        final List<DocumentSnapshot> documents
    ) {
        if (currentDocument != null) {
            final File currentFile = documentFile(currentDocument);
            return currentFile != null && !currentFile.getName().isBlank()
                ? currentFile.getName()
                : "Untitled";
        }
        return documents.isEmpty() ? "Untitled" : documents.get(0).name();
    }

    private List<DocumentSnapshot> documents(final Object rawDocuments) {
        if (!(rawDocuments instanceof Iterable<?> iterable)) return List.of();
        final List<DocumentSnapshot> documents = new ArrayList<>();
        for (Object document : iterable) {
            if (document != null) documents.add(document(document));
        }
        return List.copyOf(documents);
    }

    private DocumentSnapshot document(final Object document) {
        final DocumentKind kind = documentKind(document);
        final Object contentOwner;
        final File file;
        if (kind == DocumentKind.IMAGE) {
            contentOwner = invokePublic(document, "a").orElse(null);
            file = imageSourceFile(contentOwner);
        } else {
            contentOwner = resolver.invoke(DOCUMENT_FILE_CONTENT, document);
            file = contentOwner == null
                ? null
                : asFile(resolver.invoke(FILE_CONTENT_FILE, contentOwner));
        }
        final String documentId = identities.idFor(document, "document");
        final String fallbackName = file == null || file.getName().isBlank()
            ? "untitled"
            : file.getName();
        final Optional<String> contentId = Optional.ofNullable(contentOwner)
            .map(content -> switch (kind) {
                case MODEL -> ProjectContentIdentity.forLifecycleContent(ProjectContentKind.MODEL, content);
                case ANIMATION_SCENE -> ProjectContentIdentity.forLifecycleContent(
                    ProjectContentKind.ANIMATION, content
                );
                default -> identities.idFor(content, "content");
            });
        final Optional<ModelSnapshot> model = kind == DocumentKind.MODEL
            ? Optional.of(model(document, fallbackName))
            : Optional.empty();
        final Optional<AnimationSnapshot> animation = kind == DocumentKind.ANIMATION_SCENE
            ? Optional.of(animationForScene(document, contentOwner, fallbackName))
            : Optional.empty();
        final String displayName = switch (kind) {
            case MODEL -> model.orElseThrow().name();
            case ANIMATION_SCENE -> animation.orElseThrow().name();
            case IMAGE -> stringProperty(contentOwner, "getName").orElse(fallbackName);
            default -> fallbackName;
        };
        return new DocumentSnapshot(
            documentId,
            displayName,
            "documents/" + documentId + "/" + safeSegment(fallbackName),
            Optional.<Path>empty(),
            model,
            kind,
            contentId,
            animation
        );
    }

    private ModelSnapshot model(final Object modelingDocument, final String fallbackName) {
        final Object modelSource = invokePublic(modelingDocument, "getModelSource")
            .orElse(modelingDocument);
        final String name = stringProperty(modelingDocument, "getModelName")
            .orElse(fallbackName);
        return new ModelSnapshot(
            identities.idFor(modelSource, "model"),
            name,
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private AnimationSnapshot animationForScene(
        final Object sceneDocument,
        final Object resolvedFileContent,
        final String fallbackName
    ) {
        final Object animationContent = isInstance(animationContentType, resolvedFileContent)
            ? resolvedFileContent
            : invokePublic(sceneDocument, "getAnimationContent").orElse(resolvedFileContent);
        if (animationContent == null || !isInstance(animationContentType, animationContent)) {
            throw new IllegalStateException("Animation scene has no reviewed animation content.");
        }
        return animation(animationContent, sceneDocument, fallbackName);
    }

    private AnimationSnapshot animation(
        final Object animationContent,
        final Object activeScene,
        final String fallbackName
    ) {
        final Object animation = invokePublic(animationContent, "getAnimation")
            .orElse(animationContent);
        final String name = stringProperty(animation, "getName").orElse(fallbackName);
        final List<String> sceneDocumentIds = new ArrayList<>();
        final Object rawScenes = invokePublic(animationContent, "getSceneDocs").orElse(List.of());
        if (rawScenes instanceof Iterable<?> iterable) {
            for (Object scene : iterable) {
                if (scene != null) sceneDocumentIds.add(identities.idFor(scene, "document"));
            }
        }
        final Object currentScene = activeScene != null
            ? activeScene
            : invokePublic(animationContent, "getCurrentSceneDoc").orElse(null);
        final Optional<String> activeSceneId = Optional.ofNullable(currentScene)
            .map(scene -> identities.idFor(scene, "document"));
        activeSceneId.ifPresent(id -> {
            if (!sceneDocumentIds.contains(id)) sceneDocumentIds.add(id);
        });
        return new AnimationSnapshot(
            identities.idFor(animation, "animation"),
            name,
            Optional.empty(),
            sceneDocumentIds,
            activeSceneId
        );
    }

    private List<ProjectContentSnapshot> contents(
        final Object rawContents,
        final List<DocumentSnapshot> documents
    ) {
        if (!(rawContents instanceof Iterable<?> iterable)) return List.of();
        final List<ProjectContentSnapshot> contents = new ArrayList<>();
        final java.util.Set<Object> visited = java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<>()
        );
        collectContents(iterable, contents, visited);
        return contents.stream().map(content -> {
            final List<String> documentIds = new ArrayList<>(content.documentIds());
            documents.stream()
                .filter(document -> document.contentId().filter(
                    content.contentId()::equals
                ).isPresent())
                .map(DocumentSnapshot::documentId)
                .filter(id -> !documentIds.contains(id))
                .forEach(documentIds::add);
            return new ProjectContentSnapshot(
                content.contentId(),
                content.name(),
                content.kind(),
                content.filePath(),
                documentIds,
                content.resources()
            );
        }).toList();
    }

    private void collectContents(
        final Iterable<?> entries,
        final List<ProjectContentSnapshot> contents,
        final java.util.Set<Object> visited
    ) {
        for (Object entry : entries) {
            if (entry == null || !visited.add(entry)) continue;
            if (isProjectContent(entry)) {
                contents.add(content(entry));
                continue;
            }
            final Object children = invokePublic(entry, "getChildren").orElse(List.of());
            if (children instanceof Iterable<?> nested) {
                collectContents(nested, contents, visited);
            }
        }
    }

    private boolean isProjectContent(final Object content) {
        return isInstance(fileContentType, content)
            || isInstance(imageProjectEntryType, content)
            || isInstance(layeredImageType, content);
    }

    private ProjectContentSnapshot content(final Object content) {
        if (isInstance(imageProjectEntryType, content) || isInstance(layeredImageType, content)) {
            return imageContent(content);
        }
        final ProjectContentKind kind = contentKind(content);
        final File file = asFile(resolver.invoke(FILE_CONTENT_FILE, content));
        final String fallbackName = file == null || file.getName().isBlank()
            ? "Untitled"
            : file.getName();
        final String name = switch (kind) {
            case MODEL -> stringProperty(content, "getModelName").orElse(fallbackName);
            case ANIMATION -> invokePublic(content, "getAnimation")
                .flatMap(animation -> stringProperty(animation, "getName"))
                .orElse(fallbackName);
            default -> fallbackName;
        };
        final List<String> documentIds = new ArrayList<>();
        final Object rawDocuments = invokePublic(content, "getFileContentDocs").orElse(List.of());
        if (rawDocuments instanceof Iterable<?> iterable) {
            for (Object document : iterable) {
                if (document != null) documentIds.add(identities.idFor(document, "document"));
            }
        }
        final String contentId = kind == ProjectContentKind.MODEL || kind == ProjectContentKind.ANIMATION
            ? ProjectContentIdentity.forLifecycleContent(kind, content)
            : identities.idFor(content, "content");
        return new ProjectContentSnapshot(
            contentId,
            name,
            kind,
            Optional.empty(),
            documentIds,
            List.of()
        );
    }

    private ProjectContentSnapshot imageContent(final Object projectEntry) {
        final Object openedDocument = invokePublic(projectEntry, "getOpenedImageDocument")
            .orElse(null);
        final Object layeredImage = isInstance(layeredImageType, projectEntry)
            ? projectEntry
            : invokePublic(openedDocument, "a").orElse(null);
        final Object identityOwner = layeredImage == null ? projectEntry : layeredImage;
        final File file = Optional.ofNullable(imageSourceFile(projectEntry))
            .orElseGet(() -> imageSourceFile(layeredImage));
        final String fallbackName = file == null || file.getName().isBlank()
            ? "Untitled Image"
            : file.getName();
        final String name = stringProperty(layeredImage, "getName").orElse(fallbackName);
        final String contentId = identities.idFor(identityOwner, "content");
        final List<String> documentIds = openedDocument == null
            ? List.of()
            : List.of(identities.idFor(openedDocument, "document"));
        final List<ProjectResourceSnapshot> resources = file == null
            ? List.of()
            : List.of(new ProjectResourceSnapshot(
                contentId + "-source",
                file.getName(),
                resourceKind(file),
                Optional.of(file.getName())
            ));
        return new ProjectContentSnapshot(
            contentId,
            name,
            ProjectContentKind.IMAGE,
            Optional.empty(),
            documentIds,
            resources
        );
    }

    private DocumentKind documentKind(final Object document) {
        if (isInstance(modelingDocumentType, document)) return DocumentKind.MODEL;
        if (isInstance(animationSceneDocumentType, document)) return DocumentKind.ANIMATION_SCENE;
        if (isInstance(imageDocumentType, document)) return DocumentKind.IMAGE;
        if (isInstance(physicsSettingsDocumentType, document)) return DocumentKind.PHYSICS_SETTINGS;
        if (isInstance(gameDataDocumentType, document)) return DocumentKind.GAME_DATA;
        return DocumentKind.OTHER;
    }

    private ProjectContentKind contentKind(final Object content) {
        if (isInstance(modelingDocumentType, content)) return ProjectContentKind.MODEL;
        if (isInstance(animationContentType, content)) return ProjectContentKind.ANIMATION;
        if (isInstance(imageProjectEntryType, content) || isInstance(layeredImageType, content)) {
            return ProjectContentKind.IMAGE;
        }
        if (isInstance(gameDataDocumentType, content)) return ProjectContentKind.GAME_DATA;
        return ProjectContentKind.OTHER;
    }

    private File documentFile(final Object document) {
        if (document == null) return null;
        if (documentKind(document) == DocumentKind.IMAGE) {
            return imageSourceFile(invokePublic(document, "a").orElse(null));
        }
        final Object fileContent = resolver.invoke(DOCUMENT_FILE_CONTENT, document);
        return fileContent == null ? null : asFile(resolver.invoke(FILE_CONTENT_FILE, fileContent));
    }

    private File imageSourceFile(final Object source) {
        return invokePublic(source, "getPsdFile")
            .filter(File.class::isInstance)
            .map(File.class::cast)
            .orElse(null);
    }

    private static ResourceKind resourceKind(final File file) {
        final String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".psd") ? ResourceKind.PSD : ResourceKind.IMAGE;
    }

    private Optional<String> stringProperty(final Object target, final String methodName) {
        return invokePublic(target, methodName)
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .filter(value -> !value.isBlank());
    }

    private Optional<Object> invokePublic(final Object target, final String methodName) {
        if (target == null) return Optional.empty();
        try {
            final Method method = target.getClass().getMethod(methodName);
            return Optional.ofNullable(method.invoke(target));
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            return Optional.empty();
        } catch (InvocationTargetException exception) {
            final Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(
                "Reviewed Cubism public method failed: " + methodName,
                cause
            );
        }
    }

    private static Optional<Class<?>> hostClass(
        final ClassLoader classLoader,
        final String className
    ) {
        try {
            return Optional.of(Class.forName(className, false, classLoader));
        } catch (ClassNotFoundException exception) {
            return Optional.empty();
        }
    }

    private static boolean isInstance(final Optional<Class<?>> type, final Object value) {
        return value != null && type.filter(candidate -> candidate.isInstance(value)).isPresent();
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
        if (value instanceof String text && !text.isBlank()) return text;
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
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
