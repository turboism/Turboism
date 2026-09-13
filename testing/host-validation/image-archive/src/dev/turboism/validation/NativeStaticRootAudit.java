package dev.turboism.validation;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Properties;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import javax.swing.SwingUtilities;

/**
 * Test-only, bounded audit of the reviewed Java static footholds of the native model graph.
 *
 * Read-only: static fields are read, never written; a non-null holder is scanned one hop further for fields whose
 * declared type is the recorded source or the closed document, and only identity comparisons through {@code refersTo}
 * are used. No object is retained: every result property is a scalar or a bounded, sanitised label list.
 *
 * The reviewed field list is frozen in source. Reading a static field may initialize its declaring class; a class
 * initializer cannot obtain the live model source of an already-open document, so an identity match is always caused by
 * an application write, never by this audit. See specs/029-static-root-audit/ for the argument and its limits.
 */
final class NativeStaticRootAudit {
    private static final String SOURCE = "com.live2d.cubism.doc.model.CModelSource";
    private static final String DOCUMENT = "com.live2d.cubism.doc.modeling.CModelingDocument";
    private static final int HOLDER_LIMIT = 64;
    private static final int FIELD_LIMIT = 128;
    private static final int LABEL_LIMIT = 512;
    private static final int SUPER_LIMIT = 4;

    /** Direct static holders of the recorded native model source, reviewed at the exact JAR hash. */
    private static final String[][] DIRECT = {
            {"com.live2d.cubism.appCtrlImpl.ui.a.a", "d"},
            {"com.live2d.cubism.view.palette.parameter.dialog.G", "d"},
            {"com.live2d.cubism.view.palette.parameter.dialog.af", "i"},
            {"com.live2d.cubism.view.palette.parts.a.a", "d"},
    };

    /**
     * Reviewed singleton/state statics that can reach the source without passing through the application controller
     * singleton. Each entry is {@code owner#field}; the value is scanned one hop for a source or document field.
     */
    private static final String[] HOLDERS = {
            "com.live2d.cubism.appCtrlImpl.ar#a", "com.live2d.cubism.appCtrlImpl.ar#f",
            "com.live2d.cubism.appCtrlImpl.ar#g", "com.live2d.cubism.appCtrlImpl.ui.motionSync.e#q",
            "com.live2d.cubism.doc.model.ax#q", "com.live2d.cubism.doc.model.deformer.autoYure.f#G",
            "com.live2d.cubism.doc.model.deformer.autoface.w#a", "com.live2d.cubism.doc.model.deformer.autoface.w#b",
            "com.live2d.cubism.doc.model.extension.rotate3d.B#p", "com.live2d.cubism.doc.model.morphTarget.MorphTargetConstraintEditDialog#a",
            "com.live2d.cubism.doc.model.morphTarget.MorphTargetConstraintEditDialog#c",
            "com.live2d.cubism.doc.modeling.ui.template.G#m",
            "com.live2d.cubism.doc.modeling.ui.viewerForOriginalWorkflow.data.hitArea.a#lastSelectedHitArea",
            "com.live2d.cubism.doc.webSocket.l#i", "com.live2d.cubism.doc.webSocket.l#j",
            "com.live2d.cubism.doc.webSocket.x#a", "com.live2d.cubism.doc.webSocket.x#b",
            "com.live2d.cubism.setting.AppSetting#INSTANCE", "com.live2d.cubism.setting.AppSetting#gui",
            "com.live2d.cubism.view.context.action.C$c#a", "com.live2d.cubism.view.context.action.C$c#d",
            "com.live2d.cubism.view.palette.parameter.dialog.av#a", "com.live2d.cubism.view.palette.parameter.dialog.av#b",
            "com.live2d.cubism.view.palette.tool.toolMode.artPath.G#a", "com.live2d.cubism.view.palette.tool.toolMode.artPath.G#b",
            "com.live2d.cubism.view.palette.tool.toolMode.artPath.y#a", "com.live2d.cubism.view.palette.tool.toolMode.artPath.y#b",
            "com.live2d.cubism.view.palette.tool.toolMode.artPath.y#c", "s#a", "s#b",
    };

