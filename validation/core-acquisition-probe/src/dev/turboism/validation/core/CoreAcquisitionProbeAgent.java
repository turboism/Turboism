package dev.turboism.validation.core;

import java.awt.Window;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

/** Standalone validation-only, public-reflection-only Core acquisition probe. */
public final class CoreAcquisitionProbeAgent {
    private static final String APP = "com.live2d.cubism.CEAppCtrl";
    private static final String DOCUMENT = "com.live2d.cubism.doc.modeling.CModelingDocument";
    private static final String SOURCE = "com.live2d.cubism.doc.model.CModelSource";
    private static final String MODEL = "com.live2d.cubism.doc.model.CModel";
    private static final String VIEW = "com.live2d.cubism.view.context.CEViewContext_ModelingView";
    private static final String CORE_MODEL = "com.live2d.sdk.cubism.core.CubismModel";
    private static final long TIMEOUT_MILLIS = 360_000L;
    private static final long POLL_MILLIS = 250L;
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private CoreAcquisitionProbeAgent() {}

    public static void premain(final String ignored, final Instrumentation instrumentation) {
        if (!STARTED.compareAndSet(false, true)) return;
        System.out.println("CORE_ACQUISITION_PROBE_INITIALIZED");
        System.err.println("CORE_ACQUISITION_PROBE_INITIALIZED");
        final Thread observer = new Thread(() -> observe(instrumentation), "turboism-core-acquisition-probe");
        observer.setDaemon(true);
        observer.start();
    }

    private static void observe(final Instrumentation instrumentation) {
        final String homeValue = System.getProperty("turboism.home");
        if (blank(homeValue)) {
            System.err.println("CORE_ACQUISITION_RESULT status=FAIL bridgeStatus=ERROR");
            return;
        }
        final Path stateDir = Path.of(homeValue).resolve("state");
        Config config;
        try {
            config = Config.read();
        } catch (Throwable failure) {
            finish(stateDir, null, "FAIL", "ERROR", failureText(failure), null);
            return;
        }

        final long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        Observation last = null;
        Observation accepted = null;
        boolean timedOut = false;
        try {
            while (System.currentTimeMillis() < deadline) {
                final Loaded loaded = Loaded.find(instrumentation.getAllLoadedClasses());
                if (loaded.ready()) {
                    final StableCapture capture = onEdt(() -> captureStable(loaded));
                    last = capture.observation;
                    if (capture.stable) {
                        accepted = capture.observation;
                        break;
                    }
                }
                sleep(POLL_MILLIS);
            }
            if (accepted == null) timedOut = true;
        } catch (Throwable failure) {
            finish(stateDir, last, "FAIL", "ERROR", failureText(failure), config);
            return;
        }

        if (timedOut) {
            finish(stateDir, last, "FAIL", "ERROR",
                "TIMEOUT_NO_USABLE_DOCUMENT_OR_CLASSES", config);
            return;
        }
        final String error = config.hashesMatch(accepted) ? null : "EDITOR_OR_CORE_HASH_MISMATCH";
        finish(stateDir, accepted, error == null ? "PASS" : "FAIL", accepted.bridgeStatus, error, config);
    }

    private static void finish(final Path stateDir, final Observation observation, final String status,
                               final String bridgeStatus, final String error, final Config config) {
        final String runId = config == null ? "" : config.runId;
        final String profile = config == null ? "" : config.profile;
        final String expectedEditor = config == null ? "" : config.expectedEditorSha256;
        final String expectedCore = config == null ? "" : config.expectedCoreSha256;
        final String actualEditor = observation == null ? "" : observation.editorSha256;
        final String actualCore = observation == null ? "" : observation.coreSha256;
        final Map<String, String> result = new TreeMap<>();
        result.put("actualCoreSha256", actualCore);
        result.put("actualEditorSha256", actualEditor);
        result.put("bridgeStatus", bridgeStatus);
        result.put("expectedCoreSha256", expectedCore);
        result.put("expectedEditorSha256", expectedEditor);
        result.put("profile", profile);
        result.put("runId", runId);
        result.put("stableSnapshots", Integer.toString(observation == null ? 0 : observation.stableSnapshots));
        result.put("status", status);
        if (error != null) result.put("error", error);
        try {
            Files.createDirectories(stateDir);
            writeAtomic(stateDir.resolve("core-acquisition-report.json"),
                renderJson(status, bridgeStatus, error, config, observation).getBytes(StandardCharsets.UTF_8));
            writeAtomic(stateDir.resolve("core-acquisition-result.properties"),
                renderProperties(result).getBytes(StandardCharsets.UTF_8));
            System.out.println("CORE_ACQUISITION_RESULT status=" + status + " bridgeStatus=" + bridgeStatus);
            System.err.println("CORE_ACQUISITION_RESULT status=" + status + " bridgeStatus=" + bridgeStatus);
            if ("true".equalsIgnoreCase(System.getProperty("turboism.coreAcquisition.exitOnComplete"))) {
                onEdt(() -> { requestHostClose(); return null; });
            }
        } catch (Throwable failure) {
            System.err.println("CORE_ACQUISITION_RESULT status=FAIL bridgeStatus=ERROR");
        }
    }

