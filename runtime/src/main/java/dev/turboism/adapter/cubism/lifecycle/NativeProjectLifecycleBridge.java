package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectFileOperation;
import dev.turboism.sdk.cubism.ProjectFileOperationType;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Static bridge called only by verified host bytecode transformers. */
public final class NativeProjectLifecycleBridge {

    private static final AtomicReference<NativeProjectLifecycleBridge> INSTALLED =
        new AtomicReference<>();

    private final ProjectFileLifecycleCoordinator projectFiles;
    private final EditorLifecycleCoordinator editor;
    private final String hostVersion;
    private final ThreadLocal<Deque<FileInvocation>> files =
        ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<Deque<ExitFrame>> exits =
        ThreadLocal.withInitial(ArrayDeque::new);

    public NativeProjectLifecycleBridge(
        final ProjectFileLifecycleCoordinator projectFiles,
        final EditorLifecycleCoordinator editor,
        final String hostVersion
    ) {
        this.projectFiles = Objects.requireNonNull(projectFiles, "projectFiles");
        this.editor = Objects.requireNonNull(editor, "editor");
        this.hostVersion = requireText(hostVersion, "hostVersion");
    }

    public static void install(final NativeProjectLifecycleBridge bridge) {
        if (!INSTALLED.compareAndSet(null, Objects.requireNonNull(bridge, "bridge"))) {
            throw new IllegalStateException("Native project lifecycle bridge is already installed.");
        }
    }

    public static void uninstall(final NativeProjectLifecycleBridge bridge) {
        INSTALLED.compareAndSet(Objects.requireNonNull(bridge, "bridge"), null);
    }

    public static void beginModelOpen(final String displayName, final File file) {
        System.out.println("LIFECYCLE-BRIDGE:method=beginModelOpen kind=MODEL op=OPEN");
        final NativeProjectLifecycleBridge bridge = INSTALLED.get();
        if (bridge == null) return;
        bridge.safeBeginOpen(ProjectContentKind.MODEL, displayName, file, null);
    }

    public static void beginAnimationOpen(final Object animation, final File file) {
        System.out.println("LIFECYCLE-BRIDGE:method=beginAnimationOpen kind=ANIMATION op=OPEN");
        final NativeProjectLifecycleBridge bridge = INSTALLED.get();
        if (bridge == null) return;
        bridge.safeBeginOpen(
            ProjectContentKind.ANIMATION,
            bridge.stringProperty(animation, "getName").orElseGet(() -> fileName(file)),
            file,
            null
        );
    }

    public static void beginContent(
        final Object content,
        final int kindOrdinal,
        final int operationOrdinal
    ) {
        System.out.println("LIFECYCLE-BRIDGE:method=beginContent kind="
            + enumNameOrDash(ProjectContentKind.values(), kindOrdinal)
            + " op=" + enumNameOrDash(ProjectFileOperationType.values(), operationOrdinal));
        final NativeProjectLifecycleBridge bridge = INSTALLED.get();
        if (bridge == null) return;
        bridge.safeBeginExisting(
            content,
            enumValue(ProjectContentKind.values(), kindOrdinal, "kindOrdinal"),
            enumValue(ProjectFileOperationType.values(), operationOrdinal, "operationOrdinal")
        );
    }

    public static void completeObject(final Object content) {
        System.out.println("LIFECYCLE-BRIDGE:method=completeObject kind=- op=-");
        final NativeProjectLifecycleBridge bridge = INSTALLED.get();
        if (bridge == null) return;
        bridge.safeCompleteFile(content, content != null, null);
    }

    public static void completeBoolean(final boolean succeeded) {
        System.out.println("LIFECYCLE-BRIDGE:method=completeBoolean kind=- op=-");
        final NativeProjectLifecycleBridge bridge = INSTALLED.get();
        if (bridge == null) return;
        bridge.safeCompleteFile(null, succeeded, null);
    }

    public static void failedFile(final Throwable failure) {
        System.out.println("LIFECYCLE-BRIDGE:method=failedFile kind=- op=-");
        final NativeProjectLifecycleBridge bridge = INSTALLED.get();
        if (bridge == null) return;
        bridge.safeCompleteFile(null, false, Objects.requireNonNull(failure, "failure"));
    }

