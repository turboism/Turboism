package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.plugin.PluginContext;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Manual-test-only, read-only exact-host probe for Cubism's public Undo manager surface. */
public final class WindowsHistoryManagerValidationProbe implements CubismPlugin {

    private static final int POLL_MILLIS = 100;
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_DETAIL_DEPTH = 4;
    private static final int MAX_DETAIL_NODES = 64;
    private static final int MAX_DETAIL_STRING = 256;
    private static final long MAX_EVIDENCE_BYTES = 2L * 1024L * 1024L;

    private PluginContext context;
    private Timer timer;
    private Path evidence;
    private String lastFingerprint = "";
    private boolean evidenceFull;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.evidence = context.paths().dataDir().resolve("history-probe.jsonl");
        context.logger().info("Read-only history manager validation probe initialized");
    }

    @Override
    public void enable() {
        onEdt(() -> {
            try {
                Files.createDirectories(evidence.getParent());
                if (Files.exists(evidence)) {
                    throw new IllegalStateException("History probe evidence already exists");
                }
                append(status("STARTED", "Read-only probe; use Cubism UI for Undo/Redo"));
                timer = new Timer(POLL_MILLIS, ignored -> poll());
                timer.setCoalesce(true);
                timer.start();
                poll();
            } catch (Exception exception) {
                context.logger().error("History probe startup failed", exception);
            }
        });
    }

    @Override
    public void disable() {
        stop("DISABLED");
    }

    @Override
    public void shutdown() {
        stop("SHUTDOWN");
    }

    private void stop(final String phase) {
        onEdt(() -> {
            if (timer != null) {
                timer.stop();
                timer = null;
            }
            try {
                append(status("STOPPED", phase));
            } catch (Exception exception) {
                context.logger().error("History probe terminal evidence failed", exception);
            }
        });
    }

    private void poll() {
        if (evidenceFull) return;
        try {
            final Snapshot snapshot = snapshot();
            if (!snapshot.fingerprint().equals(lastFingerprint)) {
                append(snapshot.json());
                lastFingerprint = snapshot.fingerprint();
            }
        } catch (Exception exception) {
            try {
                append(failure("POLL", exception));
            } catch (Exception writeFailure) {
                evidenceFull = true;
                context.logger().error("History probe evidence failed", writeFailure);
            }
        }
    }

    private Snapshot snapshot() throws Exception {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("History probe snapshot must run on EDT");
        }
        final Class<?> appClass = Class.forName(
            "com.live2d.cubism.CEAppCtrl",
            false,
            ClassLoader.getSystemClassLoader()
        );
        final Object app = appClass.getMethod("access$get_instance$cp").invoke(null);
        final Object document = invoke(app, "getCurrentDoc");
        if (document == null) throw new IllegalStateException("No active Cubism document");
        requireSameLoader(appClass, document.getClass());

        final Object currentMode = invoke(document, "getCurrentEditMode");
        final Object mainMode = optionalInvoke(document, "getEditMode_modeling");
        final Object documentManager = invoke(document, "getUndoManager");
        final Object currentManager = currentMode == null ? null : invoke(currentMode, "getUndoManager");
        final Object mainManager = mainMode == null ? null : invoke(mainMode, "getUndoManager");
        final Object linkedManager = currentMode == null ? null : optionalInvoke(currentMode, "getLinkedUndoManager");

        for (Object value : new Object[] {documentManager, currentManager, mainManager}) {
            if (value != null) requireSameLoader(appClass, value.getClass());
        }
        if (linkedManager != null) requireSameLoader(appClass, linkedManager.getClass());

        return new Snapshot(
            Instant.now().toString(),
            Thread.currentThread().getName(),
            true,
            loader(appClass),
            identity(document),
            currentMode == null ? "null" : currentMode.getClass().getName(),
            identity(currentMode),
            manager("DOCUMENT", documentManager),
            manager("CURRENT", currentManager),
            manager("MAIN", mainManager),
            manager("LINKED", linkedManager),
            sdkHistory()
        );
    }

    private SdkHistorySnapshot sdkHistory() {
        final HistorySnapshot history = context.cubism().history().snapshot();
        final int count = Math.min(history.entries().size(), MAX_ENTRIES);
        final List<SdkEntry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            final HistoryEntry entry = history.entries().get(index);
            entries.add(new SdkEntry(
                entry.index(),
                entry.entryId().map(value -> value.value()).orElse(""),
                boundedLabel(entry.label()),
                sdkDetailJson(entry.detail(), 0)
            ));
        }
        return new SdkHistorySnapshot(
            history.availability().name(),
            history.generation(),
            history.revision(),
            history.position(),
            history.canUndo(),
            history.canRedo(),
            boundedLabel(history.documentBindingId()),
            boundedLabel(history.managerBindingId()),
            history.entries().size(),
            List.copyOf(entries)
        );
    }

    private static String sdkDetailJson(
        final dev.turboism.sdk.cubism.history.HistoryEntryDetail detail,
        final int depth
    ) {
        final String targets = detail.targets().stream().map(target ->
            "{\"type\":\"" + json(target.type())
                + "\",\"id\":" + optionalJson(target.id())
                + ",\"displayName\":" + optionalJson(target.displayName()) + "}"
        ).reduce((left, right) -> left + "," + right).orElse("");
        final String changes = detail.changes().stream().map(change ->
            "{\"operation\":\"" + change.operation().name()
                + "\",\"targetIndex\":" + change.targetIndex().map(String::valueOf).orElse("null")
                + ",\"property\":" + optionalJson(change.property())
                + ",\"before\":" + optionalJson(change.before())
                + ",\"after\":" + optionalJson(change.after())
                + ",\"context\":" + sdkContextJson(change) + "}"
        ).reduce((left, right) -> left + "," + right).orElse("");
        String group = "null";
        if (depth < MAX_DETAIL_DEPTH && detail.group().isPresent()) {
            final var value = detail.group().orElseThrow();
            final String children = value.children().stream()
                .map(child -> sdkDetailJson(child, depth + 1))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
            group = "{\"groupId\":" + optionalJson(value.groupId())
                + ",\"observedChildCount\":" + value.observedChildCount()
                + ",\"truncated\":" + value.truncated()
                + ",\"children\":[" + children + "]}";
        }
        return "{\"summary\":\"" + json(detail.summary())
            + "\",\"detailLevel\":\"" + detail.detailLevel().name()
            + "\",\"origin\":\"" + detail.origin().kind().name()
            + "\",\"degradationCode\":" + optionalJson(detail.degradationCode())
            + ",\"targets\":[" + targets + "]"
            + ",\"changes\":[" + changes + "]"
            + ",\"group\":" + group + "}";
    }

    private static String sdkContextJson(final HistoryChange change) {
        final String coordinates = change.context().coordinates().stream().map(coordinate ->
            "{\"parameter\":{\"type\":\"" + json(coordinate.parameter().type())
                + "\",\"id\":" + optionalJson(coordinate.parameter().id())
                + ",\"displayName\":" + optionalJson(coordinate.parameter().displayName())
                + "},\"value\":\"" + json(coordinate.value()) + "\"}"
        ).reduce((left, right) -> left + "," + right).orElse("");
        return "{\"kind\":\"" + change.context().kind().name()
            + "\",\"formId\":" + optionalJson(change.context().formId())
            + ",\"coordinates\":[" + coordinates + "]}";
    }

    private static String optionalJson(final java.util.Optional<String> value) {
        return value.map(item -> "\"" + json(item) + "\"").orElse("null");
    }

    private static ManagerSnapshot manager(final String name, final Object manager) throws Exception {
        if (manager == null) return new ManagerSnapshot(name, "null", -1, false, false, 0, List.of());
        final List<?> raw = (List<?>) invoke(manager, "getUndoList");
        final int count = Math.min(raw.size(), MAX_ENTRIES);
        final List<Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            final Object entry = raw.get(index);
            entries.add(new Entry(
                index,
                boundedLabel(invoke(entry, "getPresentationName")),
                (Boolean) invoke(entry, "isSignificant"),
                nativeDetail(entry, 0, new java.util.IdentityHashMap<>(), new int[] {0})
            ));
        }
        return new ManagerSnapshot(
            name,
            identity(manager),
            (Integer) invoke(manager, "getCurrentPos"),
            (Boolean) invoke(manager, "canUndo"),
            (Boolean) invoke(manager, "canRedo"),
            raw.size(),
            List.copyOf(entries)
        );
    }

    static String boundedLabel(final Object value) {
        if (!(value instanceof String text)) return "";
        return bound(text, 160);
    }

    static String safeScalar(final Object value) {
        if (value == null) return "";
        if (value instanceof String text) return bound(text, MAX_DETAIL_STRING);
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return bound(String.valueOf(value), MAX_DETAIL_STRING);
        }
        if (value instanceof Enum<?> enumeration) return bound(enumeration.name(), MAX_DETAIL_STRING);
        return "";
    }

    private static String bound(final String value, final int codePoints) {
        return value.codePoints().limit(codePoints)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
    }

    private static NativeDetail nativeDetail(
        final Object entry,
        final int depth,
        final java.util.IdentityHashMap<Object, Boolean> visited,
        final int[] nodes
    ) {
        final String className = entry.getClass().getName();
        if (depth > MAX_DETAIL_DEPTH || nodes[0] >= MAX_DETAIL_NODES || visited.put(entry, Boolean.TRUE) != null) {
            return NativeDetail.degraded(className, "LIMIT", "history.detail.node-or-depth-limit");
        }
        nodes[0]++;
        try {
            return switch (className) {
                case "com.live2d.undo.GroupUndo" -> groupDetail(entry, className, depth, visited, nodes);
                case "com.live2d.undo.PropertyUndo" -> propertyDetail(entry, className);
                case "com.live2d.undo.SimpleUndo" -> simpleDetail(entry, className);
                case "com.live2d.undo.ListUndo" -> listDetail(entry, className);
                case "com.live2d.cubism.doc.model.ModelHandler$Undo_AddOrRemove_Parameter_" ->
                    addRemoveDetail(entry, className, "getChildItem");
                case "com.live2d.cubism.doc.model.ModelHandler$Undo_AddOrRemove_Part",
                     "com.live2d.cubism.doc.model.ModelHandler$Undo_AddOrRemove_Drawable",
                     "com.live2d.cubism.doc.model.ModelHandler$Undo_AddOrRemove_Deformer" ->
                    addRemoveDetail(entry, className, "getItem");
                case "com.live2d.cubism.doc.model.ModelHandler$Undo_AddOrRemove_ParameterGroup" ->
                    addRemoveDetail(entry, className, "getChildGroup");
                default -> NativeDetail.degraded(className, "UNSUPPORTED", "history.detail.class-unsupported");
            };
        } catch (Exception failure) {
            return NativeDetail.degraded(className, "FAILED", "history.detail.decoder-failed");
        }
    }

    private static NativeDetail groupDetail(
        final Object entry,
        final String className,
        final int depth,
        final java.util.IdentityHashMap<Object, Boolean> visited,
        final int[] nodes
    ) throws Exception {
        final List<?> children = (List<?>) invoke(entry, "getEditList");
        final int observed = (Integer) invoke(entry, "getEditCount");
        final ArrayList<String> childClasses = new ArrayList<>();
        final ArrayList<NativeDetail> childDetails = new ArrayList<>();
        final int count = Math.min(children.size(), MAX_DETAIL_NODES - nodes[0]);
        boolean truncated = observed != children.size() || children.size() > count;
        for (int index = 0; index < count; index++) {
            final NativeDetail child = nativeDetail(children.get(index), depth + 1, visited, nodes);
            childClasses.add(child.entryClass());
            childDetails.add(child);
            if (!child.degradationCode().isEmpty()) truncated = true;
        }
        return new NativeDetail(
            "GROUP", className, "", "", "", "", "", -1,
            observed, List.copyOf(childClasses), List.copyOf(childDetails), false, false,
            truncated ? "history.detail.group-truncated" : ""
        );
    }

    private static NativeDetail propertyDetail(final Object entry, final String className) throws Exception {
        final Object target = invoke(entry, "getObj");
        final Object previous = invoke(entry, "getPrevValue");
        final Object post = invoke(entry, "getPostValue");
        final String previousValue = safeScalar(previous);
        final String postValue = safeScalar(post);
        final String degradation = postValue.isEmpty()
            ? "history.detail.post-state-unavailable"
            : previous != null && previousValue.isEmpty()
                ? "history.detail.value-unsupported"
                : "history.detail.native-target-unattributed";
        return new NativeDetail(
            "PROPERTY", className, target == null ? "" : target.getClass().getName(),
            boundedLabel(invoke(entry, "getName")), previousValue, postValue, "", -1,
            0, List.of(), List.of(), previous != null, post != null, degradation
        );
    }

    private static NativeDetail simpleDetail(final Object entry, final String className) throws Exception {
        final Object target = invoke(entry, "getTargetData");
        final Object undo = invoke(entry, "getUndoData");
        final Object redo = invoke(entry, "getRedoData");
        return new NativeDetail(
            "SIMPLE", className, target == null ? "" : target.getClass().getName(),
            "", "", "", "", -1, 0, List.of(), List.of(), undo != null, redo != null,
            redo == null ? "history.detail.post-state-unavailable" : "history.detail.native-object-state-opaque"
        );
    }

    private static NativeDetail listDetail(final Object entry, final String className) throws Exception {
        final Object target = invoke(entry, "getTargetList");
        final Object undo = invoke(entry, "getTargetListEntriesForUndo");
        final Object redo = invoke(entry, "getTargetListEntriesForRedo");
        final String previous = undo instanceof List<?> list ? Integer.toString(list.size()) : "";
        final String post = redo instanceof List<?> list ? Integer.toString(list.size()) : "";
        return new NativeDetail(
            "LIST", className, target == null ? "" : target.getClass().getName(),
            "entryCount", previous, post, "", -1, 0, List.of(), List.of(), undo != null, redo != null,
            post.isEmpty() ? "history.detail.post-state-unavailable" : "history.detail.native-list-content-opaque"
        );
    }

    private static NativeDetail addRemoveDetail(
        final Object entry,
        final String className,
        final String itemMethod
    ) throws Exception {
        final Object owner = invoke(entry, "getOwnerObject");
        final Object item = invoke(entry, itemMethod);
        final boolean add = (Boolean) invoke(entry, "isAdd");
        return new NativeDetail(
            "ADD_REMOVE", className,
            item == null ? (owner == null ? "" : owner.getClass().getName()) : item.getClass().getName(),
            "", "", "", add ? "ADD" : "REMOVE", (Integer) invoke(entry, "getInsertIndex"),
            0, List.of(), List.of(), true, true, item == null ? "history.detail.target-unavailable" : ""
        );
    }

    private static void requireSameLoader(final Class<?> expected, final Class<?> actual) {
        if (expected.getClassLoader() != actual.getClassLoader()) {
            throw new IllegalStateException("Cubism host ClassLoader mismatch");
        }
    }

    private static String loader(final Class<?> type) {
        return type.getClassLoader() == null
            ? "bootstrap"
            : type.getClassLoader().getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(type.getClassLoader()));
    }

    private static Object invoke(final Object target, final String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    private static Object optionalInvoke(final Object target, final String method) throws Exception {
        try {
            return invoke(target, method);
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }

    private static String identity(final Object value) {
        return value == null ? "null" : value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    private void append(final String line) throws Exception {
        if (Files.exists(evidence) && Files.size(evidence) + line.length() + 1L > MAX_EVIDENCE_BYTES) {
            evidenceFull = true;
            throw new IllegalStateException("History probe evidence budget exhausted");
        }
        Files.writeString(
            evidence,
            line + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }

    private static String status(final String status, final String message) {
        return "{\"type\":\"status\",\"status\":\"" + json(status) + "\",\"message\":\"" + json(message) + "\"}";
    }

    private static String failure(final String phase, final Exception exception) {
        final Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        return "{\"type\":\"failure\",\"phase\":\"" + json(phase) + "\",\"errorType\":\""
            + json(cause.getClass().getName()) + "\",\"message\":\"" + json(boundedLabel(cause.getMessage())) + "\"}";
    }

    static String json(final String value) {
        final StringBuilder result = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            switch (character) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) result.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    else result.append(character);
                }
            }
        }
        return result.toString();
    }

    private static void onEdt(final Runnable operation) {
        if (SwingUtilities.isEventDispatchThread()) operation.run();
        else SwingUtilities.invokeLater(operation);
    }

    record Entry(int index, String label, boolean significant, NativeDetail detail) {
        String json() {
            return "{\"index\":" + index + ",\"label\":\"" + WindowsHistoryManagerValidationProbe.json(label)
                + "\",\"significant\":" + significant + ",\"nativeDetail\":" + detail.json() + "}";
        }
    }

    record NativeDetail(
        String family,
        String entryClass,
        String targetClass,
        String propertyName,
        String previousValue,
        String postValue,
        String direction,
        int index,
        int observedChildCount,
        List<String> childClasses,
        List<NativeDetail> childDetails,
        boolean undoAvailable,
        boolean postAvailable,
        String degradationCode
    ) {
        static NativeDetail degraded(final String entryClass, final String family, final String code) {
            return new NativeDetail(
                family, entryClass, "", "", "", "", "", -1, 0, List.of(), List.of(), false, false, code
            );
        }

        String json() {
            return "{\"family\":\"" + WindowsHistoryManagerValidationProbe.json(family)
                + "\",\"entryClass\":\"" + WindowsHistoryManagerValidationProbe.json(entryClass)
                + "\",\"targetClass\":\"" + WindowsHistoryManagerValidationProbe.json(targetClass)
                + "\",\"propertyName\":\"" + WindowsHistoryManagerValidationProbe.json(propertyName)
                + "\",\"previousValue\":\"" + WindowsHistoryManagerValidationProbe.json(previousValue)
                + "\",\"postValue\":\"" + WindowsHistoryManagerValidationProbe.json(postValue)
                + "\",\"direction\":\"" + WindowsHistoryManagerValidationProbe.json(direction)
                + "\",\"index\":" + index
                + ",\"observedChildCount\":" + observedChildCount
                + ",\"childClasses\":[" + childClasses.stream()
                    .map(value -> "\"" + WindowsHistoryManagerValidationProbe.json(value) + "\"")
                    .reduce((a, b) -> a + "," + b).orElse("") + "]"
                + ",\"childDetails\":[" + childDetails.stream()
                    .map(NativeDetail::json)
                    .reduce((a, b) -> a + "," + b).orElse("") + "]"
                + ",\"undoAvailable\":" + undoAvailable
                + ",\"postAvailable\":" + postAvailable
                + ",\"degradationCode\":\"" + WindowsHistoryManagerValidationProbe.json(degradationCode) + "\"}";
        }
    }

    record ManagerSnapshot(
        String name,
        String identity,
        int position,
        boolean canUndo,
        boolean canRedo,
        int totalEntries,
        List<Entry> entries
    ) {
        String json() {
            return "{\"name\":\"" + WindowsHistoryManagerValidationProbe.json(name)
                + "\",\"identity\":\"" + WindowsHistoryManagerValidationProbe.json(identity)
                + "\",\"position\":" + position
                + ",\"canUndo\":" + canUndo
                + ",\"canRedo\":" + canRedo
                + ",\"totalEntries\":" + totalEntries
                + ",\"truncated\":" + (totalEntries > entries.size())
                + ",\"entries\":[" + entries.stream().map(Entry::json).reduce((a, b) -> a + "," + b).orElse("") + "]}";
        }
    }

    record SdkEntry(int index, String entryId, String label, String detailJson) {
        String json() {
            return "{\"index\":" + index
                + ",\"entryId\":\"" + WindowsHistoryManagerValidationProbe.json(entryId)
                + "\",\"label\":\"" + WindowsHistoryManagerValidationProbe.json(label)
                + "\",\"detail\":" + detailJson + "}";
        }
    }

    record SdkHistorySnapshot(
        String availability,
        long generation,
        long revision,
        int position,
        boolean canUndo,
        boolean canRedo,
        String documentBindingId,
        String managerBindingId,
        int totalEntries,
        List<SdkEntry> entries
    ) {
        String json() {
            return "{\"availability\":\"" + WindowsHistoryManagerValidationProbe.json(availability)
                + "\",\"generation\":" + generation
                + ",\"revision\":" + revision
                + ",\"position\":" + position
                + ",\"canUndo\":" + canUndo
                + ",\"canRedo\":" + canRedo
                + ",\"documentBindingId\":\"" + WindowsHistoryManagerValidationProbe.json(documentBindingId)
                + "\",\"managerBindingId\":\"" + WindowsHistoryManagerValidationProbe.json(managerBindingId)
                + "\",\"totalEntries\":" + totalEntries
                + ",\"truncated\":" + (totalEntries > entries.size())
                + ",\"entries\":[" + entries.stream().map(SdkEntry::json)
                    .reduce((left, right) -> left + "," + right).orElse("") + "]}";
        }
    }

    record Snapshot(
        String observedAt,
        String thread,
        boolean edt,
        String hostLoader,
        String documentIdentity,
        String currentModeClass,
        String currentModeIdentity,
        ManagerSnapshot document,
        ManagerSnapshot current,
        ManagerSnapshot main,
        ManagerSnapshot linked,
        SdkHistorySnapshot sdkHistory
    ) {
        String fingerprint() {
            return documentIdentity + ":" + currentModeIdentity + ":" + document.identity() + ":" + current.identity()
                + ":" + main.identity() + ":" + linked.identity() + ":" + document.position() + ":" + current.position()
                + ":" + main.position() + ":" + linked.position() + ":" + document.totalEntries() + ":" + current.totalEntries()
                + ":" + sdkHistory.generation() + ":" + sdkHistory.revision() + ":" + sdkHistory.position()
                + ":" + sdkHistory.totalEntries();
        }

        String json() {
            return "{\"type\":\"snapshot\",\"observedAt\":\"" + WindowsHistoryManagerValidationProbe.json(observedAt)
                + "\",\"thread\":\"" + WindowsHistoryManagerValidationProbe.json(thread)
                + "\",\"edt\":" + edt
                + ",\"hostLoader\":\"" + WindowsHistoryManagerValidationProbe.json(hostLoader)
                + "\",\"documentIdentity\":\"" + WindowsHistoryManagerValidationProbe.json(documentIdentity)
                + "\",\"currentModeClass\":\"" + WindowsHistoryManagerValidationProbe.json(currentModeClass)
                + "\",\"currentModeIdentity\":\"" + WindowsHistoryManagerValidationProbe.json(currentModeIdentity)
                + "\",\"managers\":[" + document.json() + "," + current.json() + "," + main.json() + "," + linked.json() + "]"
                + ",\"sdkHistory\":" + sdkHistory.json() + "}";
        }
    }
}