    private static StableCapture captureStable(final Loaded loaded) {
        final TokenBook tokens = new TokenBook();
        final Raw first = captureOnce(loaded, tokens);
        final Raw second = captureOnce(loaded, tokens);
        final Raw third = captureOnce(loaded, tokens);
        final boolean stable = usable(first, loaded) && sameIdentity(first, second) && sameIdentity(second, third);
        final Observation observation = toObservation(third, tokens, stable ? 3 : 0);
        tokens.clear();
        return new StableCapture(observation, stable);
    }

    private static boolean usable(final Raw raw, final Loaded loaded) {
        return raw != null && loaded.document.isInstance(raw.document) && loaded.source.isInstance(raw.source)
            && loaded.model.isInstance(raw.current) && raw.document == raw.packDocument
            && raw.document == raw.sourceDocument && containsIdentity(raw.modelInstances, raw.current)
            && (raw.view == null || loaded.model.isInstance(raw.viewModel));
    }

    private static Raw captureOnce(final Loaded loaded, final TokenBook tokens) {
        final Object app = invokeStatic(loaded.app, "access$get_instance$cp");
        final Object document = invokeGetter(app, "getCurrentDoc");
        final Object pack = invokeGetter(app, "getCompletePack");
        final Object packDocument = invokeGetter(pack, "getCurrentDoc");
        final Object source = loaded.document.isInstance(document) ? invokeGetter(document, "getModelSource") : null;
        final Object sourceDocument = loaded.source.isInstance(source) ? invokeGetter(source, "getDocument") : null;
        final List<Object> modelInstances = objectList(invokeGetter(source, "getModelInstances"));
        final Object viewContext = loaded.document.isInstance(document) ? invokeGetter(document, "getLastActiveViewContext") : null;
        final Object view = loaded.view.isInstance(viewContext) ? viewContext : null;
        final Object current = loaded.source.isInstance(source) ? invokeGetter(source, "getCurrentInstance") : null;
        final Object viewModel = invokeGetter(view, "getModel");

        final LinkedHashMap<String, Object> candidates = new LinkedHashMap<>();
        candidates.put("document", document);
        candidates.put("source", source);
        candidates.put("current", current);
        candidates.put("view", view);
        candidates.put("viewModel", viewModel);
        for (int index = 0; index < modelInstances.size(); index++) {
            candidates.put("modelInstance[" + index + "]", modelInstances.get(index));
        }
        final List<Bridge> bridges = findBridges(candidates, loaded, tokens);
        final Bridge bridge = bridges.size() == 1 ? bridges.get(0) : null;
        final String bridgeStatus = bridges.isEmpty() ? "NO_PUBLIC_BRIDGE"
            : bridges.size() > 1 ? "AMBIGUOUS" : "FOUND";
        final Map<String, String> counts = bridge == null ? Map.of() : structuralCounts(bridge.model);
        return new Raw(document, source, sourceDocument, current, view, viewModel, packDocument, modelInstances,
            bridgeStatus, bridge, counts, loaded);
    }

    private static List<Object> objectList(final Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return new ArrayList<>(list);
    }

    private static boolean containsIdentity(final List<Object> values, final Object expected) {
        for (Object value : values) if (value == expected) return true;
        return false;
    }

