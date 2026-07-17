package dev.turboism.storage;

import dev.turboism.sdk.storage.StorageErrorCode;
import dev.turboism.sdk.storage.StoragePath;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Executes bounded recursive deletion while the caller holds the storage mutation lock. */
final class BoundedStorageDeleter {

    private final DeleteLimits limits;
    private final ExistingPathVerifier verifier;

    BoundedStorageDeleter(
        final DeleteLimits limits,
        final ExistingPathVerifier verifier
    ) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    Result delete(final StoragePath logicalPath, final Path target) {
        final DeleteProgress progress = new DeleteProgress();
        try {
            final DeleteBudget budget = new DeleteBudget(limits);
            budget.includeRoot(logicalPath);
            deleteRecursively(logicalPath, target, 0, progress, budget);
            return Result.success();
        } catch (DeleteFault failure) {
            return Result.failed(progress.changed, failure.path, failure.code);
        }
    }

    private void deleteRecursively(
        final StoragePath logicalPath,
        final Path target,
        final int depth,
        final DeleteProgress progress,
        final DeleteBudget budget
    ) throws DeleteFault {
        try {
            checkCanceled(logicalPath);
            budget.enter(logicalPath, depth);
            if (Files.isSymbolicLink(target)) {
                throw new DeleteFault(logicalPath, StorageErrorCode.LINK_ESCAPE);
            }
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                for (DeleteChild child : deleteChildren(target, logicalPath, budget)) {
                    deleteRecursively(
                        child.logicalPath,
                        child.target,
                        depth + 1,
                        progress,
                        budget
                    );
                }
            }
            budget.beforeDelete(logicalPath);
            Files.delete(target);
            progress.changed = true;
        } catch (DeleteFault failure) {
            throw failure;
        } catch (IOException failure) {
            throw new DeleteFault(logicalPath, StorageErrorCode.IO_FAILURE, failure);
        }
    }

    private List<DeleteChild> deleteChildren(
        final Path directory,
        final StoragePath logicalDirectory,
        final DeleteBudget budget
    ) throws IOException, DeleteFault {
        final List<DeleteChild> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            try {
                for (Path child : stream) {
                    checkCanceled(logicalDirectory);
                    final StoragePath logicalChild = new StoragePath(
                        logicalDirectory.root(),
                        logicalDirectory.relativePath() + "/" + child.getFileName()
                    );
                    budget.discover(logicalDirectory);
                    if (Files.isSymbolicLink(child)) {
                        throw new DeleteFault(logicalChild, StorageErrorCode.LINK_ESCAPE);
                    }
                    final StorageErrorCode verificationFailure = verifier.verify(
                        logicalChild,
                        child
                    );
                    if (verificationFailure != null) {
                        throw new DeleteFault(logicalChild, verificationFailure);
                    }
                    children.add(new DeleteChild(logicalChild, child));
                }
            } catch (DirectoryIteratorException failure) {
                throw failure.getCause();
            }
        }
        children.sort(Comparator.comparing(
            child -> child.target.getFileName().toString()
        ));
        return children;
    }

    private static void checkCanceled(final StoragePath path) throws DeleteFault {
        if (Thread.currentThread().isInterrupted()) {
            throw new DeleteFault(path, StorageErrorCode.CANCELED);
        }
    }

    @FunctionalInterface
    interface ExistingPathVerifier {
        StorageErrorCode verify(StoragePath logicalPath, Path target) throws IOException;
    }

    record Result(
        boolean changed,
        StoragePath failurePath,
        StorageErrorCode failureCode
    ) {
        Result {
            if ((failurePath == null) != (failureCode == null)) {
                throw new IllegalArgumentException("failure path and code must be supplied together");
            }
        }

        static Result success() {
            return new Result(true, null, null);
        }

        static Result failed(
            final boolean changed,
            final StoragePath failurePath,
            final StorageErrorCode failureCode
        ) {
            return new Result(changed, failurePath, failureCode);
        }

        boolean isSuccessful() {
            return failureCode == null;
        }
    }

    private static final class DeleteFault extends Exception {
        private final StoragePath path;
        private final StorageErrorCode code;

        private DeleteFault(
            final StoragePath path,
            final StorageErrorCode code
        ) {
            this(path, code, null);
        }

        private DeleteFault(
            final StoragePath path,
            final StorageErrorCode code,
            final Throwable cause
        ) {
            super(cause);
            this.path = Objects.requireNonNull(path, "path");
            this.code = Objects.requireNonNull(code, "code");
        }
    }

    private static final class DeleteBudget {
        private final int maxDepth;
        private long entriesRemaining;
        private long workRemaining;

        private DeleteBudget(final DeleteLimits limits) {
            maxDepth = limits.maxDepth();
            entriesRemaining = limits.maxEntries();
            workRemaining = limits.maxWork();
        }

        private void includeRoot(final StoragePath path) throws DeleteFault {
            consumeEntry(path);
        }

        private void enter(
            final StoragePath path,
            final int depth
        ) throws DeleteFault {
            if (depth > maxDepth) {
                throw limit(path);
            }
            consumeWork(depth + 1L, path);
        }

        private void discover(final StoragePath directory) throws DeleteFault {
            consumeEntry(directory);
            consumeWork(1L, directory);
        }

        private void beforeDelete(final StoragePath path) throws DeleteFault {
            consumeWork(1L, path);
        }

        private void consumeEntry(final StoragePath path) throws DeleteFault {
            if (entriesRemaining < 1L) {
                throw limit(path);
            }
            entriesRemaining -= 1L;
        }

        private void consumeWork(
            final long amount,
            final StoragePath path
        ) throws DeleteFault {
            if (workRemaining < amount) {
                throw limit(path);
            }
            workRemaining -= amount;
        }

        private DeleteFault limit(final StoragePath path) {
            return new DeleteFault(path, StorageErrorCode.SIZE_LIMIT_EXCEEDED);
        }
    }

    private static final class DeleteChild {
        private final StoragePath logicalPath;
        private final Path target;

        private DeleteChild(
            final StoragePath logicalPath,
            final Path target
        ) {
            this.logicalPath = logicalPath;
            this.target = target;
        }
    }

    private static final class DeleteProgress {
        private boolean changed;
    }
}