    public static void beforeEditorExit() {
        System.out.println("LIFECYCLE-BRIDGE:method=beforeEditorExit kind=- op=-");
        final NativeProjectLifecycleBridge bridge = INSTALLED.get();
        if (bridge == null) return;
        EditorLifecycleCoordinator.ExitInvocation invocation = null;
        try {
            invocation = bridge.editor.beginExit(bridge.hostVersion);
        } catch (Throwable ignored) {
            // Native ingress must fail open when lifecycle state is unavailable.
        }
        bridge.exits.get().push(new ExitFrame(invocation));
    }

    public static void completeEditorExit(final boolean accepted) {
        System.out.println("LIFECYCLE-BRIDGE:method=completeEditorExit kind=- op=-");
        final NativeProjectLifecycleBridge bridge = INSTALLED.get();
        if (bridge == null) return;
        bridge.safeCompleteExit(accepted, null);
    }

    public static void failedEditorExit(final Throwable failure) {
        System.out.println("LIFECYCLE-BRIDGE:method=failedEditorExit kind=- op=-");
        final NativeProjectLifecycleBridge bridge = INSTALLED.get();
        if (bridge == null) return;
        bridge.safeCompleteExit(false, Objects.requireNonNull(failure, "failure"));
    }

    private void safeBeginOpen(
        final ProjectContentKind kind,
        final String displayName,
        final File file,
        final Object subject
    ) {
        try {
            beginOpen(kind, displayName, file, subject);
        } catch (Throwable ignored) {
            files.get().push(FileInvocation.skipped());
        }
    }

    private void beginOpen(
        final ProjectContentKind kind,
        final String displayName,
        final File file,
        final Object subject
    ) {
        final ProjectFileOperationType operation = file == null
            ? ProjectFileOperationType.CREATE
            : ProjectFileOperationType.OPEN;
        final ProjectFileOperation request = new ProjectFileOperation(
            kind,
            operation,
            Optional.empty(),
            normalizedDisplayName(displayName, file),
            Optional.ofNullable(file).map(File::getName)
        );
        files.get().push(new FileInvocation(projectFiles.begin(request), subject, null));
    }

    private void safeBeginExisting(
        final Object content,
        final ProjectContentKind kind,
        final ProjectFileOperationType operation
    ) {
        try {
            beginExisting(content, kind, operation);
        } catch (Throwable ignored) {
            files.get().push(FileInvocation.skipped());
        }
    }

    private void beginExisting(
        final Object content,
        final ProjectContentKind kind,
        final ProjectFileOperationType operation
    ) {
        final ProjectContentSnapshot beforeSnapshot = snapshot(content, kind, null);
        final ProjectFileOperation request = new ProjectFileOperation(
            kind,
            operation,
            Optional.of(beforeSnapshot.contentId()),
            beforeSnapshot.name(),
            fileName(content)
        );
        files.get().push(new FileInvocation(
            projectFiles.begin(request),
            content,
            beforeSnapshot
        ));
    }

    private void safeCompleteFile(
        final Object returnedContent,
        final boolean succeeded,
        final Throwable failure
    ) {
        try {
            completeFile(returnedContent, succeeded, failure);
        } catch (Throwable ignored) {
            // Native completion must never destabilize Cubism.
        }
    }

    private void completeFile(
        final Object returnedContent,
        final boolean succeeded,
        final Throwable failure
    ) {
        final Deque<FileInvocation> stack = files.get();
        final FileInvocation invocation = stack.poll();
        if (stack.isEmpty()) files.remove();
        if (invocation == null || invocation.lifecycle() == null) return;
        ProjectContentSnapshot completionSnapshot = invocation.beforeSnapshot();
        final Object content = returnedContent != null ? returnedContent : invocation.subject();
        if (content != null) {
            try {
                completionSnapshot = snapshot(
                    content,
                    invocation.lifecycle().operation().kind(),
                    invocation.lifecycle().operation()
                );
            } catch (Throwable ignored) {
                // Closing content may already be detached; retain the immutable before snapshot.
            }
        }
        if (completionSnapshot == null) {
            completionSnapshot = placeholder(invocation.lifecycle().operation());
        }
        projectFiles.complete(
            invocation.lifecycle(),
            completionSnapshot,
            succeeded,
            failure
        );
    }

    private void safeCompleteExit(final boolean accepted, final Throwable failure) {
        try {
            completeExit(accepted, failure);
        } catch (Throwable ignored) {
            // Native completion must never destabilize Cubism.
        }
    }

