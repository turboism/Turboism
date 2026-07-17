package dev.turboism.userfile;

import dev.turboism.failure.RuntimeFailure;
import dev.turboism.failure.RuntimeFailureDomain;
import dev.turboism.failure.RuntimeFailureSink;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.ui.UserFileAccessService;
import dev.turboism.sdk.ui.UserFileError;
import dev.turboism.sdk.ui.UserFileErrorCode;
import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileHandleState;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileReadResult;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.sdk.ui.UserFileRequestResult;
import dev.turboism.sdk.ui.UserFileRequestStatus;
import dev.turboism.sdk.ui.UserFileWriteResult;
import dev.turboism.task.PluginCompletionFuture;
import dev.turboism.task.RuntimePluginTaskScheduler;
import dev.turboism.cleanup.CleanupEvidenceCollector;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opaque, plugin/session-bound user-file grant service. */
public final class RuntimeUserFileAccessService
    implements UserFileAccessService, AutoCloseable {

    private static final int MAX_OPERATION_BYTES = 8 * 1024 * 1024;

    private final String pluginId;
    private final Object ownerToken = new Object();
    private final Set<String> permissions;
    private final UserFileGrantSource source;
    private final RuntimePluginTaskScheduler tasks;
    private final UserFileIoExecutor io;
    private final CleanupEvidenceCollector cleanupEvidence;
    private final RuntimeFailureSink failureSink;
    private final Object lifecycleLock = new Object();
    private final Set<RuntimeUserFileHandle> grants = new HashSet<>();
    private final Set<PendingRequest> pendingRequests = new HashSet<>();
    private boolean active = true;

    public RuntimeUserFileAccessService(
        final String pluginId,
        final Set<String> permissions,
        final UserFileGrantSource source,
        final RuntimePluginTaskScheduler tasks,
        final DisposableScope scope
    ) {
        this(
            pluginId,
            permissions,
            source,
            tasks,
            scope,
            new CleanupEvidenceCollector(),
            RuntimeFailureSink.noop()
        );
    }

    public RuntimeUserFileAccessService(
        final String pluginId,
        final Set<String> permissions,
        final UserFileGrantSource source,
        final RuntimePluginTaskScheduler tasks,
        final DisposableScope scope,
        final CleanupEvidenceCollector cleanupEvidence
    ) {
        this(
            pluginId,
            permissions,
            source,
            tasks,
            scope,
            cleanupEvidence,
            RuntimeFailureSink.noop()
        );
    }

    public RuntimeUserFileAccessService(
        final String pluginId,
        final Set<String> permissions,
        final UserFileGrantSource source,
        final RuntimePluginTaskScheduler tasks,
        final DisposableScope scope,
        final CleanupEvidenceCollector cleanupEvidence,
        final RuntimeFailureSink failureSink
    ) {
        this.pluginId = requireText(pluginId, "pluginId");
        this.permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        this.source = Objects.requireNonNull(source, "source");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.cleanupEvidence = Objects.requireNonNull(cleanupEvidence, "cleanupEvidence");
        this.failureSink = RuntimeFailureSink.require(failureSink);
        this.io = new UserFileIoExecutor(pluginId, tasks);
        try {
            Objects.requireNonNull(scope, "scope").register(this);
        } catch (RuntimeException exception) {
            io.close();
            throw exception;
        }
    }

    @Override
    public CompletionStage<UserFileRequestResult> request(
        final UserFileRequest request
    ) {
        final UserFileRequest validated = Objects.requireNonNull(request, "request");
        if (!has(PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST)
            || !has(requiredFilePermission(validated.mode()))) {
            return immediate(observe(
                requestDenied(),
                "user-file.request",
                missingRequestPermission(validated.mode())
            ));
        }

        final PendingRequest pending = new PendingRequest(validated);
        synchronized (lifecycleLock) {
            if (!active) {
                return immediate(observe(
                    requestUnavailable(),
                    "user-file.request",
                    null
                ));
            }
            pendingRequests.add(pending);
        }
        try {
            final CompletionStage<UserFileGrantSource.Decision> stage =
                Objects.requireNonNull(source.request(validated), "grant source stage");
            stage.whenComplete(pending::complete);
        } catch (RuntimeException exception) {
            pending.complete(null, exception);
        }
        return pending.completion.stage();
    }

    @Override
    public CompletionStage<UserFileReadResult<String>> readUtf8(
        final UserFileHandle handle,
        final int maxBytes
    ) {
        requireNonNegative(maxBytes, "maxBytes");
        final Authorization authorization = authorize(
            handle,
            UserFileMode.READ,
            PermissionIds.TURBOISM_FILE_READ
        );
        if (authorization.error != null) {
            return immediate(observe(
                readFailure(authorization.error),
                "user-file.readUtf8",
                permissionFor(authorization.error, PermissionIds.TURBOISM_FILE_READ)
            ));
        }
        if (maxBytes > MAX_OPERATION_BYTES) {
            return immediate(observe(
                readFailure(UserFileErrorCode.SIZE_LIMIT_EXCEEDED),
                "user-file.readUtf8",
                null
            ));
        }
        return io.submit(
            () -> observe(readUtf8Now(authorization.target, maxBytes), "user-file.readUtf8", null),
            () -> observe(readFailure(UserFileErrorCode.CANCELED), "user-file.readUtf8", null),
            () -> observe(
                readFailure(UserFileErrorCode.RUNTIME_UNAVAILABLE),
                "user-file.readUtf8",
                null
            ),
            () -> observe(readFailure(UserFileErrorCode.IO_FAILURE), "user-file.readUtf8", null)
        );
    }

    @Override
    public CompletionStage<UserFileReadResult<byte[]>> readBytes(
        final UserFileHandle handle,
        final int maxBytes
    ) {
        requireNonNegative(maxBytes, "maxBytes");
        final Authorization authorization = authorize(
            handle,
            UserFileMode.READ,
            PermissionIds.TURBOISM_FILE_READ
        );
        if (authorization.error != null) {
            return immediate(observe(
                readFailure(authorization.error),
                "user-file.readBytes",
                permissionFor(authorization.error, PermissionIds.TURBOISM_FILE_READ)
            ));
        }
        if (maxBytes > MAX_OPERATION_BYTES) {
            return immediate(observe(
                readFailure(UserFileErrorCode.SIZE_LIMIT_EXCEEDED),
                "user-file.readBytes",
                null
            ));
        }
        return io.submit(
            () -> observe(readBytesNow(authorization.target, maxBytes), "user-file.readBytes", null),
            () -> observe(readFailure(UserFileErrorCode.CANCELED), "user-file.readBytes", null),
            () -> observe(
                readFailure(UserFileErrorCode.RUNTIME_UNAVAILABLE),
                "user-file.readBytes",
                null
            ),
            () -> observe(readFailure(UserFileErrorCode.IO_FAILURE), "user-file.readBytes", null)
        );
    }

    @Override
    public CompletionStage<UserFileWriteResult> writeUtf8Atomic(
        final UserFileHandle handle,
        final String content
    ) {
        Objects.requireNonNull(content, "content");
        return writeBytesAtomic(handle, content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public CompletionStage<UserFileWriteResult> writeBytesAtomic(
        final UserFileHandle handle,
        final byte[] content
    ) {
        final byte[] snapshot = Objects.requireNonNull(content, "content").clone();
        final Authorization authorization = authorize(
            handle,
            UserFileMode.WRITE,
            PermissionIds.TURBOISM_FILE_WRITE
        );
        if (authorization.error != null) {
            return immediate(observe(
                writeFailure(authorization.error),
                "user-file.writeBytesAtomic",
                permissionFor(authorization.error, PermissionIds.TURBOISM_FILE_WRITE)
            ));
        }
        if (snapshot.length > MAX_OPERATION_BYTES) {
            return immediate(observe(
                writeFailure(UserFileErrorCode.SIZE_LIMIT_EXCEEDED),
                "user-file.writeBytesAtomic",
                null
            ));
        }
        return io.submit(
            () -> observe(
                writeBytesNow(authorization.target, snapshot),
                "user-file.writeBytesAtomic",
                null
            ),
            () -> observe(
                writeFailure(UserFileErrorCode.CANCELED),
                "user-file.writeBytesAtomic",
                null
            ),
            () -> observe(
                writeFailure(UserFileErrorCode.RUNTIME_UNAVAILABLE),
                "user-file.writeBytesAtomic",
                null
            ),
            () -> observe(
                writeFailure(UserFileErrorCode.IO_FAILURE),
                "user-file.writeBytesAtomic",
                null
            )
        );
    }

    @Override
    public void close() {
        final List<RuntimeUserFileHandle> toRevoke;
        final List<PendingRequest> toSettle;
        synchronized (lifecycleLock) {
            if (!active) {
                return;
            }
            active = false;
            toRevoke = new ArrayList<>(grants);
            toSettle = new ArrayList<>(pendingRequests);
        }
        toRevoke.forEach(handle -> {
            if (handle.revokeIfActive()) {
                cleanupEvidence.userFileHandleRevoked();
            }
        });
        toSettle.forEach(PendingRequest::unavailable);
        io.close();
        synchronized (lifecycleLock) {
            grants.clear();
            pendingRequests.clear();
        }
    }

    private Authorization authorize(
        final UserFileHandle handle,
        final UserFileMode expectedMode,
        final String permission
    ) {
        if (!(handle instanceof RuntimeUserFileHandle runtimeHandle)) {
            return Authorization.failure(UserFileErrorCode.INVALID_GRANT);
        }
        if (!runtimeHandle.ownedBy(ownerToken)) {
            return Authorization.failure(UserFileErrorCode.FOREIGN_GRANT);
        }
        synchronized (lifecycleLock) {
            if (!active) {
                runtimeHandle.revoke();
            }
        }
        final UserFileHandleState before = runtimeHandle.beginAttempt();
        if (before == UserFileHandleState.REVOKED) {
            return Authorization.failure(UserFileErrorCode.GRANT_REVOKED);
        }
        if (before == UserFileHandleState.CLOSED) {
            return Authorization.failure(UserFileErrorCode.GRANT_EXPIRED);
        }
        if (!has(permission)) {
            return Authorization.failure(UserFileErrorCode.PERMISSION_DENIED);
        }
        if (runtimeHandle.mode() != expectedMode) {
            return Authorization.failure(UserFileErrorCode.MODE_MISMATCH);
        }
        return Authorization.success(runtimeHandle.target());
    }

    private RuntimeUserFileHandle createGrant(
        final UserFileRequest request,
        final Path selected
    ) throws IOException {
        final Path target = validateSelection(request, selected);
        final Path fileName = target.getFileName();
        if (fileName == null) {
            throw new IOException("selected user file has no leaf name");
        }
        return new RuntimeUserFileHandle(
            ownerToken,
            fileName.toString(),
            request.mode(),
            request.lifetime(),
            target
        );
    }

    private static Path validateSelection(
        final UserFileRequest request,
        final Path selected
    ) throws IOException {
        final Path normalized = Objects.requireNonNull(selected, "selected")
            .toAbsolutePath()
            .normalize();
        final Path fileName = normalized.getFileName();
        if (fileName == null || !extensionAllowed(fileName.toString(), request.allowedExtensions())) {
            throw new IOException("selected user file does not match the request");
        }
        if (Files.isSymbolicLink(normalized)) {
            throw new IOException("selected user file must not be a symbolic link");
        }
        if (request.mode() == UserFileMode.READ) {
            if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("selected user file is not readable");
            }
            return normalized.toRealPath();
        }
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("selected user file is not writable");
            }
            return normalized.toRealPath();
        }
        final Path parent = normalized.getParent();
        if (parent == null || Files.isSymbolicLink(parent)
            || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("selected user-file parent is unavailable");
        }
        return parent.toRealPath().resolve(fileName.toString());
    }

    private static boolean extensionAllowed(
        final String fileName,
        final List<String> allowedExtensions
    ) {
        if (allowedExtensions.isEmpty()) {
            return true;
        }
        final String lower = fileName.toLowerCase(Locale.ROOT);
        return allowedExtensions.stream()
            .map(extension -> "." + extension.toLowerCase(Locale.ROOT))
            .anyMatch(lower::endsWith);
    }

    private static UserFileReadResult<String> readUtf8Now(
        final Path target,
        final int maxBytes
    ) {
        final UserFileReadResult<byte[]> bytes = readBytesNow(target, maxBytes);
        if (bytes.error().isPresent()) {
            return new UserFileReadResult<>(Optional.empty(), bytes.error(), false);
        }
        try {
            final String value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.value().orElseThrow()))
                .toString();
            return new UserFileReadResult<>(
                Optional.of(value),
                Optional.empty(),
                bytes.truncated()
            );
        } catch (CharacterCodingException exception) {
            return readFailure(UserFileErrorCode.IO_FAILURE);
        }
    }

    private static UserFileReadResult<byte[]> readBytesNow(
        final Path target,
        final int maxBytes
    ) {
        try {
            if (Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                return readFailure(UserFileErrorCode.IO_FAILURE);
            }
            try (InputStream input = Files.newInputStream(
                target,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
            )) {
                final ByteArrayOutputStream output = new ByteArrayOutputStream(
                    Math.min(maxBytes, 8192)
                );
                final byte[] buffer = new byte[Math.min(8192, Math.max(1, maxBytes + 1))];
                int remaining = maxBytes + 1;
                while (remaining > 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        return readFailure(UserFileErrorCode.CANCELED);
                    }
                    final int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
                    if (read < 0) {
                        break;
                    }
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
                final byte[] raw = output.toByteArray();
                final boolean truncated = raw.length > maxBytes;
                final byte[] value = truncated
                    ? java.util.Arrays.copyOf(raw, maxBytes)
                    : raw;
                return new UserFileReadResult<>(
                    Optional.of(value),
                    Optional.empty(),
                    truncated
                );
            }
        } catch (IOException exception) {
            return readFailure(UserFileErrorCode.IO_FAILURE);
        }
    }

    private UserFileWriteResult writeBytesNow(
        final Path target,
        final byte[] content
    ) {
        Path temporary = null;
        try {
            final Path parent = target.getParent();
            if (parent == null || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !parent.toRealPath().equals(parent.toAbsolutePath().normalize())) {
                return writeFailure(UserFileErrorCode.IO_FAILURE);
            }
            if (Files.isSymbolicLink(target)
                || Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                return writeFailure(UserFileErrorCode.IO_FAILURE);
            }
            temporary = target.resolveSibling(
                "." + target.getFileName() + ".turboism-user-file-"
                    + UUID.randomUUID() + ".tmp"
            );
            try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )) {
                final ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    if (Thread.currentThread().isInterrupted()) {
                        return writeFailure(UserFileErrorCode.CANCELED);
                    }
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
            temporary = null;
            return new UserFileWriteResult(true, Optional.empty());
        } catch (AtomicMoveNotSupportedException exception) {
            return writeFailure(UserFileErrorCode.ATOMIC_REPLACE_UNAVAILABLE);
        } catch (IOException exception) {
            return writeFailure(UserFileErrorCode.IO_FAILURE);
        } finally {
            if (temporary != null) {
                try {
                    if (Files.deleteIfExists(temporary)) {
                        cleanupEvidence.temporaryFileDeleted();
                    }
                } catch (IOException ignored) {
                    cleanupEvidence.cleanupFailed();
                }
            }
        }
    }

    private boolean has(final String permission) {
        return permissions.contains(permission);
    }

    private static String requiredFilePermission(final UserFileMode mode) {
        return mode == UserFileMode.READ
            ? PermissionIds.TURBOISM_FILE_READ
            : PermissionIds.TURBOISM_FILE_WRITE;
    }

    private <T> CompletionStage<T> immediate(final T value) {
        final PluginCompletionFuture<T> completion = new PluginCompletionFuture<>(
            tasks::dispatchContinuation
        );
        tasks.dispatchContinuation(() -> completion.settle(value));
        return completion.stage();
    }

    private UserFileRequestResult observe(
        final UserFileRequestResult result,
        final String operationId,
        final String permissionId
    ) {
        result.error().ifPresent(error -> record(error, operationId, permissionId));
        return result;
    }

    private <T> UserFileReadResult<T> observe(
        final UserFileReadResult<T> result,
        final String operationId,
        final String permissionId
    ) {
        result.error().ifPresent(error -> record(error, operationId, permissionId));
        return result;
    }

    private UserFileWriteResult observe(
        final UserFileWriteResult result,
        final String operationId,
        final String permissionId
    ) {
        result.error().ifPresent(error -> record(error, operationId, permissionId));
        return result;
    }

    private void record(
        final UserFileError error,
        final String operationId,
        final String permissionId
    ) {
        failureSink.record(RuntimeFailureDomain.STORAGE, new RuntimeFailure(
            error.code().name(),
            "ERROR",
            "user-file",
            pluginId,
            operationId,
            permissionId,
            error.message(),
            null,
            1
        ));
    }

    private String missingRequestPermission(final UserFileMode mode) {
        if (!has(PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST)) {
            return PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST;
        }
        return requiredFilePermission(mode);
    }

    private static String permissionFor(
        final UserFileErrorCode code,
        final String expectedPermission
    ) {
        return code == UserFileErrorCode.PERMISSION_DENIED ? expectedPermission : null;
    }

    private static UserFileRequestResult requestDenied() {
        return new UserFileRequestResult(
            UserFileRequestStatus.DENIED,
            Optional.empty(),
            Optional.of(error(UserFileErrorCode.PERMISSION_DENIED))
        );
    }

    private static UserFileRequestResult requestUnavailable() {
        return new UserFileRequestResult(
            UserFileRequestStatus.UNAVAILABLE,
            Optional.empty(),
            Optional.of(error(UserFileErrorCode.RUNTIME_UNAVAILABLE))
        );
    }

    private static <T> UserFileReadResult<T> readFailure(
        final UserFileErrorCode code
    ) {
        return new UserFileReadResult<>(
            Optional.empty(),
            Optional.of(error(code)),
            false
        );
    }

    private static UserFileWriteResult writeFailure(
        final UserFileErrorCode code
    ) {
        return new UserFileWriteResult(false, Optional.of(error(code)));
    }

    private static UserFileError error(final UserFileErrorCode code) {
        return new UserFileError(code, message(code));
    }

    private static String message(final UserFileErrorCode code) {
        return switch (code) {
            case PERMISSION_DENIED -> "User-file permission was denied.";
            case INVALID_GRANT -> "User-file grant is invalid.";
            case MODE_MISMATCH -> "User-file grant mode does not match the operation.";
            case GRANT_EXPIRED -> "User-file grant has expired.";
            case GRANT_REVOKED -> "User-file grant was revoked.";
            case FOREIGN_GRANT -> "User-file grant belongs to another runtime context.";
            case SIZE_LIMIT_EXCEEDED -> "User-file operation exceeds the size limit.";
            case ATOMIC_REPLACE_UNAVAILABLE -> "Atomic user-file replacement is unavailable.";
            case CANCELED -> "User-file operation was canceled.";
            case RUNTIME_UNAVAILABLE -> "User-file runtime is unavailable.";
            case IO_FAILURE -> "User-file operation failed safely.";
        };
    }

    private static int requireNonNegative(final int value, final String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record Authorization(Path target, UserFileErrorCode error) {
        private static Authorization success(final Path target) {
            return new Authorization(Objects.requireNonNull(target, "target"), null);
        }

        private static Authorization failure(final UserFileErrorCode error) {
            return new Authorization(null, Objects.requireNonNull(error, "error"));
        }
    }

    private final class PendingRequest {
        private final UserFileRequest request;
        private final PluginCompletionFuture<UserFileRequestResult> completion;
        private final AtomicBoolean settled = new AtomicBoolean(false);

        private PendingRequest(final UserFileRequest request) {
            this.request = request;
            this.completion = new PluginCompletionFuture<>(tasks::dispatchContinuation);
        }

        private void complete(
            final UserFileGrantSource.Decision decision,
            final Throwable failure
        ) {
            if (failure != null || decision == null) {
                settle(requestUnavailable());
                return;
            }
            if (decision instanceof UserFileGrantSource.Canceled) {
                settle(new UserFileRequestResult(
                    UserFileRequestStatus.CANCELED,
                    Optional.empty(),
                    Optional.empty()
                ));
                return;
            }
            if (decision instanceof UserFileGrantSource.Unavailable) {
                settle(requestUnavailable());
                return;
            }
            final Path path = ((UserFileGrantSource.Selected) decision).path();
            try {
                final RuntimeUserFileHandle handle = createGrant(request, path);
                synchronized (lifecycleLock) {
                    if (!active) {
                        handle.revoke();
                        settle(requestUnavailable());
                        return;
                    }
                    grants.add(handle);
                }
                settle(new UserFileRequestResult(
                    UserFileRequestStatus.GRANTED,
                    Optional.of(handle),
                    Optional.empty()
                ));
            } catch (IOException | RuntimeException exception) {
                settle(requestUnavailable());
            }
        }

        private void unavailable() {
            settle(requestUnavailable());
        }

        private void settle(final UserFileRequestResult result) {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            final UserFileRequestResult observed = observe(
                result,
                "user-file.request",
                null
            );
            synchronized (lifecycleLock) {
                pendingRequests.remove(this);
            }
            tasks.dispatchContinuation(() -> completion.settle(observed));
        }
    }
}
