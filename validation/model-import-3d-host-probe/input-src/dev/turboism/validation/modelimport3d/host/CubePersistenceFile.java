package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.command.*;
import dev.turboism.sdk.ui.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;

/** Typed native SAVE_AS and bounded artifact observation; does not prove model persistence. */
final class CubePersistenceFile {
    static final String REQUEST_ID = "mi3d-cube-persistence-save";
    static final String FILE_NAME = "mi3d-cube-model.cmo3";
    static final long MAX_BYTES = 32L * 1024 * 1024;
    record Artifact(String relativePath, long bytes, String sha256) { }
    private CubePersistenceFile() { }

    static Artifact save(UserFileAccessService files, EditorCommandService commands, Path state,
            Runnable requireCurrentDocument) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("save grant wait must be off EDT");
        Path target = target(state);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("save target already exists");
        var result = files.request(new UserFileRequest(REQUEST_ID, "Save imported cube", List.of("cmo3"),
            UserFileMode.WRITE, UserFileLifetime.ONE_OPERATION)).toCompletableFuture().get(60, TimeUnit.SECONDS);
        if (result.status() != UserFileRequestStatus.GRANTED) throw new IllegalStateException("cube save grant: " + result.status());
        try (var handle = result.handle().orElseThrow()) {
            var task = new FutureTask<EditorCommandResult>(() -> {
                requireCurrentDocument.run();
                return commands.execute(new EditorFileCommandRequest(EditorFileCommand.SAVE_AS, handle,
                    EditorOverwritePolicy.REJECT_EXISTING));
            });
            SwingUtilities.invokeAndWait(task);
            var command = task.get();
            if (command.status() != EditorCommandResult.Status.EXECUTED)
                throw new IllegalStateException("cube SAVE_AS: " + command.status());
        }
        // The host command must have created this exact task-owned file. Never synthesize a CMO3.
        return observe(state);
    }

    static Artifact observe(Path state) throws Exception {
        Path target = target(state);
        var before = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() <= 0 || before.size() > MAX_BYTES)
            throw new IllegalStateException("cube artifact outside file/size contract");
        var digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (var channel = Files.newByteChannel(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.allocate(8192);
            while (true) {
                buffer.clear(); int count = channel.read(buffer); if (count < 0) break;
                total += count;
                if (total > before.size()) throw new IllegalStateException("cube artifact grew while reading");
                buffer.flip(); digest.update(buffer);
            }
        }
        target(state);
        var after = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || total != before.size() || after.size() != before.size()
                || !Objects.equals(before.fileKey(), after.fileKey()) || !before.lastModifiedTime().equals(after.lastModifiedTime()))
            throw new IllegalStateException("cube artifact changed while reading");
        return new Artifact("persistence/" + FILE_NAME, total, HexFormat.of().formatHex(digest.digest()));
    }

    private static Path target(Path state) throws Exception {
        Path root = state.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("missing state directory");
        for (Path p = root; p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IllegalStateException("linked state path");
        Path parent = root.resolve("persistence");
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(parent);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("invalid persistence directory");
        Path target = parent.resolve(FILE_NAME);
        if (Files.isSymbolicLink(target)) throw new IllegalStateException("linked cube artifact");
        return target;
    }
}
