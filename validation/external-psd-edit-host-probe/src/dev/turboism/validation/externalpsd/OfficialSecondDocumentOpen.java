package dev.turboism.validation.externalpsd;

import javax.swing.SwingUtilities;
import java.awt.Dialog;
import java.awt.Window;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Validation-only second CMO open. Observes document/model object identity, raw names/GUIDs,
 * model-image current/linked/selector keys and dirty state. Layer record contents, ArtMesh
 * bindings, RGB and Undo are outside this observation; it does not establish F2 isolation.
 */
public final class OfficialSecondDocumentOpen {
    private static final String SHA =
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";

    private OfficialSecondDocumentOpen() { }

    /** firstDocumentId/firstContentId belong to the SDK, never the native UID namespace. */
    public record Request(Path taskRoot, Path cmo, String sha256, String firstDocumentId,
        String firstContentId, Window window, long timeoutMillis) { }

    /** Native references are intentionally confined to this validation helper's result. */
    public record Document(Object nativeDocument, Object nativeModel, String documentId,
        String contentId, Path file, String modelId, boolean dirty,
        Map<String, String> rawNames, Map<String, Image> images) {
        public Document {
            Objects.requireNonNull(nativeDocument);
            Objects.requireNonNull(nativeModel);
            requireText(documentId);
            requireText(contentId);
            requireText(modelId);
            file = Objects.requireNonNull(file).toAbsolutePath().normalize();
            rawNames = Map.copyOf(rawNames);
            images = Map.copyOf(images);
            if (rawNames.isEmpty() || images.isEmpty()) throw new IllegalArgumentException(
                "second-document control requires populated raw and model-image relations");
        }
    }

    public record Image(String current, List<String> linked, Set<String> selectorKeys) {
        public Image {
            requireText(current);
            linked = List.copyOf(linked);
            selectorKeys = Set.copyOf(selectorKeys);
            if (!selectorKeys.contains(current) || linked.size() != new HashSet<>(linked).size()
                || !selectorKeys.equals(new HashSet<>(linked))) throw new IllegalArgumentException(
                    "current/linked/selector key relations are inconsistent");
        }
    }

    public record Result(Document first, Document second, int commandCalls) { }

    interface Host {
        Object window() throws Exception;
        Object currentDocument() throws Exception;
        List<Document> documents() throws Exception;
        boolean hasDialog() throws Exception;
        default String dialogDiagnostic() throws Exception { return "dialog details unavailable"; }
        void open(Path cmo) throws Exception;
        /** The native document object currently active in the app; command hosts only. */
        default Object currentNativeDocument() throws Exception {
            throw new UnsupportedOperationException("native current document is not bound by this host");
        }
        /** Official undo of the given live document; only hosts that bind commands support it. */
        default void undo(Object nativeDocument) throws Exception {
            throw new UnsupportedOperationException("undo is not bound by this host");
        }
        /** Official redo of the given live document; only hosts that bind commands support it. */
        default void redo(Object nativeDocument) throws Exception {
            throw new UnsupportedOperationException("redo is not bound by this host");
        }
    }

    @FunctionalInterface
    public interface Admission { SdkIdentity check(Document nativeDocument, boolean completing) throws Exception; }

    public record SdkIdentity(String documentId, String contentId) {
        public SdkIdentity { requireText(documentId); requireText(contentId); }
    }

    public static Result open(Request request, BooleanSupplier stopped,
        BooleanSupplier taskBound, Admission admission) throws Exception {
        validateSource(request);
        Objects.requireNonNull(request.window(), "bound main window");
        return run(request, stopped, taskBound, new NativeHost(), Objects.requireNonNull(admission));
    }

    /** Fresh native observation of both live documents; no document activation involved. */
    public record Recheck(Document first, Document second, Object currentDocument) {
        public Recheck {
            Objects.requireNonNull(first);
            Objects.requireNonNull(second);
            Objects.requireNonNull(currentDocument);
        }
    }

