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
public final class CubePersistenceFileTest {
    static int checks;
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    interface Work { void run() throws Exception; }
    static void rejected(Work work) throws Exception {
        try { work.run(); } catch (Exception expected) { checks++; return; }
        throw new AssertionError("expected rejection");
    }
    public static void main(String[] args) throws Exception {
        persistenceStateChecks();
        restoredHistoryChecks();
        Path root = Files.createDirectory(Path.of(args[0]));
        var good = new World(root.resolve("good"));
        var result = good.save();
        check(result.bytes() == good.bytes.length && result.sha256().equals(HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(good.bytes))), "actual bytes hashed");
        check(result.relativePath().equals("persistence/" + CubePersistenceFile.FILE_NAME), "pinned artifact path");
        check(good.calls == 1 && good.checked == 1 && good.handle.closed, "one command and grant closed");
        check(result.equals(CubePersistenceFile.observe(good.state)), "stable repeated observation");
        rejected(good::save); check(good.requests == 1, "existing file rejected before grant");
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
        try (var file = new java.io.RandomAccessFile(parent.resolve(CubePersistenceFile.FILE_NAME).toFile(), "rw")) {
            file.setLength(CubePersistenceFile.MAX_BYTES + 1);
        }
        rejected(() -> CubePersistenceFile.observe(oversized.state));
        var edt = new FutureTask<Void>(() -> { rejected(() -> new World(root.resolve("edt")).save()); return null; });
        SwingUtilities.invokeAndWait(edt); edt.get();
        System.out.println("CubePersistenceFileTest: " + checks + " checks PASS; native command substituted; hostExecuted=false");
    }
    static Map<String,Object> observed(String session, long generation, String field, Object changed) {
        var input = new LinkedHashMap<String,Object>(Map.of("modelId", session, "generation", generation,
            "kind", "MODEL_IMAGE", "meshId", "face", "imageId", "material", "atlasId", ""));
        var mesh = new LinkedHashMap<String,Object>(Map.of("id", "face", "snapshotSha256", "all-authored-fields",
            "culling", true, "visible", true, "parent", "cube", "input", input));
        var state = new LinkedHashMap<String,Object>(Map.of("model", session, "meshes", List.of(mesh),
            "canvas", List.of(100,100), "parameters", List.of("yaw","pitch"), "parts", List.of("cube"),
            "rawImages", List.of("raw"), "imageGroups", List.of("material")));
        if (field.startsWith("input.")) input.put(field.substring(6),changed);
        else if (field.startsWith("mesh.")) mesh.put(field.substring(5),changed);
        else if (!field.isEmpty()) state.put(field,changed);
        return state;
    }
    static void persistenceStateChecks() {
        var original = observed("first-process", 1, "", "");
        var baseline = WholeImportProbe.persistenceState(original);
        check(baseline.equals(WholeImportProbe.persistenceState(observed("new-process", 42, "", ""))),
            "session identity excluded from durable comparison");
        check(original.containsKey("model") && !original.containsKey("schema"), "observation is not mutated");
        for (String field : List.of("canvas", "parameters", "parts", "rawImages", "imageGroups",
                "mesh.id", "mesh.snapshotSha256", "mesh.culling", "mesh.visible", "mesh.parent",
                "input.kind", "input.meshId", "input.imageId", "input.atlasId")) {
            check(!baseline.equals(WholeImportProbe.persistenceState(observed("first-process", 1, field, "changed"))),
                "durable comparison detects " + field);
        }
        check(!baseline.equals(WholeImportProbe.persistenceState(observed("first-process", 1, "mesh.input", null))),
            "missing texture binding detected");
    }
    static HistorySnapshot history(int position,long revision,String document,String... ids) {
        var entries=new ArrayList<HistoryEntry>();
        for(int i=0;i<ids.length;i++) entries.add(new HistoryEntry(i,ids[i],true,Optional.empty(),Optional.of(new HistoryEntryId(ids[i])),Optional.empty()));
        return new HistorySnapshot(HistorySnapshot.Availability.AVAILABLE,1,revision,position,entries,position>0,position<entries.size(),document,"manager");
    }
    static void restoredHistoryChecks() {
        var before=history(1,1,"document","import");
        var restored=history(1,3,"document","import","pose");
        check(CubePersistenceRender.restoredHistory(before,restored),"native Undo may retain redo tail");
        check(!CubePersistenceRender.sameHistory(before,restored),"render-only reads cannot add redo entries");
        check(!CubePersistenceRender.restoredHistory(before,history(2,3,"document","import","pose")),"unrestored position rejected");
        check(!CubePersistenceRender.restoredHistory(before,history(1,3,"document","foreign","pose")),"active prefix identity protected");
        check(!CubePersistenceRender.restoredHistory(before,history(1,3,"foreign","import","pose")),"document binding protected");
        check(!CubePersistenceRender.restoredHistory(before,history(1,0,"document","import","pose")),"backward revision rejected");
        check(!CubePersistenceRender.restoredHistory(HistorySnapshot.unavailable(),HistorySnapshot.unavailable()),"unavailable history rejected");
    }
    static final class Handle implements UserFileHandle {
        boolean closed;
        public String id() { return "cube-grant"; }
        public String displayName() { return CubePersistenceFile.FILE_NAME; }
        public UserFileMode mode() { return UserFileMode.WRITE; }
        public UserFileLifetime lifetime() { return UserFileLifetime.ONE_OPERATION; }
        public UserFileHandleState state() { return closed ? UserFileHandleState.CLOSED : UserFileHandleState.ACTIVE; }
        public void revoke() { closed = true; }
        public void close() { closed = true; }
    }
    static final class World {
        final Path state; final Handle handle = new Handle();
        boolean grant = true, execute = true, current = true;
        int requests, calls, checked;
        byte[] bytes = "synthetic command output, not a native CMO3".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        World(Path state) throws Exception { this.state = Files.createDirectory(state); }
        CubePersistenceFile.Artifact save() throws Exception {
            var files = (UserFileAccessService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{UserFileAccessService.class}, (o,m,a) -> {
                if (!m.getName().equals("request")) throw new AssertionError("unexpected file service call");
                requests++; check(!SwingUtilities.isEventDispatchThread(), "grant off EDT");
                var request = (UserFileRequest) a[0];
                check(request.id().equals(CubePersistenceFile.REQUEST_ID) && request.mode() == UserFileMode.WRITE
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
                if (execute) Files.write(state.resolve("persistence").resolve(CubePersistenceFile.FILE_NAME), bytes, StandardOpenOption.CREATE_NEW);
                return new EditorCommandResult(execute ? EditorCommandResult.Status.EXECUTED : EditorCommandResult.Status.FAILED, request.commandId());
            });
            return CubePersistenceFile.save(files, commands, state, () -> {
                checked++; check(SwingUtilities.isEventDispatchThread(), "current document check on EDT");
                if (!current) throw new IllegalStateException("document switched");
            });
        }
    }
}
