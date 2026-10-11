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
final class ApplePersistenceFile {
    static final String REQUEST_ID = "mi3d-apple-menu-save";
    static final String FILE_NAME = "mi3d-apple-model.cmo3";
    static final long MAX_BYTES = 256L * 1024 * 1024;
    enum Profile {
        APPLE(REQUEST_ID, FILE_NAME, MAX_BYTES),
        CAT("mi3d-cat-menu-save", "mi3d-cat-model.cmo3", MAX_BYTES),
        INTERPOLATION("mi3d-interpolation-save", "mi3d-interpolation-model.cmo3", 64L * 1024 * 1024),
        XYZ("mi3d-xyz-menu-save", "mi3d-xyz-model.cmo3", MAX_BYTES),
        SURFACE("mi3d-surface-menu-save", "mi3d-surface-model.cmo3", 64L * 1024 * 1024);
        final String requestId, fileName;
        final long maximumBytes;
        Profile(String requestId, String fileName, long maximumBytes) {
            this.requestId=requestId;this.fileName=fileName;this.maximumBytes=maximumBytes;
        }
    }
    record Artifact(String relativePath, long bytes, String sha256) { }
    private ApplePersistenceFile() { }

    static Artifact save(UserFileAccessService files, EditorCommandService commands, Path state,
            Runnable requireCurrentDocument) throws Exception {
        return save(files, commands, state, requireCurrentDocument, Profile.APPLE);
    }

    static Artifact save(UserFileAccessService files, EditorCommandService commands, Path state,
            Runnable requireCurrentDocument, Profile profile) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("save grant wait must be off EDT");
        Path target = target(state, profile);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("save target already exists");
        var result = files.request(new UserFileRequest(profile.requestId, "Save imported model", List.of("cmo3"),
            UserFileMode.WRITE, UserFileLifetime.ONE_OPERATION)).toCompletableFuture().get(60, TimeUnit.SECONDS);
        if (result.status() != UserFileRequestStatus.GRANTED) throw new IllegalStateException("apple save grant: " + result.status());
        try (var handle = result.handle().orElseThrow()) {
            var task = new FutureTask<EditorCommandResult>(() -> {
                requireCurrentDocument.run();
                return commands.execute(new EditorFileCommandRequest(EditorFileCommand.SAVE_AS, handle,
                    EditorOverwritePolicy.REJECT_EXISTING));
            });
            SwingUtilities.invokeAndWait(task);
            var command = task.get();
            if (command.status() != EditorCommandResult.Status.EXECUTED)
                throw new IllegalStateException("apple SAVE_AS: " + command.status());
        }
        // The host command must have created this exact task-owned file. Never synthesize a CMO3.
        return observe(state, profile);
    }

    static Artifact observe(Path state) throws Exception {
        return observe(state, Profile.APPLE);
    }

    static Artifact observe(Path state, Profile profile) throws Exception {
        Path target = target(state, profile);
        var before = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() <= 0 || before.size() > profile.maximumBytes)
            throw new IllegalStateException("apple artifact outside file/size contract");
        var digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (var channel = Files.newByteChannel(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.allocate(8192);
            while (true) {
                buffer.clear(); int count = channel.read(buffer); if (count < 0) break;
                total += count;
                if (total > before.size()) throw new IllegalStateException("apple artifact grew while reading");
                buffer.flip(); digest.update(buffer);
            }
        }
        target(state, profile);
        var after = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || total != before.size() || after.size() != before.size()
                || !Objects.equals(before.fileKey(), after.fileKey()) || !before.lastModifiedTime().equals(after.lastModifiedTime()))
            throw new IllegalStateException("apple artifact changed while reading");
        return new Artifact("persistence/" + profile.fileName, total, HexFormat.of().formatHex(digest.digest()));
    }

    private static Path target(Path state, Profile profile) throws Exception {
        Path root = state.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("missing state directory");
        for (Path p = root; p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IllegalStateException("linked state path");
        Path parent = root.resolve("persistence");
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(parent);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("invalid persistence directory");
        Path target = parent.resolve(profile.fileName);
        if (Files.isSymbolicLink(target)) throw new IllegalStateException("linked apple artifact");
        return target;
    }
}