    /**
     * Re-reads both live documents from the official object graph on the EDT. This is pure
     * observation: unlike {@link #open}, it never dispatches a command and never requires the
     * first document to be the active one, so isolation can be proven without UI interaction.
     */
    public static Recheck reobserve(Result prepared) throws Exception {
        return reobserve(prepared, newNativeHost());
    }

    /** Builds the exact-artifact host off the EDT; its reads still require the EDT. */
    public static Host newNativeHost() throws Exception {
        return new NativeHost();
    }

    static Recheck reobserve(Result prepared, Host host) throws Exception {
        Objects.requireNonNull(prepared);
        Objects.requireNonNull(host);
        final List<Document> docs = host.documents();
        if (docs.size() != 2) throw new IllegalStateException(
            "re-observation requires exactly two open model documents");
        Document first = null;
        Document second = null;
        for (Document doc : docs) {
            if (doc.nativeDocument() == prepared.first().nativeDocument()) {
                if (first != null) throw new IllegalStateException("first document observed twice");
                first = doc;
            } else if (doc.nativeDocument() == prepared.second().nativeDocument()) {
                if (second != null) throw new IllegalStateException("second document observed twice");
                second = doc;
            }
        }
        if (first == null || second == null) throw new IllegalStateException(
            "re-observed documents differ from the prepared pair");
        if (!first.file().equals(prepared.first().file())
            || !second.file().equals(prepared.second().file())) throw new IllegalStateException(
                "re-observed document paths differ from the prepared pair");
        return new Recheck(first, second, host.currentDocument());
    }

    static Result run(Request request, BooleanSupplier stopped, BooleanSupplier taskBound,
        Host host) throws Exception {
        return run(request, stopped, taskBound, host, (document, completing) -> new SdkIdentity(document.documentId(), document.contentId()));
    }

