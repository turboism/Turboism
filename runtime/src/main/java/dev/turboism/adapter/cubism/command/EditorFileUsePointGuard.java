package dev.turboism.adapter.cubism.command;

import dev.turboism.sdk.cubism.command.EditorOverwritePolicy;
import dev.turboism.sdk.ui.UserFileMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Use-point filesystem revalidation for resolved editor file commands. The runtime resolver
 * already verified grant ownership, lifetime, mode, and one-operation consumption; this guard
 * repeats the proportionate canonical/ownership-of-path checks immediately before the host
 * operation to shrink the remaining TOCTOU window. It never opens a chooser and never follows
 * symlinks; any non-regular or replaced target fails closed.
 */
final class EditorFileUsePointGuard {

    private EditorFileUsePointGuard() {
    }

    static Result admit(final ResolvedEditorFileCommand command) {
        Objects.requireNonNull(command, "command");
        final Path file = command.file();
        if (command.command().mode() == UserFileMode.READ) {
            return admitRead(file);
        }
        return admitWrite(file, command.overwritePolicy());
    }

    private static Result admitRead(final Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return Result.reject("target is not a regular file");
            }
            if (!file.toRealPath().equals(file.toAbsolutePath().normalize())) {
                return Result.reject("target path is not canonical (symlink or indirect path)");
            }
            return Result.allow();
        } catch (IOException exception) {
            return Result.reject("target cannot be revalidated: " + exception.getClass().getSimpleName());
        }
    }

    private static Result admitWrite(final Path file, final EditorOverwritePolicy overwritePolicy) {
        final Path parent = file.getParent();
        try {
            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                return Result.reject("parent directory is missing or not a directory");
            }
            if (!parent.toRealPath().equals(parent.toAbsolutePath().normalize())) {
                return Result.reject("parent path is not canonical (symlink or indirect path)");
            }
            final boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
            if (exists) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    return Result.reject("existing target is not a regular file");
                }
                if (!file.toRealPath().equals(file.toAbsolutePath().normalize())) {
                    return Result.reject("existing target path is not canonical (symlink or indirect path)");
                }
                if (overwritePolicy == EditorOverwritePolicy.REJECT_EXISTING) {
                    return Result.reject("existing target and overwrite policy rejects replacement");
                }
            }
            return Result.allow();
        } catch (IOException exception) {
            return Result.reject("target cannot be revalidated: " + exception.getClass().getSimpleName());
        }
    }

    record Result(boolean allowed, String reason) {
        static Result allow() {
            return new Result(true, "");
        }

        static Result reject(final String reason) {
            return new Result(false, reason);
        }
    }
}
