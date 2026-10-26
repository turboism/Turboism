package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.json.Json;
import dev.turboism.sdk.plugin.PluginContext;
import java.awt.Frame;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/** Task-only close/read verifier. The external client must use production MCP to reopen. */
public final class McpAuthorizedOpenProbe {
    public static final String PREPARE = "mcp-fresh-open-prepare.json";
    public static final String CLOSED = "mcp-fresh-open-closed.json";
    public static final String VERIFY = "mcp-fresh-open-verify.json";
    public static final String RESULT = "mcp-fresh-open-result.json";
    private final PluginContext context;
    private final Path root;
    private final boolean persistence;
    private final String prepareName, closedName, verifyName, resultName;
    private McpGeometryRollbackProbe.Request baselineRequest;
    private List<Object> baselineGeometry;
    private List<ParameterBinding> baselineBindings;
    private Object savedDocument;
    private String outputHash;
    private boolean baselineAttempted, baselineReady, restoreClosed, restoreAttempted, restored;
    public static final String BASELINE = "mcp-production-save-baseline.json";
    public static final String BASELINE_RESULT = "mcp-production-save-baseline-result.json";
    public static final String SAVED_PREPARE = "mcp-production-save-prepare.json";
    public static final String SAVED_CLOSED = "mcp-production-save-closed.json";
    public static final String SAVED_VERIFY = "mcp-production-save-verify.json";
    public static final String SAVED_RESULT = "mcp-production-save-result.json";
    public static final String RESTORE_CLOSE = "mcp-production-save-restore-close.json";
    public static final String RESTORE_CLOSED = "mcp-production-save-restore-closed.json";
    public static final String RESTORE_VERIFY = "mcp-production-save-restore-verify.json";
    public static final String RESTORE_RESULT = "mcp-production-save-restore-result.json";
    private final String runId = System.getProperty("turboism.validation.runId", "");
    private final Path fixture = Path.of(System.getProperty("turboism.validation.fixture", ""));
    private McpGeometryRollbackProbe.Request request;
    private Object app, originalDocument;
    private List<Object> geometry;
    private List<ParameterBinding> bindings;
    private String fixtureHash;
    private boolean prepared, closed, verified;

    public McpAuthorizedOpenProbe(PluginContext context, Path root) {
        this(context, root, false);
    }

    public McpAuthorizedOpenProbe(PluginContext context, Path root, boolean persistence) {
        this.context = context;
        this.root = root;
        this.persistence = persistence;
        prepareName = persistence ? SAVED_PREPARE : PREPARE;
        closedName = persistence ? SAVED_CLOSED : CLOSED;
        verifyName = persistence ? SAVED_VERIFY : VERIFY;
        resultName = persistence ? SAVED_RESULT : RESULT;
    }

    public static Path authorizedOutput(Path home, Path fixture, String runId) throws Exception {
        McpGeometryRollbackProbe.requireTaskIdentity(home, fixture, runId);
        Path state = home.resolve("state");
        Path directory = state.resolve("authorized-save");
        for (Path path : List.of(state, directory)) {
            check(!Files.isSymbolicLink(path), "Symlinked authorized output parent");
            check(
                    !Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(path),
                    "Invalid authorized output parent");
        }
        Path target = directory.resolve(runId + "-saved.cmo3");
        check(!Files.isSymbolicLink(target), "Symlinked authorized output");
        return target;
    }