    static Result run(Request request, BooleanSupplier stopped, BooleanSupplier taskBound,
        Host host, Admission admission) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "second-document coordinator must run off EDT");
        validateSource(request);
        Objects.requireNonNull(stopped);
        Objects.requireNonNull(taskBound);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.timeoutMillis());
        AtomicBoolean live = new AtomicBoolean(true);
        BooleanSupplier allowed = () -> live.get() && !stopped.getAsBoolean()
            && taskBound.getAsBoolean() && System.nanoTime() < deadline;
        try {
            Document observedFirst = null;
            var waitingDialog = new java.util.concurrent.atomic.AtomicReference<>("none");
            while (observedFirst == null) {
                if (!allowed.getAsBoolean()) throw new IllegalStateException(
                    "initial document readiness stopped/expired; last dialog=" + waitingDialog.get());
                try {
                    observedFirst = edt(() -> {
                        check(allowed);
                        checkWindow(host, request);
                        if (host.hasDialog()) {
                            waitingDialog.set(host.dialogDiagnostic());
                            return null;
                        }
                        List<Document> docs = host.documents();
                        if (docs.size() != 1) throw new IllegalStateException(
                            "second-document preparation requires exactly one initial model document");
                        Document initial = docs.get(0);
                        requireFirstSdk(request, admission.check(initial, false));
                        if (initial.nativeDocument() != host.currentDocument()
                            || initial.file().equals(request.cmo().normalize())) throw new IllegalStateException(
                                "initial document identity/path differs");
                        return initial;
                    }, deadline, allowed);
                } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException failure) {
                    throw new IllegalStateException("initial document readiness failed; last dialog="
                        + waitingDialog.get(), failure);
                }
                if (observedFirst == null) Thread.sleep(Math.min(100L, Math.max(1L,
                    TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
            }
            final Document first = observedFirst;
            // Hash off EDT immediately before dispatch; the task owns this immutable input copy.
            validateSource(request);
            edt(() -> {
                check(allowed);
                checkHost(host, request);
                List<Document> docs = host.documents();
                if (docs.size() != 1 || !unchanged(first, docs.get(0))
                    || host.currentDocument() != first.nativeDocument()) throw new IllegalStateException(
                        "initial document changed before command_open");
                check(allowed);
                requireFirstSdk(request, admission.check(first, false));
                checkHost(host, request);
                if (host.currentDocument() != first.nativeDocument()) throw new IllegalStateException(
                    "native current document changed during SDK admission");
                check(allowed);
                host.open(request.cmo().normalize());
                return null;
            }, deadline, allowed);
            while (true) {
                check(allowed);
                Document second = edt(() -> {
                    check(allowed);
                    if (host.window() != request.window()) throw new IllegalStateException(
                        "bound host window changed while opening");
                    // command_open owns a progress dialog; never click it or accept completion
                    // while any dialog remains. The common deadline bounds this observation.
                    if (host.hasDialog()) return null;
                    Document candidate = observe(first, host.documents(), request.cmo().normalize());
                    if (candidate != null && host.currentDocument() != candidate.nativeDocument()) {
                        return null;
                    }
                    if (candidate != null) {
                        SdkIdentity sdk = admission.check(candidate, true);
                        if (request.firstDocumentId().equals(sdk.documentId())
                            || request.firstContentId().equals(sdk.contentId())) {
                            throw new IllegalStateException("second SDK document/content identity reused");
                        }
                        if (host.currentDocument() != candidate.nativeDocument()) throw new IllegalStateException(
                            "native current document changed during SDK observation");
                    }
                    check(allowed);
                    return candidate;
                }, deadline, allowed);
                if (second != null) return new Result(first, second, 1);
                Thread.sleep(Math.min(100L, Math.max(1L,
                    TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
            }
        } finally {
            live.set(false);
        }
    }

    static Document observe(Document first, List<Document> docs, Path expected) {
        if (docs.isEmpty() || docs.size() > 2) throw new IllegalStateException(
            "unexpected model document count");
        Document original = null;
        Document second = null;
        Set<String> ids = new HashSet<>();
        Set<String> contents = new HashSet<>();
        for (Document doc : docs) {
            if (!ids.add(doc.documentId()) || !contents.add(doc.contentId())) {
                throw new IllegalStateException("document UID/content identity collision");
            }
            if (doc.nativeDocument() == first.nativeDocument()) original = doc;
            else second = doc;
        }
        if (original == null || !unchanged(first, original)) throw new IllegalStateException(
            "original document identity or observed relations changed");
        if (second != null && (second.nativeModel() == first.nativeModel()
            || !second.file().equals(expected))) throw new IllegalStateException(
                "second document model object/path differs");
        return second;
    }

    private static boolean unchanged(Document a, Document b) {
        return a.nativeDocument() == b.nativeDocument() && a.nativeModel() == b.nativeModel()
            && a.documentId().equals(b.documentId()) && a.contentId().equals(b.contentId())
            && a.file().equals(b.file()) && a.modelId().equals(b.modelId())
            && a.dirty() == b.dirty() && a.rawNames().equals(b.rawNames())
            && a.images().equals(b.images());
    }

    private static void requireFirstSdk(Request request, SdkIdentity identity) {
        if (!request.firstDocumentId().equals(identity.documentId())
            || !request.firstContentId().equals(identity.contentId())) throw new IllegalStateException(
                "initial SDK document/content identity changed");
    }

    private static String objectId(Object value) {
        return value == null ? "null" : value.getClass().getName() + "@"
            + Integer.toHexString(System.identityHashCode(value));
    }

    /** Read-only bounded diagnostics. Editable text/password values are never inspected. */
    static String componentDiagnostic(java.awt.Component root) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("component read off EDT");
        var pending = new java.util.ArrayDeque<java.awt.Component>();
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<java.awt.Component, Boolean>());
        List<String> items = new ArrayList<>();
        pending.add(root);
        while (!pending.isEmpty() && items.size() < 48) {
            var component = pending.removeFirst();
            if (!seen.add(component)) continue;
            String label = component instanceof javax.swing.JLabel value ? value.getText()
                : component instanceof javax.swing.AbstractButton value ? value.getText() : "";
            items.add(component.getClass().getName() + " bounds=" + component.getBounds()
                + (label == null || label.isBlank() ? "" : " label=" + diagnosticText(label)));
            if (component instanceof java.awt.Container container) {
                for (var child : container.getComponents()) {
                    if (pending.size() + items.size() >= 48) break;
                    pending.addLast(child);
                }
            }
        }
        return items.toString();
    }

    private static String diagnosticText(String value) {
        if (value == null) return "";
        String text = value.replace('\n', ' ').replace('\r', ' ');
        return text.substring(0, Math.min(200, text.length()));
    }

    private static void checkWindow(Host host, Request request) throws Exception {
        Object actual = host.window();
        if (actual != request.window()) throw new IllegalStateException(
            "bound host window differs: expected=" + objectId(request.window()) + " actual=" + objectId(actual));
    }

    private static void checkHost(Host host, Request request) throws Exception {
        checkWindow(host, request);
        if (host.hasDialog()) throw new IllegalStateException(
            "dialog requires observation: " + host.dialogDiagnostic());
    }

    private static void check(BooleanSupplier allowed) {
        if (Thread.currentThread().isInterrupted() || !allowed.getAsBoolean()) {
            throw new IllegalStateException("second-document task stopped, expired or unbound");
        }
    }

    private static <T> T edt(Callable<T> call, long deadline, BooleanSupplier allowed)
        throws Exception {
        FutureTask<T> task = new FutureTask<>(() -> { check(allowed); return call.call(); });
        SwingUtilities.invokeLater(task);
        try {
            return task.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } finally {
            // Cancels a queued callback, but never interrupts the shared EDT.
            task.cancel(false);
        }
    }

    static void validateSource(Request r) throws Exception {
        Objects.requireNonNull(r);
        requireText(r.firstDocumentId());
        requireText(r.firstContentId());
        if (r.timeoutMillis() < 1000 || r.timeoutMillis() > 480000
            || r.taskRoot() == null || r.cmo() == null || !r.taskRoot().isAbsolute()
            || !r.cmo().isAbsolute() || r.sha256() == null || !r.sha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid second-document request");
        }
        Path root = r.taskRoot().normalize();
        Path file = r.cmo().normalize();
        if (!file.startsWith(root) || !file.getFileName().toString().endsWith(".cmo3")
            || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("second CMO must be a task-owned regular .cmo3");
        }
        for (Path p = file; p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new IllegalArgumentException("CMO path traverses symlink");
        }
        if (!r.sha256().equals(digest(file))) throw new IllegalArgumentException("second CMO SHA differs");
    }

    private static String digest(Path p) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = new byte[65536];
            for (int count; (count = input.read(bytes)) >= 0;) digest.update(bytes, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("identity is missing");
    }

    /** Exact artifact bridge; only command_open mutates the editor. */
    private static final class NativeHost implements Host {
        private final ClassLoader loader;
        private final Path artifact;
        private final Map<String, Method> methods = new HashMap<>();
        private final Class<?> appClass;

        NativeHost() throws Exception {
            if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("preflight on EDT");
            Class<?> app;
            try {
                app = Class.forName("com.live2d.cubism.CEAppCtrl", false,
                    Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException missing) {
                app = Class.forName("com.live2d.cubism.CEAppCtrl", false,
                    ClassLoader.getSystemClassLoader());
            }
            appClass = app;
            loader = app.getClassLoader();
            artifact = source(app);
            if (loader == null || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)
                || !SHA.equals(digest(artifact))) throw new IllegalStateException("official JAR mismatch");
            bind("instance", app, "access$get_instance$cp", app, true);
            bind("open", app, "command_open", void.class, false, File.class, boolean.class);
            Class<?> iDocument = type("com.live2d.cubism.doc.IDocument");
            bind("undo", app, "command_undo", void.class, false, iDocument);
            bind("redo", app, "command_redo", void.class, false, iDocument);
            bind("docs", app, "getAllModelDocs", List.class, false);
            bind("current", app, "getCurrentDoc", type("com.live2d.cubism.doc.IDocument"), false);
            Class<?> frame = type("com.live2d.cubism.view.CEMainFrameCtrl");
            Class<?> cframe = type("com.live2d.ui.window.CFrame");
            bind("frame", app, "getMainFrameCtrl", frame, false);
            bind("cframe", frame, "getMainFrame", cframe, false);
            bind("window", type("com.live2d.ui.window.V"), "getJWindow", Window.class, false);
            bind("jframe", cframe, "getJFrame", javax.swing.JFrame.class, false);
            Class<?> doc = type("com.live2d.cubism.doc.modeling.CModelingDocument");
            Class<?> model = type("com.live2d.cubism.doc.model.CModelSource");
            Class<?> manager = type("com.live2d.cubism.doc.model.texture.CTextureManager");
            bind("uid", doc, "getDocumentUID", String.class, false);
            bind("content", doc, "getFileContentInstanceGuid", type("com.live2d.type.FileContentInstanceGuid"), false);
            bind("file", doc, "getFile", File.class, false);
            bind("dirty", doc, "isModifiedAfterSaving", boolean.class, false);
            bind("model", doc, "getModelSource", model, false);
            bind("modelGuid", model, "getGuid", type("com.live2d.type.CModelGuid"), false);
            bind("manager", model, "getTextureManager", manager, false);
            bind("raws", manager, "getRawImages", List.class, false);
            bind("groups", manager, "getModelImageGroups", List.class, false);
            Class<?> raw = type("com.live2d.cubism.doc.resources.CLayeredImage");
            Class<?> rawGuid = type("com.live2d.type.CLayeredImageGuid");
            bind("raw", type("com.live2d.cubism.doc.model.texture.LayeredImageWrapper"), "getImage", raw, false);
            bind("rawGuid", raw, "getGuid", rawGuid, false);
            bind("rawName", raw, "getName", String.class, false);
            bind("guid", type("com.live2d.type.Guid"), "getUuidString", String.class, false);
            Class<?> image = type("com.live2d.cubism.doc.model.texture.modelImage.CModelImage");
            bind("images", type("com.live2d.cubism.doc.model.texture.modelImage.CModelImageGroup"), "getModelImages", List.class, false);
            bind("imageGuid", image, "getGuid", type("com.live2d.type.CModelImageGuid"), false);
            bind("linked", image, "getLinkedRawImageGuids", type("com.live2d.type.CArrayList"), false);
            Class<?> env = type("com.live2d.cubism.doc.model.extension.textureInput.inputFilter.ModelImageFilterEnv");
            Class<?> selector = type("com.live2d.cubism.doc.model.extension.textureInput.inputFilter.CLayerSelectorMap");
            bind("env", image, "getInputFilterEnv", env, false);
            bind("currentRaw", env, "getCurrentImageGuid", rawGuid, false);
            bind("selector", env, "getLayerInputData", selector, false);
            bind("keys", selector, "getImageToLayerInput", Map.class, false);
        }

        private static Path source(Class<?> type) throws Exception {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        }

        private Class<?> type(String name) throws Exception {
            Class<?> type = Class.forName(name, false, loader);
            verify(type);
            return type;
        }

        private void verify(Class<?> type) throws Exception {
            if (type.getClassLoader() != loader || !source(type).equals(artifact)) {
                throw new IllegalStateException("official class artifact/loader differs: " + type.getName());
            }
        }

        private void bind(String key, Class<?> owner, String name, Class<?> result,
            boolean isStatic, Class<?>... args) throws Exception {
            verify(owner);
            Method method = owner.getDeclaredMethod(name, args);
            if (method.getReturnType() != result || !Modifier.isPublic(method.getModifiers())
                || Modifier.isStatic(method.getModifiers()) != isStatic || !method.trySetAccessible()) {
                throw new IllegalStateException("official method shape differs: " + name);
            }
            methods.put(key, method);
        }

        private Object get(String key, Object target, Object... args) throws Exception {
            if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("host read off EDT");
            return methods.get(key).invoke(target, args);
        }

        private Object app() throws Exception {
            Object app = get("instance", null);
            if (app == null || app.getClass() != appClass) throw new IllegalStateException("app unavailable");
            return app;
        }

        @Override public Object window() throws Exception {
            Object frame = get("cframe", get("frame", app()));
            Window window = (Window) get("window", frame);
            if (window != get("jframe", frame) || !window.isShowing() || !window.isDisplayable()) {
                throw new IllegalStateException("official main window unavailable");
            }
            return window;
        }

        @Override public boolean hasDialog() {
            if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("dialog read off EDT");
            for (Window w : Window.getWindows()) if (w instanceof Dialog && w.isShowing()) return true;
            return false;
        }

        @Override public String dialogDiagnostic() {
            if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("dialog read off EDT");
            List<String> values = new ArrayList<>();
            for (Window w : Window.getWindows()) if (w instanceof Dialog d && w.isShowing()) {
                values.add("class=" + d.getClass().getName() + " identity=" + objectId(d)
                    + " owner=" + objectId(d.getOwner()) + " showing=" + d.isShowing()
                    + " displayable=" + d.isDisplayable() + " modal=" + d.isModal()
                    + " title=" + diagnosticText(d.getTitle()) + " bounds=" + d.getBounds()
                    + " type=" + d.getType() + " components=" + componentDiagnostic(d));
            }
            return values.toString();
        }

        @Override public Object currentDocument() throws Exception { return get("current", app()); }
        @Override public Object currentNativeDocument() throws Exception { return get("current", app()); }
        @Override public void open(Path cmo) throws Exception { get("open", app(), cmo.toFile(), true); }
        @Override public void undo(Object nativeDocument) throws Exception {
            get("undo", app(), nativeDocument);
        }
        @Override public void redo(Object nativeDocument) throws Exception {
            get("redo", app(), nativeDocument);
        }
        private String guid(Object value) throws Exception { return (String) get("guid", value); }

        @Override public List<Document> documents() throws Exception {
            List<Document> result = new ArrayList<>();
            for (Object doc : (List<?>) get("docs", app())) {
                if (doc.getClass() != methods.get("uid").getDeclaringClass()) {
                    throw new IllegalStateException("unknown model document class");
                }
                Object model = get("model", doc);
                Object manager = get("manager", model);
                Map<String, String> raws = new HashMap<>();
                for (Object wrapper : (List<?>) get("raws", manager)) {
                    Object raw = get("raw", wrapper);
                    if (raws.put(guid(get("rawGuid", raw)), (String) get("rawName", raw)) != null) {
                        throw new IllegalStateException("duplicate raw GUID");
                    }
                }
                Map<String, Image> images = new HashMap<>();
                for (Object group : (List<?>) get("groups", manager)) {
                    for (Object image : (List<?>) get("images", group)) {
                        Object env = get("env", image);
                        List<String> linked = new ArrayList<>();
                        for (Object raw : (List<?>) get("linked", image)) linked.add(guid(raw));
                        Set<String> keys = new HashSet<>();
                        for (Object raw : ((Map<?, ?>) get("keys", get("selector", env))).keySet()) {
                            if (!keys.add(guid(raw))) throw new IllegalStateException("duplicate selector GUID");
                        }
                        if (!raws.keySet().containsAll(keys)) throw new IllegalStateException("dangling selector raw");
                        Image value = new Image(guid(get("currentRaw", env)), linked, keys);
                        if (images.put(guid(get("imageGuid", image)), value) != null) {
                            throw new IllegalStateException("duplicate model-image GUID");
                        }
                    }
                }
                result.add(new Document(doc, model, (String) get("uid", doc), guid(get("content", doc)),
                    ((File) get("file", doc)).toPath(), guid(get("modelGuid", model)),
                    (Boolean) get("dirty", doc), raws, images));
            }
            return List.copyOf(result);
        }
    }

    static void verifyShape() throws Exception { new NativeHost(); }
}