    private void completeExit(final boolean accepted, final Throwable failure) {
        final Deque<ExitFrame> stack = exits.get();
        final ExitFrame frame = stack.poll();
        if (stack.isEmpty()) exits.remove();
        if (frame != null && frame.invocation() != null) {
            editor.completeExit(frame.invocation(), accepted, failure);
        }
    }

    private ProjectContentSnapshot snapshot(
        final Object content,
        final ProjectContentKind kind,
        final ProjectFileOperation fallback
    ) {
        Objects.requireNonNull(content, "content");
        final String id = kind == ProjectContentKind.MODEL || kind == ProjectContentKind.ANIMATION
            ? ProjectContentIdentity.forLifecycleContent(kind, content)
            : kind.name().toLowerCase(java.util.Locale.ROOT)
                + ":" + Integer.toUnsignedString(System.identityHashCode(content), 16);
        final Optional<File> file = fileProperty(content);
        if (kind == ProjectContentKind.MODEL) {
            final String name = stringProperty(content, "getModelName")
                .orElseGet(() -> fallbackName(fallback, file.orElse(null)));
            final List<String> documents = stringProperty(content, "getDocumentUID")
                .map(List::of)
                .orElseGet(List::of);
            return new ProjectContentSnapshot(
                id,
                name,
                kind,
                Optional.empty(),
                documents,
                List.of()
            );
        }
        if (kind == ProjectContentKind.ANIMATION) {
            final Object animation = invoke(content, "getAnimation").orElse(null);
            final String name = stringProperty(animation, "getName")
                .orElseGet(() -> fallbackName(fallback, file.orElse(null)));
            final List<String> documents = new ArrayList<>();
            final Object sceneDocs = invoke(content, "getSceneDocs").orElse(null);
            if (sceneDocs instanceof Iterable<?> iterable) {
                for (Object document : iterable) {
                    stringProperty(document, "getDocumentUID").ifPresent(documents::add);
                }
            }
            return new ProjectContentSnapshot(
                id,
                name,
                kind,
                Optional.empty(),
                documents,
                List.of()
            );
        }
        return new ProjectContentSnapshot(
            id,
            fallbackName(fallback, file.orElse(null)),
            kind,
            Optional.empty(),
            List.of(),
            List.of()
        );
    }

    private static ProjectContentSnapshot placeholder(final ProjectFileOperation operation) {
        return new ProjectContentSnapshot(
            operation.contentId().orElseGet(() -> operation.kind().name().toLowerCase(java.util.Locale.ROOT)
                + ":pending"),
            operation.displayName(),
            operation.kind(),
            Optional.empty(),
            List.of(),
            List.of()
        );
    }

    private Optional<String> fileName(final Object content) {
        return fileProperty(content).map(File::getName);
    }

    private Optional<File> fileProperty(final Object content) {
        return invoke(content, "getFile").filter(File.class::isInstance).map(File.class::cast);
    }

    private Optional<String> stringProperty(final Object target, final String method) {
        return invoke(target, method).filter(String.class::isInstance).map(String.class::cast)
            .filter(value -> !value.isBlank());
    }

    private Optional<Object> invoke(final Object target, final String methodName) {
        if (target == null) return Optional.empty();
        try {
            final Method method = target.getClass().getMethod(methodName);
            return Optional.ofNullable(method.invoke(target));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static String normalizedDisplayName(final String displayName, final File file) {
        if (displayName != null && !displayName.isBlank()) return displayName;
        return fileName(file);
    }

    private static String fallbackName(final ProjectFileOperation fallback, final File file) {
        if (fallback != null && !fallback.displayName().isBlank()) return fallback.displayName();
        return fileName(file);
    }

    private static String fileName(final File file) {
        if (file == null || file.getName().isBlank()) return "Untitled";
        return file.getName();
    }

    private static <T> T enumValue(final T[] values, final int ordinal, final String name) {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException(name + " is out of range");
        }
        return values[ordinal];
    }

    private static String enumNameOrDash(final Enum<?>[] values, final int ordinal) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal].name() : "-";
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private record FileInvocation(
        ProjectFileLifecycleCoordinator.Invocation lifecycle,
        Object subject,
        ProjectContentSnapshot beforeSnapshot
    ) {
        private static FileInvocation skipped() {
            return new FileInvocation(null, null, null);
        }
    }

    private record ExitFrame(EditorLifecycleCoordinator.ExitInvocation invocation) {
    }
}