    public static Path authorizedNewOutput(Path home, Path fixture, String runId) throws Exception {
        Path target = authorizedOutput(home, fixture, runId);
        check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), "Authorized output must be new");
        return target;
    }

    private Path loadedPath() throws Exception {
        return persistence ? authorizedOutput(root.getParent(), fixture, runId) : fixture;
    }

    public void poll() {
        if (persistence) pollPersistenceStages();
        if (!prepared
                && (!persistence || baselineReady)
                && Files.isRegularFile(root.resolve(prepareName), LinkOption.NOFOLLOW_LINKS)) {
            prepared = true; // Even unknown outcomes are terminal; never repeat a close.
            var report = report();
            try {
                check("5303".equals(System.getProperty("turboism.validation.hostVersion")), "Exact host unavailable");
                McpGeometryRollbackProbe.requireTaskIdentity(root.getParent(), fixture, runId);
                request = McpGeometryRollbackProbe.parse(read(prepareName), runId);
                if (!persistence) fixtureHash = fileHash(fixture);
                else {
                    check(fixtureHash.equals(fileHash(fixture)), "SAVE_AS changed original fixture");
                    Path output = loadedPath();
                    check(
                            Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)
                                    && Files.size(output) > 0
                                    && Files.size(output) <= 128L * 1024 * 1024,
                            "Saved output unavailable or oversized");
                    outputHash = fileHash(output);
                }
                edt(() -> {
                    app = resolveApp();
                    originalDocument = current();
                    if (persistence) requireOwnedFile(originalDocument);
                    else requireFile(originalDocument, fixture);
                    var document = context.cubism().activeDocument().orElseThrow();
                    check(
                            document.documentId().equals(request.documentId())
                                    && document.model().orElseThrow().modelId().equals(request.modelId()),
                            "Document scope changed");
                    geometry = geometry();
                    bindings = bindings();
                    if (persistence)
                        checkPersistenceDelta(
                                baselineGeometry,
                                geometry,
                                baselineBindings,
                                bindings,
                                Boolean.getBoolean("turboism.validation.headPersistence"));
                    check(
                            Boolean.TRUE.equals(originalDocument
                                    .getClass()
                                    .getMethod("closeFile", boolean.class, boolean.class)
                                    .invoke(originalDocument, false, false)),
                            "Owned close rejected");
                    return null;
                });
                awaitEmpty(originalDocument);
                check(fixtureHash.equals(fileHash(fixture)), "Closing changed fixture bytes");
                closed = true;
                report.put("status", "CLOSED");
                if (persistence) report.put("savedFileSha256", outputHash);
                report.put("noActiveDocument", true);
                report.put("fixtureSha256", fixtureHash);
                report.put("beforeGeometryHashes", hashes(geometry));
                report.put("beforeBindingsHash", hash(bindings.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (Exception failure) {
                report.put("exceptionClass", failure.getClass().getSimpleName());
                context.logger().error("Task-only preparation for production OPEN failed", failure);
            }
            publish(closedName, report);
        }
        if (closed && !verified && Files.isRegularFile(root.resolve(verifyName), LinkOption.NOFOLLOW_LINKS)) {
            verified = true;
            var report = report();
            try {
                check(read(verifyName).equals(Map.of("runId", runId)), "Foreign verification request");
                McpGeometryRollbackProbe.requireTaskIdentity(root.getParent(), fixture, runId);
                edt(() -> {
                    Object document = current();
                    requireFile(document, loadedPath());
                    var observedGeometry = geometry();
                    var observedBindings = bindings();
                    checkReopened(originalDocument, document, geometry, observedGeometry, bindings, observedBindings);
                    check(context.cubism().activeModel().isPresent(), "Model not ready");
                    savedDocument = document;
                    report.put("afterGeometryHashes", hashes(observedGeometry));
                    report.put(
                            "afterBindingsHash",
                            hash(observedBindings.toString().getBytes(StandardCharsets.UTF_8)));
                    return null;
                });
                check(fixtureHash.equals(fileHash(fixture)), "Opening changed fixture bytes");
                if (persistence) {
                    check(outputHash.equals(fileHash(loadedPath())), "Saved output changed during reopen");
                    report.put("savedFileSha256", outputHash);
                }
                report.put("fixtureSha256", fixtureHash);
                report.put("beforeGeometryHashes", hashes(geometry));
                report.put("beforeBindingsHash", hash(bindings.toString().getBytes(StandardCharsets.UTF_8)));
                report.put("freshNativeDocument", true);
                report.put("taskFileIdentityVerified", true);
                report.put("fixtureUnchanged", true);
                report.put("geometryKinds", 3);
                report.put("bindingsEqual", true);
                report.put("status", "PASS");
            } catch (Exception failure) {
                report.put("exceptionClass", failure.getClass().getSimpleName());
                context.logger().error("Production OPEN fresh document verification failed", failure);
            }
            publish(resultName, report);
        }
    }

    private void awaitEmpty(Object closedDocument) throws Exception {
        for (int attempt = 0; attempt < 120; attempt++) {
            boolean empty = edt(() -> {
                Object active = current();
                check(active == null || active == closedDocument, "Foreign active document after close");
                return active == null
                        && context.cubism().activeDocument().isEmpty()
                        && context.cubism().activeModel().isEmpty();
            });
            if (empty) return;
            Thread.sleep(250);
        }
        throw new IllegalStateException("Task document remains open");
    }

    private void pollPersistenceStages() {
        if (!baselineAttempted && Files.isRegularFile(root.resolve(BASELINE), LinkOption.NOFOLLOW_LINKS)) {
            baselineAttempted = true;
            var report = report();
            try {
                check("5303".equals(System.getProperty("turboism.validation.hostVersion")), "Exact host unavailable");
                authorizedNewOutput(root.getParent(), fixture, runId);
                baselineRequest = McpGeometryRollbackProbe.parse(read(BASELINE), runId);
                fixtureHash = fileHash(fixture);
                edt(() -> {
                    app = resolveApp();
                    requireFile(current(), fixture);
                    var document = context.cubism().activeDocument().orElseThrow();
                    check(
                            document.documentId().equals(baselineRequest.documentId())
                                    && document.model().orElseThrow().modelId().equals(baselineRequest.modelId()),
                            "Baseline scope changed");
                    baselineGeometry = geometry(baselineRequest);
                    baselineBindings = bindings();
                    return null;
                });
                report.put("geometryHashes", hashes(baselineGeometry));
                report.put("bindingsHash", hash(baselineBindings.toString().getBytes(StandardCharsets.UTF_8)));
                report.put("fixtureSha256", fixtureHash);
                report.put("status", "BASELINE");
                baselineReady = true;
            } catch (Exception failure) {
                report.put("exceptionClass", failure.getClass().getSimpleName());
                context.logger().error("Production persistence baseline failed", failure);
            }
            publish(BASELINE_RESULT, report);
        }
        if (verified
                && savedDocument != null
                && !restoreAttempted
                && Files.isRegularFile(root.resolve(RESTORE_CLOSE), LinkOption.NOFOLLOW_LINKS)) {
            restoreAttempted = true;
            var report = report();
            try {
                check(read(RESTORE_CLOSE).equals(Map.of("runId", runId)), "Foreign restore close request");
                edt(() -> {
                    check(current() == savedDocument, "Refusing to close a different saved document");
                    requireFile(savedDocument, loadedPath());
                    check(
                            geometry().equals(geometry) && bindings().equals(bindings),
                            "Saved document changed before close");
                    check(
                            Boolean.TRUE.equals(savedDocument
                                    .getClass()
                                    .getMethod("closeFile", boolean.class, boolean.class)
                                    .invoke(savedDocument, false, false)),
                            "Saved document close rejected");
                    return null;
                });
                awaitEmpty(savedDocument);
                check(
                        outputHash.equals(fileHash(loadedPath())) && fixtureHash.equals(fileHash(fixture)),
                        "Files changed before restore");
                report.put("status", "RESTORE_CLOSED");
                restoreClosed = true;
            } catch (Exception failure) {
                report.put("exceptionClass", failure.getClass().getSimpleName());
                context.logger().error("Production persistence owned close failed", failure);
            }
            publish(RESTORE_CLOSED, report);
        }
        if (restoreClosed
                && !restored
                && Files.isRegularFile(root.resolve(RESTORE_VERIFY), LinkOption.NOFOLLOW_LINKS)) {
            restored = true;
            var report = report();
            try {
                check(read(RESTORE_VERIFY).equals(Map.of("runId", runId)), "Foreign restore verification");
                edt(() -> {
                    requireFile(current(), fixture);
                    check(
                            geometry(baselineRequest).equals(baselineGeometry)
                                    && bindings().equals(baselineBindings),
                            "Original geometry or bindings not restored");
                    return null;
                });
                check(
                        fixtureHash.equals(fileHash(fixture)) && outputHash.equals(fileHash(loadedPath())),
                        "Files changed on restoration");
                report.put("fixtureSha256", fixtureHash);
                report.put("savedFileSha256", outputHash);
                report.put("restoredGeometryHashes", hashes(baselineGeometry));
                report.put(
                        "restoredBindingsHash", hash(baselineBindings.toString().getBytes(StandardCharsets.UTF_8)));
                report.put("originalRestored", true);
                report.put("status", "PASS");
            } catch (Exception failure) {
                report.put("exceptionClass", failure.getClass().getSimpleName());
                context.logger().error("Production persistence restoration verification failed", failure);
            }
            publish(RESTORE_RESULT, report);
        }
    }

    static void checkPersistenceDelta(
            List<?> originalGeometry,
            List<?> changedGeometry,
            List<?> originalBindings,
            List<?> changedBindings,
            boolean preserveBindings) {
        check(!originalGeometry.equals(changedGeometry), "Persistence must exercise changed geometry");
        check(
                preserveBindings == originalBindings.equals(changedBindings),
                preserveBindings
                        ? "Existing head bindings must be preserved"
                        : "Persistence must exercise changed bindings");
    }

    static void checkReopened(
            Object oldDoc,
            Object newDoc,
            List<?> before,
            List<?> after,
            List<?> beforeBindings,
            List<?> afterBindings) {
        check(oldDoc != null && newDoc != null && oldDoc != newDoc, "Not a fresh native document");
        check(before.size() == 3 && before.equals(after), "Complete stored geometry differs");
        check(beforeBindings.equals(afterBindings), "Complete parameter bindings differ");
    }

    private Object resolveApp() throws Exception {
        var loaders = new LinkedHashSet<ClassLoader>();
        for (ClassLoader loader = getClass().getClassLoader(); loader != null; loader = loader.getParent())
            loaders.add(loader);
        loaders.add(ClassLoader.getSystemClassLoader());
        for (Frame frame : Frame.getFrames()) loaders.add(frame.getClass().getClassLoader());
        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            Class<?> type;
            try {
                type = Class.forName("com.live2d.cubism.CEAppCtrl", false, loader);
            } catch (ClassNotFoundException absent) {
                continue;
            }
            Path jar = Path.of(
                    type.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(fileHash(jar).equals(McpTexturePersistenceProbe.expectedJarHash("5303")), "Native artifact mismatch");
            return type.getMethod("access$get_instance$cp").invoke(null);
        }
        throw new IllegalStateException("Exact native app unavailable");
    }

    private Object current() throws Exception {
        return app.getClass().getMethod("getCurrentDoc").invoke(app);
    }

    private void requireOwnedFile(Object document) throws Exception {
        Object file = document.getClass().getMethod("getFile").invoke(document);
        check(file instanceof File, "Owned document has no file");
        Path actual = ((File) file).toPath();
        requireFile(document, Files.isSameFile(actual, fixture) ? fixture : loadedPath());
    }

    private void requireFile(Object document, Path expected) throws Exception {
        check(
                document != null
                        && document.getClass().getName().equals("com.live2d.cubism.doc.modeling.CModelingDocument"),
                "Not the owned model document");
        Object file = document.getClass().getMethod("getFile").invoke(document);
        check(file instanceof File && Files.isSameFile(((File) file).toPath(), expected), "Foreign document file");
    }

    private List<Object> geometry() {
        return geometry(request);
    }

    private List<Object> geometry(McpGeometryRollbackProbe.Request request) {
        var model = context.cubism().model().active();
        var rows = new ArrayList<Object>();
        for (var target : request.targets()) {
            switch (target.kind()) {
                case "art_mesh" -> {
                    var s = model.drawables()
                            .find(new ArtMeshId(target.objectId()))
                            .authoringSnapshot();
                    check(
                            s.sourceGuid().equals(target.sourceGuid())
                                    && s.forms().stream()
                                            .anyMatch(f -> f.formGuid().equals(target.formGuid())),
                            "Mesh identity changed");
                    rows.add(s);
                }
                case "warp_deformer" -> {
                    var s = ((WarpDeformer) model.deformers().find(new DeformerId(target.objectId())))
                            .authoringSnapshot();
                    check(
                            s.sourceGuid().equals(target.sourceGuid())
                                    && s.forms().stream()
                                            .anyMatch(f -> f.formGuid().equals(target.formGuid())),
                            "Warp identity changed");
                    rows.add(s);
                }
                case "rotation_deformer" -> {
                    var s = ((RotationDeformer) model.deformers().find(new DeformerId(target.objectId())))
                            .authoringSnapshot();
                    check(
                            s.sourceGuid().equals(target.sourceGuid())
                                    && s.forms().stream()
                                            .anyMatch(f -> f.formGuid().equals(target.formGuid())),
                            "Rotation identity changed");
                    rows.add(s);
                }
                default -> throw new IllegalArgumentException("Unexpected geometry kind");
            }
        }
        check(rows.toString().length() <= 8 * 1024 * 1024, "Geometry evidence exceeds budget");
        return List.copyOf(rows);
    }

    private List<ParameterBinding> bindings() {
        return context.cubism().model().active().parameters().all().stream()
                .flatMap(p -> p.getParameterBindings().stream())
                .sorted(Comparator.comparing(
                                (ParameterBinding b) -> b.target().type().name())
                        .thenComparing(b -> b.target().id())
                        .thenComparing(b -> b.parameterId().value())
                        .thenComparing(b -> b.family().name()))
                .toList();
    }

    private <T> T edt(Callable<T> work) throws Exception {
        var task = new FutureTask<T>(work);
        SwingUtilities.invokeLater(task);
        return task.get(120, TimeUnit.SECONDS); // Timeout is terminal; no close/open cleanup or retry.
    }

    private Map<String, Object> read(String name) throws Exception {
        Path path = root.resolve(name);
        check(!Files.isSymbolicLink(path) && Files.size(path) <= 16384, "Invalid request file");
        return new LinkedHashMap<>(Json.parseObject(Files.readString(path)));
    }

    private Map<String, Object> report() {
        var report = new LinkedHashMap<String, Object>();
        report.put("runId", runId);
        report.put("status", "FAIL");
        return report;
    }

    private void publish(String name, Map<String, Object> report) {
        try {
            Path pending = root.resolve(name + ".pending");
            Files.writeString(pending, Json.stringify(report));
            Files.move(
                    pending, root.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) {
            context.logger().error("Fresh OPEN evidence publication failed", failure);
        }
    }

    private static List<String> hashes(List<Object> rows) throws Exception {
        var result = new ArrayList<String>();
        for (Object row : rows) result.add(hash(row.toString().getBytes(StandardCharsets.UTF_8)));
        return List.copyOf(result);
    }

    private static String fileHash(Path file) throws Exception {
        return hash(Files.readAllBytes(file));
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