    private static Observation toObservation(final Raw raw, final TokenBook tokens, final int stableSnapshots) {
        final List<Candidate> candidates = new ArrayList<>();
        addCandidate(candidates, "document", raw.document, tokens);
        addCandidate(candidates, "source", raw.source, tokens);
        addCandidate(candidates, "sourceDocument", raw.sourceDocument, tokens);
        addCandidate(candidates, "current", raw.current, tokens);
        addCandidate(candidates, "view", raw.view, tokens);
        addCandidate(candidates, "viewModel", raw.viewModel, tokens);
        addCandidate(candidates, "packDocument", raw.packDocument, tokens);
        for (int index = 0; index < raw.modelInstances.size(); index++) {
            addCandidate(candidates, "modelInstance[" + index + "]", raw.modelInstances.get(index), tokens);
        }
        final IdentityFlags identity = new IdentityFlags(
            raw.document == raw.packDocument,
            raw.document == raw.sourceDocument,
            raw.current != null && raw.current == raw.viewModel,
            containsIdentity(raw.modelInstances, raw.current)
        );
        return new Observation(raw.bridgeStatus, raw.bridge == null ? null
            : new BridgeInfo(raw.bridge.candidate, raw.bridge.method, raw.bridge.token), raw.counts, candidates,
            identity, hash(raw.loaded.app), hash(raw.loaded.coreModel), codeSource(raw.loaded.app),
            codeSource(raw.loaded.coreModel), loaderName(raw.loaded.app), loaderName(raw.loaded.coreModel),
            tokens.loaderToken(raw.loaded.app.getClassLoader()), tokens.loaderToken(raw.loaded.coreModel.getClassLoader()),
            raw.loaded.app.getClassLoader() == raw.loaded.coreModel.getClassLoader(), raw.modelInstances.size(),
            Thread.currentThread().getName(), stableSnapshots, SwingUtilities.isEventDispatchThread());
    }

    private static void addCandidate(final List<Candidate> candidates, final String name, final Object value,
                                     final TokenBook tokens) {
        if (value == null) return;
        final Class<?> type = value.getClass();
        candidates.add(new Candidate(name, tokens.token(value), type.getName(), tokens.loaderToken(type.getClassLoader()),
            loaderName(type), codeSource(type), safeId(value)));
    }