    private static final String[] COUNT_KEYS = {"examined", "read", "unreadable", "nonNull", "scannedFields"};

    private NativeStaticRootAudit() {
    }

    /** Only scalars escape the audit; counters stay staged until the whole audit completed. */
    static final class Result {
        final Properties values = new Properties();
        private final Properties counts = new Properties();
        private final TreeSet<String> sourceHolders = new TreeSet<>();
        private final TreeSet<String> documentHolders = new TreeSet<>();

        Result() {
            for (String key : COUNT_KEYS) counts.setProperty(key, "0");
            values.setProperty("schemaVersion", "1");
            values.setProperty("coverage", "reviewed-java-static-footholds");
            values.setProperty("consistency", "non-atomic-identity-checked");
            values.setProperty("status", "COMPLETE");
            values.setProperty("reason", "none");
        }

        void add(String key, long count) {
            counts.setProperty(key, Long.toString(Long.parseLong(counts.getProperty(key, "0")) + count));
        }

        void note(boolean source, boolean document, String label) {
            if (source) sourceHolders.add(label);
            if (document) documentHolders.add(label);
        }

        /** Counts and labels are published only for a complete audit; a partial one must not report zeros. */
        void commit() {
            for (String key : counts.stringPropertyNames()) values.setProperty(key, counts.getProperty(key));
            values.setProperty("holdersWithRecordedSource", Integer.toString(sourceHolders.size()));
            values.setProperty("holdersWithRecordedDocument", Integer.toString(documentHolders.size()));
            values.setProperty("recordedSourceHolders", join(sourceHolders));
            values.setProperty("recordedDocumentHolders", join(documentHolders));
        }

        void write(Properties target, String prefix) {
            for (String key : values.stringPropertyNames()) {
                target.setProperty(prefix + "." + key, values.getProperty(key));
            }
        }
    }

    record Limits(int holders, int fields, long durationNs) {
        Limits {
            if (holders < 0 || fields < 0 || durationNs < 0) {
                throw new IllegalArgumentException("negative limits");
            }
        }
    }

