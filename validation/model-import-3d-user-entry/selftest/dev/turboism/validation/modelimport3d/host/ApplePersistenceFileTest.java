package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.command.*;
import dev.turboism.sdk.cubism.history.*;
import dev.turboism.sdk.ui.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;

/** Real command orchestration and file observation; native SAVE_AS is a substitute. */
public final class ApplePersistenceFileTest {
    static int checks;
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    interface Work { void run() throws Exception; }
    static void rejected(Work work) throws Exception {
        try { work.run(); } catch (Exception expected) { checks++; return; }
        throw new AssertionError("expected rejection");
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectory(Path.of(args[0]));
        var good = new World(root.resolve("good"));
        var result = good.save();
        check(result.bytes() == good.bytes.length && result.sha256().equals(HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(good.bytes))), "actual bytes hashed");
        check(result.relativePath().equals("persistence/" + ApplePersistenceFile.FILE_NAME), "pinned artifact path");
        check(good.calls == 1 && good.checked == 1 && good.handle.closed, "one command and grant closed");
        check(result.equals(ApplePersistenceFile.observe(good.state)), "stable repeated observation");
        rejected(good::save); check(good.requests == 1, "existing file rejected before grant");
        var xyz=new World(root.resolve("xyz"));xyz.profile=ApplePersistenceFile.Profile.XYZ;
        var xyzResult=xyz.save();
        check(xyzResult.relativePath().equals("persistence/mi3d-xyz-model.cmo3") && xyz.calls==1 && xyz.handle.closed,"XYZ fixed target and one native command");
        check(xyzResult.equals(ApplePersistenceFile.observe(xyz.state,xyz.profile)),"XYZ unchanged artifact observation");
        rejected(xyz::save);check(xyz.requests==1,"XYZ existing target rejected before grant");
        var denied = new World(root.resolve("denied")); denied.grant = false;
        rejected(denied::save); check(denied.calls == 0, "no native command without grant");
        var failed = new World(root.resolve("failed")); failed.execute = false;
        rejected(failed::save); check(failed.handle.closed && failed.calls == 1, "failed command closes handle");
        var stale = new World(root.resolve("stale")); stale.current = false;
        rejected(stale::save); check(stale.calls == 0 && stale.handle.closed, "document rechecked after asynchronous grant");
        var empty = new World(root.resolve("empty")); empty.bytes = new byte[0]; rejected(empty::save);
        var linked = new World(root.resolve("linked"));
        Files.createSymbolicLink(linked.state.resolve("persistence"), good.state.resolve("persistence").toAbsolutePath());
        rejected(linked::save); check(linked.requests == 0, "linked directory rejected before grant");
        var oversized = new World(root.resolve("oversized"));
        Path parent = Files.createDirectory(oversized.state.resolve("persistence"));
        try (var file = new java.io.RandomAccessFile(parent.resolve(ApplePersistenceFile.FILE_NAME).toFile(), "rw")) {
            file.setLength(ApplePersistenceFile.MAX_BYTES + 1);
        }
        rejected(() -> ApplePersistenceFile.observe(oversized.state));
        var edt = new FutureTask<Void>(() -> { rejected(() -> new World(root.resolve("edt")).save()); return null; });
        SwingUtilities.invokeAndWait(edt); edt.get();
        System.out.println("ApplePersistenceFileTest: " + checks + " checks PASS; native command substituted; hostExecuted=false");
    }
    static final class Handle implements UserFileHandle {
        boolean closed;
        public String id() { return "apple-grant"; }
        public String displayName() { return ApplePersistenceFile.FILE_NAME; }
        public UserFileMode mode() { return UserFileMode.WRITE; }
        public UserFileLifetime lifetime() { return UserFileLifetime.ONE_OPERATION; }
        public UserFileHandleState state() { return closed ? UserFileHandleState.CLOSED : UserFileHandleState.ACTIVE; }
        public void revoke() { closed = true; }
        public void close() { closed = true; }
    }
    static final class World {
        ApplePersistenceFile.Profile profile=ApplePersistenceFile.Profile.APPLE;
        final Path state; final Handle handle = new Handle();
        boolean grant = true, execute = true, current = true;
        int requests, calls, checked;
        byte[] bytes = "synthetic command output, not a native CMO3".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        World(Path state) throws Exception { this.state = Files.createDirectory(state); }
        ApplePersistenceFile.Artifact save() throws Exception {
            var files = (UserFileAccessService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{UserFileAccessService.class}, (o,m,a) -> {
                if (!m.getName().equals("request")) throw new AssertionError("unexpected file service call");
                requests++; check(!SwingUtilities.isEventDispatchThread(), "grant off EDT");
                var request = (UserFileRequest) a[0];
                check(request.id().equals(profile.requestId) && request.mode() == UserFileMode.WRITE
                    && request.lifetime() == UserFileLifetime.ONE_OPERATION && request.allowedExtensions().equals(List.of("cmo3")), "exact grant shape");
                return CompletableFuture.completedFuture(grant
                    ? new UserFileRequestResult(UserFileRequestStatus.GRANTED, Optional.of(handle), Optional.empty())
                    : new UserFileRequestResult(UserFileRequestStatus.CANCELED, Optional.empty(), Optional.empty()));
            });
            var commands = (EditorCommandService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{EditorCommandService.class}, (o,m,a) -> {
                calls++; check(SwingUtilities.isEventDispatchThread(), "command on EDT");
                var request = (EditorFileCommandRequest) a[0];
                check(request.command() == EditorFileCommand.SAVE_AS && request.file() == handle
                    && request.overwritePolicy() == EditorOverwritePolicy.REJECT_EXISTING, "typed non-overwriting SAVE_AS");
                if (execute) Files.write(state.resolve("persistence").resolve(profile.fileName), bytes, StandardOpenOption.CREATE_NEW);
                return new EditorCommandResult(execute ? EditorCommandResult.Status.EXECUTED : EditorCommandResult.Status.FAILED, request.commandId());
            });
            return ApplePersistenceFile.save(files, commands, state, () -> {
                checked++; check(SwingUtilities.isEventDispatchThread(), "current document check on EDT");
                if (!current) throw new IllegalStateException("document switched");
            },profile);
        }
    }
}