    private static List<Bridge> findBridges(final Map<String, Object> candidates, final Loaded loaded,
                                            final TokenBook tokens) {
        final IdentityHashMap<Object, Bridge> unique = new IdentityHashMap<>();
        for (Map.Entry<String, Object> entry : candidates.entrySet()) {
            final Object candidate = entry.getValue();
            if (candidate == null) continue;
            final Method[] methods = candidate.getClass().getMethods();
            java.util.Arrays.sort(methods, Comparator.comparing(Method::toGenericString));
            for (Method method : methods) {
                if (!isSafePublicGetter(method) || !isPublicCoreType(method.getReturnType(), loaded)) continue;
                if (!CORE_MODEL.equals(method.getReturnType().getName())) continue;
                final Object value = invoke(method, candidate);
                if (value != null && loaded.coreModel.isInstance(value)) {
                    unique.putIfAbsent(value, new Bridge(entry.getKey(), method.getName(), value, tokens.token(value)));
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    static boolean isSafePublicGetter(final Method method) {
        if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) return false;
        if (method.getParameterCount() != 0 || method.getReturnType() == Void.TYPE) return false;
        final String name = method.getName().toLowerCase(Locale.ROOT);
        if (!(name.startsWith("get") || name.startsWith("is"))) return false;
        return !name.contains("close") && !name.contains("delete") && !name.contains("update")
            && !name.contains("reinit") && !name.contains("instantiate") && !name.contains("nativehandle");
    }

    private static boolean isPublicCoreType(final Class<?> type, final Loaded loaded) {
        return Modifier.isPublic(type.getModifiers()) && type.getName().startsWith("com.live2d.sdk.cubism.core.")
            && type.getClassLoader() == loaded.loader;
    }

    private static Map<String, String> structuralCounts(final Object model) {
        final LinkedHashMap<String, String> counts = new LinkedHashMap<>();
        for (String getter : new String[] {"getParameters", "getParts", "getDrawables", "getDeformers", "getGlues"}) {
            final Object collection = invokeGetter(model, getter);
            if (collection == null) continue;
            try {
                final Method count = collection.getClass().getMethod("getCount");
                if (!isSafePublicGetter(count)) continue;
                final Object value = invoke(count, collection);
                if (value instanceof Number number) counts.put(getter.substring(3).toLowerCase(Locale.ROOT) + "Count",
                    number.toString());
            } catch (ReflectiveOperationException ignored) { }
        }
        return counts;
    }

    private static Object invokeGetter(final Object receiver, final String name) {
        if (receiver == null) return null;
        try {
            final Method method = receiver.getClass().getMethod(name);
            return isSafePublicGetter(method) ? invoke(method, receiver) : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Object invokeStatic(final Class<?> type, final String name) {
        if (type == null) return null;
        try {
            final Method method = type.getMethod(name);
            return Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())
                && method.getParameterCount() == 0 ? invoke(method, null) : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Object invoke(final Method method, final Object receiver) {
        try {
            return method.invoke(receiver);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
            return null;
        }
    }

    private static <T> T onEdt(final Callable<T> callable) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return callable.call();
        final FutureTask<T> task = new FutureTask<>(callable);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static boolean sameIdentity(final Raw left, final Raw right) {
        return right != null && left.document == right.document && left.source == right.source
            && left.sourceDocument == right.sourceDocument && left.current == right.current && left.view == right.view
            && left.viewModel == right.viewModel && left.packDocument == right.packDocument
            && sameIdentityList(left.modelInstances, right.modelInstances);
    }

    private static boolean sameIdentityList(final List<Object> left, final List<Object> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) if (left.get(index) != right.get(index)) return false;
        return true;
    }

    private static void sleep(final long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static void requestHostClose() {
        Window target = null;
        long largestArea = -1;
        for (Window window : Window.getWindows()) {
            if (!window.isDisplayable() || !window.isVisible()) continue;
            final long area = (long) window.getWidth() * window.getHeight();
            if (area > largestArea) {
                target = window;
                largestArea = area;
            }
        }
        if (target != null) target.dispatchEvent(new WindowEvent(target, WindowEvent.WINDOW_CLOSING));
    }

    static void writeAtomic(final Path target, final byte[] bytes) throws IOException {
        final Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        final Path temporary = Files.createTempFile(parent, "." + target.getFileName(), ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String renderProperties(final Map<String, String> values) {
        final StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result.append(entry.getKey()).append('=').append(propertyEscape(entry.getValue())).append('\n');
        }
        return result.toString();
    }

    private static String propertyEscape(final String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("=", "\\=");
    }

    private static String renderJson(final String status, final String bridgeStatus, final String error,
                                     final Config config, final Observation observation) {
        final String runId = config == null ? "" : config.runId;
        final String profile = config == null ? "" : config.profile;
        final String expectedEditor = config == null ? "" : config.expectedEditorSha256;
        final String expectedCore = config == null ? "" : config.expectedCoreSha256;
        final String actualEditor = observation == null ? "" : observation.editorSha256;
        final String actualCore = observation == null ? "" : observation.coreSha256;
        final StringBuilder json = new StringBuilder("{\n");
        field(json, "schemaVersion", "1", false, true);
        field(json, "runId", runId, true, true);
        field(json, "profile", profile, true, true);
        field(json, "status", status, true, true);
        field(json, "bridgeStatus", bridgeStatus, true, true);
        field(json, "thread", observation == null ? Thread.currentThread().getName() : observation.thread, true, true);
        field(json, "edt", observation != null && observation.edt, false, true);
        field(json, "stableSnapshots", observation == null ? 0 : observation.stableSnapshots, false, true);
        field(json, "modelInstancesCount", observation == null ? 0 : observation.modelInstancesCount, false, true);
        field(json, "expectedEditorSha256", expectedEditor, true, true);
        field(json, "expectedCoreSha256", expectedCore, true, true);
        field(json, "actualEditorSha256", actualEditor, true, true);
        field(json, "actualCoreSha256", actualCore, true, true);
        field(json, "editorHashMatch", observation != null && expectedEditor.equalsIgnoreCase(actualEditor), false, true);
        field(json, "coreHashMatch", observation != null && expectedCore.equalsIgnoreCase(actualCore), false, true);
        field(json, "editorCodeSource", observation == null ? "" : observation.editorCodeSource, true, true);
        field(json, "coreCodeSource", observation == null ? "" : observation.coreCodeSource, true, true);
        field(json, "editorClassLoader", observation == null ? "" : observation.editorLoader, true, true);
        field(json, "coreClassLoader", observation == null ? "" : observation.coreLoader, true, true);
        field(json, "editorClassLoaderToken", observation == null ? 0 : observation.editorLoaderToken, false, true);
        field(json, "coreClassLoaderToken", observation == null ? 0 : observation.coreLoaderToken, false, true);
        field(json, "sameDefiningClassLoader", observation != null && observation.sameDefiningLoader, false, true);
        json.append("  \"candidates\": ");
        appendCandidates(json, observation);
        if (observation != null && observation.bridge != null) {
            json.append(",\n  \"bridge\": {\"candidate\": \"").append(escape(observation.bridge.candidate))
                .append("\",\"method\":\"").append(escape(observation.bridge.method))
                .append("\",\"token\":").append(observation.bridge.token).append('}');
        }
        if (observation != null && !observation.counts.isEmpty()) {
            json.append(",\n  \"coreCounts\": {");
            int index = 0;
            for (Map.Entry<String, String> entry : observation.counts.entrySet()) {
                if (index++ > 0) json.append(',');
                json.append("\n    \"").append(escape(entry.getKey())).append("\":").append(entry.getValue());
            }
            json.append("\n  }");
        }
        json.append(",\n  \"identityEquality\": {");
        final IdentityFlags identity = observation == null ? IdentityFlags.NONE : observation.identity;
        json.append("\"documentPackDocument\":").append(identity.documentPackDocument)
            .append(",\"documentSourceDocument\":").append(identity.documentSourceDocument)
            .append(",\"currentViewModel\":").append(identity.currentViewModel)
            .append(",\"currentInModelInstances\":").append(identity.currentInModelInstances).append('}');
        json.append(",\n  \"assertions\": {\"publicReflectionOnly\":true,\"edtHostCalls\":")
            .append(observation != null && observation.edt)
            .append(",\"identityStableThreeSnapshots\":").append(observation != null && observation.stableSnapshots == 3)
            .append(",\"activeBindingCorroborated\":")
            .append(identity.documentPackDocument && identity.documentSourceDocument && identity.currentInModelInstances)
            .append(",\"noMutationOperations\":true}");
        if (error != null) json.append(",\n  \"error\":\"").append(escape(error)).append('"');
        return json.append("\n}\n").toString();
    }

    private static void appendCandidates(final StringBuilder json, final Observation observation) {
        json.append('[');
        if (observation != null) {
            for (int i = 0; i < observation.candidates.size(); i++) {
                if (i > 0) json.append(',');
                final Candidate c = observation.candidates.get(i);
                json.append("{\"name\":\"").append(escape(c.name)).append("\",\"token\":").append(c.token)
                    .append(",\"class\":\"").append(escape(c.className)).append("\",\"loaderToken\":")
                    .append(c.loaderToken).append(",\"loader\":\"").append(escape(c.loaderName))
                    .append("\",\"codeSource\":\"").append(escape(c.codeSource)).append("\",\"publicId\":\"")
                    .append(escape(c.publicId)).append("\"}");
            }
        }
        json.append(']');
    }

    private static void field(final StringBuilder json, final String key, final Object value,
                              final boolean string, final boolean comma) {
        json.append("  \"").append(escape(key)).append("\":");
        if (string) json.append('"').append(escape(String.valueOf(value))).append('"'); else json.append(value);
        if (comma) json.append(',');
        json.append('\n');
    }

    private static String escape(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String failureText(final Throwable failure) {
        return failure.getClass().getName() + ": " + (failure.getMessage() == null ? "" : failure.getMessage());
    }

    private static boolean blank(final String value) { return value == null || value.trim().isEmpty(); }

    private static String hash(final Class<?> type) {
        try {
            final URL source = type.getProtectionDomain().getCodeSource() == null ? null
                : type.getProtectionDomain().getCodeSource().getLocation();
            if (source == null || !"file".equalsIgnoreCase(source.getProtocol())) return "";
            final Path path = Path.of(source.toURI());
            if (!Files.isRegularFile(path)) return "";
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                final byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception ignored) { return ""; }
    }

    private static String codeSource(final Class<?> type) {
        try {
            final URL source = type.getProtectionDomain().getCodeSource() == null ? null
                : type.getProtectionDomain().getCodeSource().getLocation();
            return source == null ? "" : source.toString();
        } catch (SecurityException ignored) { return ""; }
    }

    private static String loaderName(final Class<?> type) {
        final ClassLoader loader = type.getClassLoader();
        return loader == null ? "bootstrap" : loader.getClass().getName();
    }

    private static String safeId(final Object value) {
        for (String methodName : new String[] {"getGuid", "getUuidString", "getIdString"}) {
            final Object nested = invokeGetter(value, methodName);
            if (nested instanceof String text && !text.isBlank()) return text;
            if (nested != null) {
                for (String nestedName : new String[] {"getUuidString", "getIdString"}) {
                    final Object id = invokeGetter(nested, nestedName);
                    if (id instanceof String text && !text.isBlank()) return text;
                }
            }
        }
        return "";
    }

    private static final class Config {
        final String runId, profile, expectedEditorSha256, expectedCoreSha256;
        private Config(String runId, String profile, String expectedEditorSha256, String expectedCoreSha256) {
            this.runId = runId; this.profile = profile; this.expectedEditorSha256 = expectedEditorSha256;
            this.expectedCoreSha256 = expectedCoreSha256;
        }
        static Config read() {
            final String profile = required("turboism.coreAcquisition.profile");
            if (!"cubism-5.2".equals(profile) && !"cubism-5.3.02".equals(profile)) {
                throw new IllegalArgumentException("unsupported Core acquisition profile: " + profile);
            }
            final String editor = digest("turboism.coreAcquisition.expectedEditorSha256");
            final String core = digest("turboism.coreAcquisition.expectedCoreSha256");
            return new Config(required("turboism.validation.runId"), profile, editor, core);
        }
        boolean hashesMatch(final Observation observation) {
            return observation != null && expectedEditorSha256.equals(observation.editorSha256)
                && expectedCoreSha256.equals(observation.coreSha256);
        }
        private static String digest(final String key) {
            final String value = required(key).toLowerCase(Locale.ROOT);
            if (!value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid SHA-256 property: " + key);
            }
            return value;
        }
    }

    private static String required(final String key) {
        final String value = System.getProperty(key);
        if (blank(value)) throw new IllegalArgumentException("missing required property: " + key);
        return value.trim();
    }

    private static final class Loaded {
        final Class<?> app, document, source, model, view, coreModel;
        final ClassLoader loader;
        private Loaded(Class<?> app, Class<?> document, Class<?> source, Class<?> model, Class<?> view,
                       Class<?> coreModel, ClassLoader loader) {
            this.app = app; this.document = document; this.source = source; this.model = model; this.view = view;
            this.coreModel = coreModel; this.loader = loader;
        }
        boolean ready() { return app != null && document != null && source != null && model != null && view != null && coreModel != null; }
        static Loaded find(final Class<?>[] loaded) {
            Class<?> app = null;
            for (Class<?> type : loaded) if (APP.equals(type.getName())) { app = type; break; }
            if (app == null) return new Loaded(null, null, null, null, null, null, null);
            final ClassLoader loader = app.getClassLoader();
            return new Loaded(app, find(loaded, DOCUMENT, loader), find(loaded, SOURCE, loader), find(loaded, MODEL, loader),
                find(loaded, VIEW, loader), find(loaded, CORE_MODEL, loader), loader);
        }
        private static Class<?> find(final Class<?>[] loaded, final String name, final ClassLoader loader) {
            for (Class<?> type : loaded) if (name.equals(type.getName()) && type.getClassLoader() == loader) return type;
            return null;
        }
    }

    private static final class TokenBook {
        private final IdentityHashMap<Object, Integer> objects = new IdentityHashMap<>();
        private final IdentityHashMap<ClassLoader, Integer> loaders = new IdentityHashMap<>();
        private int nextObject = 1, nextLoader = 1;
        int token(final Object value) { return objects.computeIfAbsent(value, ignored -> nextObject++); }
        int loaderToken(final ClassLoader value) { return value == null ? 0 : loaders.computeIfAbsent(value, ignored -> nextLoader++); }
        void clear() { objects.clear(); loaders.clear(); }
    }

    private static final class Raw {
        final Object document, source, sourceDocument, current, view, viewModel, packDocument;
        final List<Object> modelInstances;
        final String bridgeStatus; final Bridge bridge; final Map<String, String> counts; final Loaded loaded;
        Raw(Object document, Object source, Object sourceDocument, Object current, Object view, Object viewModel,
            Object packDocument, List<Object> modelInstances, String bridgeStatus, Bridge bridge,
            Map<String, String> counts, Loaded loaded) {
            this.document = document; this.source = source; this.sourceDocument = sourceDocument; this.current = current;
            this.view = view; this.viewModel = viewModel; this.packDocument = packDocument;
            this.modelInstances = modelInstances; this.bridgeStatus = bridgeStatus; this.bridge = bridge;
            this.counts = counts; this.loaded = loaded;
        }
    }

    private static final class StableCapture {
        final Observation observation; final boolean stable;
        StableCapture(Observation observation, boolean stable) { this.observation = observation; this.stable = stable; }
    }

    private static final class Bridge {
        final String candidate, method; final Object model; final int token;
        Bridge(String candidate, String method, Object model, int token) {
            this.candidate = candidate; this.method = method; this.model = model; this.token = token;
        }
    }

    private static final class Observation {
        final String bridgeStatus, editorSha256, coreSha256, editorCodeSource, coreCodeSource;
        final String editorLoader, coreLoader, thread;
        final BridgeInfo bridge; final Map<String, String> counts; final List<Candidate> candidates;
        final IdentityFlags identity; final int editorLoaderToken, coreLoaderToken, modelInstancesCount, stableSnapshots;
        final boolean sameDefiningLoader, edt;
        Observation(String bridgeStatus, BridgeInfo bridge, Map<String, String> counts, List<Candidate> candidates,
                    IdentityFlags identity, String editorSha256, String coreSha256, String editorCodeSource,
                    String coreCodeSource, String editorLoader, String coreLoader, int editorLoaderToken,
                    int coreLoaderToken, boolean sameDefiningLoader, int modelInstancesCount, String thread,
                    int stableSnapshots, boolean edt) {
            this.bridgeStatus = bridgeStatus; this.bridge = bridge; this.counts = Map.copyOf(counts);
            this.candidates = List.copyOf(candidates); this.identity = identity; this.editorSha256 = editorSha256;
            this.coreSha256 = coreSha256; this.editorCodeSource = editorCodeSource; this.coreCodeSource = coreCodeSource;
            this.editorLoader = editorLoader; this.coreLoader = coreLoader; this.editorLoaderToken = editorLoaderToken;
            this.coreLoaderToken = coreLoaderToken; this.sameDefiningLoader = sameDefiningLoader;
            this.modelInstancesCount = modelInstancesCount; this.thread = thread;
            this.stableSnapshots = stableSnapshots; this.edt = edt;
        }
    }

    private static final class BridgeInfo {
        final String candidate, method; final int token;
        BridgeInfo(String candidate, String method, int token) { this.candidate = candidate; this.method = method; this.token = token; }
    }

    private static final class Candidate {
        final String name, className, loaderName, codeSource, publicId; final int token, loaderToken;
        Candidate(String name, int token, String className, int loaderToken, String loaderName, String codeSource, String publicId) {
            this.name = name; this.token = token; this.className = className; this.loaderToken = loaderToken;
            this.loaderName = loaderName; this.codeSource = codeSource; this.publicId = publicId;
        }
    }

    private static final class IdentityFlags {
        static final IdentityFlags NONE = new IdentityFlags(false, false, false, false);
        final boolean documentPackDocument, documentSourceDocument, currentViewModel, currentInModelInstances;
        IdentityFlags(boolean documentPackDocument, boolean documentSourceDocument, boolean currentViewModel,
                      boolean currentInModelInstances) {
            this.documentPackDocument = documentPackDocument;
            this.documentSourceDocument = documentSourceDocument;
            this.currentViewModel = currentViewModel;
            this.currentInModelInstances = currentInModelInstances;
        }
    }
}