    /** Caller has verified the exact JAR hash and that the document is open or closed as expected. */
    static Result auditHost(Object controller, Class<?> verifiedHost, NativeCloseOwnershipObservation.WeakPair weak) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("audit requires EDT");
        long start = System.nanoTime();
        Result result;
        try {
            ClassLoader loader = verifiedHost.getClassLoader();
            if (controller.getClass().getClassLoader() != loader) throw new ClassNotFoundException("origin mismatch");
            Class<?> source = Class.forName(SOURCE, false, loader);
            Class<?> document = Class.forName(DOCUMENT, false, loader);
            var origin = verifiedHost.getProtectionDomain().getCodeSource();
            for (Class<?> type : new Class<?>[]{source, document}) {
                if (type.getClassLoader() != loader || !same(origin, type)) {
                    throw new ClassNotFoundException("origin mismatch");
                }
            }
            long remaining = Math.max(0, 250_000_000L - (System.nanoTime() - start));
            result = audit(loader, source, document, weak, DIRECT, HOLDERS,
                    new Limits(HOLDER_LIMIT, FIELD_LIMIT, remaining), System::nanoTime);
        } catch (ReflectiveOperationException | SecurityException problem) {
            result = new Result();
            result.values.setProperty("status", "UNSUPPORTED");
            result.values.setProperty("reason", "layout-or-origin");
        }
        result.values.setProperty("durationNs", Long.toString(System.nanoTime() - start));
        return result;
    }

    /** Field lists are injected only for synthetic regression; the exact-host path always uses the frozen constants. */
    static Result audit(ClassLoader loader, Class<?> source, Class<?> document,
                        NativeCloseOwnershipObservation.WeakPair weak, String[][] direct, String[] holders,
                        Limits limits, LongSupplier clock) {
        Result result = new Result();
        long start = clock.getAsLong();
        try {
            for (String[] entry : direct) {
                tick(start, limits, clock);
                result.add("examined", 1);
                Class<?> owner = Class.forName(entry[0], false, loader);
                if (owner.getClassLoader() != loader) throw new IllegalStateException("foreign loader");
                Field field = declaredField(owner, entry[1]);
                if (field == null) { result.add("unreadable", 1); continue; }
                Object value = read(field, null, start, limits, clock);
                result.add("read", 1);
                if (value != null) result.add("nonNull", 1);
                result.note(weak.sourceIs(value), weak.documentIs(value), entry[0] + "#" + entry[1]);
            }
            for (String entry : holders) {
                tick(start, limits, clock);
                result.add("examined", 1);
                int hash = entry.indexOf('#');
                Class<?> owner = Class.forName(entry.substring(0, hash), false, loader);
                if (owner.getClassLoader() != loader) throw new IllegalStateException("foreign loader");
                Field field = declaredField(owner, entry.substring(hash + 1));
                if (field == null) { result.add("unreadable", 1); continue; }
                Object holder = read(field, null, start, limits, clock);
                result.add("read", 1);
                if (holder == null) continue;
                result.add("nonNull", 1);
                result.note(weak.sourceIs(holder), weak.documentIs(holder), entry);
                int depth = 0;
                for (Class<?> type = holder.getClass(); type != null && type != Object.class && depth < SUPER_LIMIT;
                        type = type.getSuperclass(), depth++) {
                    for (Field candidate : type.getDeclaredFields()) {
                        tick(start, limits, clock);
                        if (Modifier.isStatic(candidate.getModifiers())) continue;
                        String declared = candidate.getType().getName();
                        if (!declared.contentEquals(SOURCE) && !declared.contentEquals(DOCUMENT)) continue;
                        if (!candidate.trySetAccessible()) { result.add("unreadable", 1); continue; }
                        result.add("scannedFields", 1);
                        if (limits.fields() < Long.parseLong(result.counts.getProperty("scannedFields"))) {
                            throw new Stop("PARTIAL", "field-limit");
                        }
                        Object value = read(candidate, holder, start, limits, clock);
                        result.note(weak.sourceIs(value), weak.documentIs(value), entry + "->" + candidate.getName());
                    }
                }
                if (limits.holders() < Long.parseLong(result.counts.getProperty("examined"))) {
                    throw new Stop("PARTIAL", "holder-limit");
                }
            }
            result.commit();
        } catch (Stop stop) {
            result.values.setProperty("status", stop.status);
            result.values.setProperty("reason", stop.reason);
        } catch (ReflectiveOperationException | IllegalArgumentException | IllegalStateException problem) {
            result.values.setProperty("status", "UNSUPPORTED");
            result.values.setProperty("reason", "access");
        }
        result.values.setProperty("durationNs", Long.toString(clock.getAsLong() - start));
        return result;
    }

    private static final class Stop extends RuntimeException {
        final String status, reason;

        Stop(String status, String reason) { super(reason, null, false, false); this.status = status; this.reason = reason; }
    }

    private static void tick(long start, Limits limits, LongSupplier clock) {
        if (clock.getAsLong() - start >= limits.durationNs()) throw new Stop("PARTIAL", "time-limit");
    }

    private static Field declaredField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            boolean shaped = Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()
                    && field.trySetAccessible();
            return shaped ? field : null;
        } catch (NoSuchFieldException | RuntimeException absent) {
            return null;
        }
    }

    /** A null target reads a static field; a holder reads that instance's field. */
    private static Object read(Field field, Object target, long start, Limits limits, LongSupplier clock)
            throws IllegalAccessException {
        tick(start, limits, clock);
        return field.get(target);
    }

    private static boolean same(java.security.CodeSource origin, Class<?> type) {
        if (origin == null) return false;
        var actual = type.getProtectionDomain().getCodeSource();
        if (actual == null) return false;
        var left = origin.getLocation();
        var right = actual.getLocation();
        return left != null && right != null && left.toExternalForm().contentEquals(right.toExternalForm());
    }

    private static String join(TreeSet<String> names) {
        StringBuilder text = new StringBuilder();
        for (String name : names) {
            if (text.length() > 0) text.append(',');
            text.append(name);
            if (text.length() >= LABEL_LIMIT) break;
        }
        return text.length() <= LABEL_LIMIT ? text.substring(0) : text.substring(0, LABEL_LIMIT);
    }
}
