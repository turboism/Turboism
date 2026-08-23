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

    /**
     * Publishes the bridge instance the transformed host bytecode calls into. Exactly one bridge may be
     * installed at a time; the previous one must be uninstalled first.
     *
     * @param bridge the bridge to make current
     * @throws NullPointerException when {@code bridge} is null
     * @throws IllegalStateException when a bridge is already installed
     */
    public static void install(final NativeProjectLifecycleBridge bridge) {
        if (!INSTALLED.compareAndSet(null, Objects.requireNonNull(bridge, "bridge"))) {
            throw new IllegalStateException("Native project lifecycle bridge is already installed.");
        }
    }

    /**
     * Clears the installed bridge only if {@code bridge} is still the current one, so a stale uninstall
     * cannot detach a newer bridge. Instrumented call sites then become no-ops.
     *
     * @param bridge the bridge expected to be current
     * @throws NullPointerException when {@code bridge} is null
     */
    public static void uninstall(final NativeProjectLifecycleBridge bridge) {
        INSTALLED.compareAndSet(Objects.requireNonNull(bridge, "bridge"), null);
    }

    /**
     * Ingress for the host's model open call, injected at method entry. Opens a project-file lifecycle
     * invocation of kind {@code MODEL} typed {@code OPEN}, or {@code CREATE} when no file is supplied.
     * Called on the host thread performing the open; no-op when no bridge is installed, and any failure
     * inside the bridge is swallowed so the host open always proceeds.
     *
     * @param displayName host-supplied document name; blank or null falls back to the file name
     * @param file the file being opened, or null for a newly created document
     */
    public static void beginModelOpen(final String displayName, final File file) {
        System.out.println("LIFECYCLE-BRIDGE:method=beginModelOpen kind=MODEL op=OPEN");
        final NativeProjectLifecycleBridge bridge = INSTALLED.get();
        if (bridge == null) return;
        bridge.safeBeginOpen(ProjectContentKind.MODEL, displayName, file, null);
    }

    /**
     * Ingress for the host's animation open call, injected at method entry. Opens a project-file
     * lifecycle invocation of kind {@code ANIMATION}, naming it from the animation object when it
     * exposes a name and otherwise from the file. Fails open exactly like the model variant.
     *
     * @param animation the host animation object being opened, inspected reflectively for its name
     * @param file the file being opened, or null for a newly created animation
     */
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

    /**
     * Ingress for save and close calls on already-open content, injected at method entry. Captures a
     * before snapshot of the content and opens a lifecycle invocation for it. Ordinals are passed
     * rather than enum references so the injected bytecode carries no SDK constant-pool dependency; an
     * out-of-range ordinal is treated as a bridge failure and the invocation is skipped rather than
     * disturbing the host call.
     *
     * @param content the open host content object
     * @param kindOrdinal ordinal into {@code ProjectContentKind}
     * @param operationOrdinal ordinal into {@code ProjectFileOperationType}
     */
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

    /**
     * Normal-return completion for host methods that return the content object. The operation counts as
     * successful when the returned reference is non-null, and the returned object is preferred over the
     * entry-time subject when taking the after snapshot.
     *
     * @param content the value the instrumented host method is returning, possibly null
     */
    public static void completeObject(final Object content) {
        System.out.println("LIFECYCLE-BRIDGE:method=completeObject kind=- op=-");
        final NativeProjectLifecycleBridge bridge = INSTALLED.get();
        if (bridge == null) return;
        bridge.safeCompleteFile(content, content != null, null);
    }

    /**
     * Normal-return completion for host methods that report success as a boolean, such as save and
     * close. The after snapshot is taken from the content captured at entry.
     *
     * @param succeeded the boolean the instrumented host method is returning
     */
    public static void completeBoolean(final boolean succeeded) {
        System.out.println("LIFECYCLE-BRIDGE:method=completeBoolean kind=- op=-");
        final NativeProjectLifecycleBridge bridge = INSTALLED.get();
        if (bridge == null) return;
        bridge.safeCompleteFile(null, succeeded, null);
    }

    /**
     * Exceptional-return completion for an open project-file invocation: publishes the operation as
     * failed together with the throwable the host is propagating. Does not suppress that throwable.
     *
     * @param failure the exception leaving the instrumented host method
     * @throws NullPointerException when {@code failure} is null and a bridge is installed
     */
    public static void failedFile(final Throwable failure) {
        System.out.println("LIFECYCLE-BRIDGE:method=failedFile kind=- op=-");
        final NativeProjectLifecycleBridge bridge = INSTALLED.get();
        if (bridge == null) return;
        bridge.safeCompleteFile(null, false, Objects.requireNonNull(failure, "failure"));
    }

    /**
     * Ingress for the host's exit command, injected at method entry. Runs the synchronous
     * {@code beforeEditorExit} phase and pushes the resulting invocation onto this thread's exit stack.
     * A failure to begin leaves an empty frame, so the matching completion becomes a no-op and the host
     * exit is never blocked by plugin state.
     */
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

    /**
     * Normal-return completion for the host's exit command. Publishes the exiting and after-exit
     * callbacks synchronously, because the process may terminate as soon as the host method returns.
     *
     * @param accepted whether the host actually accepted the exit; a cancelled exit publishes only the
     *     after phase
     */
    public static void completeEditorExit(final boolean accepted) {
        System.out.println("LIFECYCLE-BRIDGE:method=completeEditorExit kind=- op=-");
        final NativeProjectLifecycleBridge bridge = INSTALLED.get();
        if (bridge == null) return;
        bridge.safeCompleteExit(accepted, null);
    }

    /**
     * Exceptional-return completion for the host's exit command: publishes a rejected exit carrying the
     * failure's class name. Does not suppress the throwable.
     *
     * @param failure the exception leaving the host exit command
     * @throws NullPointerException when {@code failure} is null and a bridge is installed
     */
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
