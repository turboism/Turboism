package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
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
    private static final long MAX_EVIDENCE_BYTES = 2L * 1024L * 1024L;

    private PluginContext context;
    private Timer timer;
    private Path evidence;
    private String lastFingerprint = "";
    private boolean evidenceFull;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.evidence = context.paths().logsDir().resolve("history-probe.jsonl");
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

        for (Object value : List.of(documentManager, currentManager, mainManager)) {
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
            manager("LINKED", linkedManager)
        );
    }

    private static ManagerSnapshot manager(final String name, final Object manager) throws Exception {
        if (manager == null) return new ManagerSnapshot(name, "null", -1, false, false, 0, List.of());
        final List<?> raw = (List<?>) invoke(manager, "getUndoList");
        final int count = Math.min(raw.size(), MAX_ENTRIES);
        final List<Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            final Object entry = raw.get(index);
            entries.add(new Entry(index, boundedLabel(invoke(entry, "getPresentationName")), (Boolean) invoke(entry, "isSignificant")));
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
        if (value == null) return "";
        final String text = value.toString();
        return text.codePoints().limit(160).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
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

    record Entry(int index, String label, boolean significant) {
        String json() {
            return "{\"index\":" + index + ",\"label\":\"" + WindowsHistoryManagerValidationProbe.json(label)
                + "\",\"significant\":" + significant + "}";
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
        ManagerSnapshot linked
    ) {
        String fingerprint() {
            return documentIdentity + ":" + currentModeIdentity + ":" + document.identity() + ":" + current.identity()
                + ":" + main.identity() + ":" + linked.identity() + ":" + document.position() + ":" + current.position()
                + ":" + main.position() + ":" + linked.position() + ":" + document.totalEntries() + ":" + current.totalEntries();
        }

        String json() {
            return "{\"type\":\"snapshot\",\"observedAt\":\"" + WindowsHistoryManagerValidationProbe.json(observedAt)
                + "\",\"thread\":\"" + WindowsHistoryManagerValidationProbe.json(thread)
                + "\",\"edt\":" + edt
                + ",\"hostLoader\":\"" + WindowsHistoryManagerValidationProbe.json(hostLoader)
                + "\",\"documentIdentity\":\"" + WindowsHistoryManagerValidationProbe.json(documentIdentity)
                + "\",\"currentModeClass\":\"" + WindowsHistoryManagerValidationProbe.json(currentModeClass)
                + "\",\"currentModeIdentity\":\"" + WindowsHistoryManagerValidationProbe.json(currentModeIdentity)
                + "\",\"managers\":[" + document.json() + "," + current.json() + "," + main.json() + "," + linked.json() + "]}";
        }
    }
}
