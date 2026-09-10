package dev.turboism.validation;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import javax.swing.SwingUtilities;

/**
 * Test-only, non-atomic observation of post-close native ownership handles.
 *
 * Read-only: the registry list and the controller view history are read, never written; no entry is removed,
 * nothing is disposed, no collection is requested and no object identity is compared through host equals,
 * hashCode or toString. Only scalars leave the capture frame.
 */
final class NativeCloseOwnershipObservation {
    private static final String[] COUNT_KEYS = {"registryEntries", "nullEntries", "historySize", "historyNullViews",
            "historyViewsWithDocument", "historyViewsReferringToDocument"};
    private static final int REFERRER_CLASS_LIMIT = 32;
    private static final int REFERRER_TEXT_LIMIT = 512;

    /** Holds only weak references plus the fixture path string; never a host object. */
    static final class WeakPair {
        private final WeakReference<Object> document, source;
        final String fixturePath;

        WeakPair(WeakReference<?> document, WeakReference<?> source, String fixturePath) {
            if (document == null || source == null || fixturePath == null || fixturePath.isEmpty()) {
                throw new IllegalArgumentException("incomplete weak pair");
            }
            this.document = referentOf(document);
            this.source = referentOf(source);
            this.fixturePath = fixturePath;
        }

        @SuppressWarnings("unchecked")
        private static WeakReference<Object> referentOf(WeakReference<?> reference) {
            return (WeakReference<Object>) reference;
        }

        boolean documentCleared() { return document.refersTo(null); }
        boolean sourceCleared() { return source.refersTo(null); }
        boolean documentIs(Object candidate) { return candidate != null && document.refersTo(candidate); }
        boolean sourceIs(Object candidate) { return candidate != null && source.refersTo(candidate); }
    }

    /** Only scalars escape capture; counters stay staged until the whole capture completed. */
    static final class Snapshot {
        final Properties values = new Properties();
        private final Properties counts = new Properties();

        Snapshot() {
            for (String key : COUNT_KEYS) counts.setProperty(key, "0");
            values.setProperty("schemaVersion", "1");
            values.setProperty("coverage", "post-close-native-ownership-handles");
            values.setProperty("consistency", "non-atomic-identity-checked");
            values.setProperty("status", "COMPLETE");
            values.setProperty("reason", "none");
        }

        void add(String key, long count) {
            counts.setProperty(key, Long.toString(Long.parseLong(counts.getProperty(key, "0")) + count));
        }

        /** Counts are published only for a complete capture; an incomplete one must not report zeros. */
        void commit() {
            for (String key : counts.stringPropertyNames()) values.setProperty(key, counts.getProperty(key));
        }

        void write(Properties target, String prefix) {
            for (String key : values.stringPropertyNames()) {
                target.setProperty(prefix + "." + key, values.getProperty(key));
            }
        }
    }

    record Limits(int entries, int views, int referrers, long durationNs) {
        Limits {
            if (entries < 0 || views < 0 || referrers < 0 || durationNs < 0) {
                throw new IllegalArgumentException("negative limits");
            }
        }
    }

    /** Package-private synthetic layout injection only; no alternate CLI or production host routing. */
    static final class Layout {
        final Class<?> registry, entry, controller, view;
        final Method entries, entryFile, entryPayload, entryLoaded, entryReferrers, currentDoc, currentView, viewDocument;
        final Field history;

        Layout(Class<?> registry, Class<?> entry, Class<?> controller, Class<?> view)
                throws ReflectiveOperationException {
            this.registry = registry;
            this.entry = entry;
            this.controller = controller;
            this.view = view;
            entries = method(registry, "a", ArrayList.class);
            entryFile = method(entry, "a", File.class);
            entryPayload = method(entry, "b", null);
            entryLoaded = method(entry, "c", boolean.class);
            entryReferrers = method(entry, "d", List.class);
            currentDoc = method(controller, "getCurrentDoc", null);
            currentView = method(controller, "getCurrentViewContext", null);
            viewDocument = method(view, "getDoc", null);
            history = field(controller, "viewContextHistory", ArrayList.class);
        }

        private static Method method(Class<?> owner, String name, Class<?> expected) throws ReflectiveOperationException {
            Method method = owner.getMethod(name);
            Class<?> actual = method.getReturnType();
            boolean shaped = method.getParameterCount() == 0 && !Modifier.isStatic(method.getModifiers());
            if (expected == null) {
                shaped = shaped && actual != void.class && !actual.isPrimitive();
            } else {
                shaped = shaped && (expected == actual || expected.isAssignableFrom(actual));
            }
            if (!shaped) throw new NoSuchMethodException("unsupported method shape: " + name);
            return method;
        }

        private static Field field(Class<?> owner, String name, Class<?> type) throws ReflectiveOperationException {
            Field field = owner.getDeclaredField(name);
            if (field.getType() != type || Modifier.isStatic(field.getModifiers()) || !field.trySetAccessible()) {
                throw new NoSuchFieldException("unsupported field shape: " + name);
            }
            return field;
        }
    }

    /** Compares host artifact locations as text without calling host equals or toString. */
    private static boolean sameCodeSource(java.security.CodeSource left, java.security.CodeSource right) {
        if (left == null || right == null) return false;
        java.net.URL a = left.getLocation(), b = right.getLocation();
        return a != null && b != null && a.toExternalForm().contentEquals(b.toExternalForm());
    }

    /** Caller has verified the exact JAR hash and that the task document is closed or open as expected. */
    static Snapshot captureHost(Object controller, Class<?> verifiedHost, WeakPair weak) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("observation requires EDT");
        long start = System.nanoTime();
        Snapshot result;
        try {
            ClassLoader loader = controller.getClass().getClassLoader();
            Class<?> registry = Class.forName("com.live2d.cubism.doc.a.e", false, loader);
            Class<?> entry = Class.forName("com.live2d.cubism.doc.a.b.a", false, loader);
            Class<?> view = Class.forName("com.live2d.cubism.view.context.CEViewContext", false, loader);
            var origin = verifiedHost.getProtectionDomain().getCodeSource();
            if (verifiedHost.getClassLoader() != loader
                    || !sameCodeSource(origin, controller.getClass().getProtectionDomain().getCodeSource())) {
                throw new ClassNotFoundException("verified host origin mismatch");
            }
            for (Class<?> type : new Class<?>[]{registry, entry, view}) {
                if (type.getClassLoader() != loader
                        || !sameCodeSource(origin, type.getProtectionDomain().getCodeSource())) {
                    throw new ClassNotFoundException("origin mismatch");
                }
            }
            Field singleton = registry.getDeclaredField("a");
            if (!Modifier.isStatic(singleton.getModifiers()) || singleton.getType() != registry
                    || !singleton.trySetAccessible()) {
                throw new NoSuchFieldException("unsupported registry singleton shape");
            }
            Object instance = singleton.get(null);
            if (instance == null || instance.getClass() != registry) {
                throw new NoSuchFieldException("registry instance absent");
            }
            Layout layout = new Layout(registry, entry, controller.getClass(), view);
            long remaining = Math.max(0, 250_000_000L - (System.nanoTime() - start));
            result = capture(instance, controller, layout, weak, new Limits(4096, 4096, 16384, remaining), System::nanoTime);
        } catch (ReflectiveOperationException | SecurityException problem) {
            result = new Snapshot();
            result.values.setProperty("status", "UNSUPPORTED");
            result.values.setProperty("reason", "layout-or-origin");
        }
        result.values.setProperty("durationNs", Long.toString(System.nanoTime() - start));
        return result;
    }

    private static final class Stop extends RuntimeException {
        final String status, reason;

        Stop(String status, String reason) { super(reason, null, false, false); this.status = status; this.reason = reason; }
    }

    private static void unsupported(boolean valid) {
        if (!valid) throw new Stop("UNSUPPORTED", "shape");
    }

    private static void stable(boolean valid) {
        if (!valid) throw new Stop("PARTIAL", "unstable");
    }

    private static void limit(boolean valid, String reason) {
        if (!valid) throw new Stop("PARTIAL", reason);
    }

    private static final class Budget {
        final Limits limits;
        final LongSupplier clock;
        final long start;
        int referrers;

        Budget(Limits limits, LongSupplier clock) { this.limits = limits; this.clock = clock; start = clock.getAsLong(); }

        void tick() { limit(clock.getAsLong() - start < limits.durationNs(), "time-limit"); }
    }

    private static Object read(Field field, Object target, Budget budget) throws IllegalAccessException {
        budget.tick();
        return field.get(target);
    }

    private static Object call(Method method, Object target, Budget budget) throws ReflectiveOperationException {
        budget.tick();
        return method.invoke(target);
    }

    private static List<?> list(Object value, Class<?> expected) {
        unsupported(value != null && expected.isInstance(value));
        return (List<?>) value;
    }

    private static Object[] copy(List<?> list, int maximum, String reason, Budget budget) {
        budget.tick();
        int size = list.size();
        limit(size <= maximum, reason);
        Object[] result = new Object[size];
        for (int i = 0; i < size; i++) { budget.tick(); result[i] = list.get(i); }
        verify(list, result, budget);
        return result;
    }

    private static void verify(List<?> list, Object[] values, Budget budget) {
        budget.tick();
        stable(list.size() == values.length);
        for (int i = 0; i < values.length; i++) { budget.tick(); stable(list.get(i) == values[i]); }
        stable(list.size() == values.length);
    }

    static Snapshot capture(Object registry, Object controller, Layout layout, WeakPair weak, Limits limits,
                            LongSupplier clock) {
        Snapshot result = new Snapshot();
        Budget budget = new Budget(limits, clock);
        try {
            unsupported(registry != null && registry.getClass() == layout.registry);
            unsupported(controller != null && controller.getClass() == layout.controller);
            unsupported(weak != null && weak.fixturePath != null);
            result.values.setProperty("documentWeakBefore", Boolean.toString(weak.documentCleared()));
            result.values.setProperty("sourceWeakBefore", Boolean.toString(weak.sourceCleared()));
            scanRegistry(registry, layout, weak, budget, result);
            scanController(controller, layout, weak, budget, result);
            result.values.setProperty("documentWeakAfter", Boolean.toString(weak.documentCleared()));
            result.values.setProperty("sourceWeakAfter", Boolean.toString(weak.sourceCleared()));
            result.commit();
        } catch (Stop stop) {
            result.values.setProperty("status", stop.status);
            result.values.setProperty("reason", stop.reason);
        } catch (IndexOutOfBoundsException | java.util.ConcurrentModificationException problem) {
            result.values.setProperty("status", "PARTIAL");
            result.values.setProperty("reason", "unstable");
        } catch (ReflectiveOperationException | IllegalArgumentException | ClassCastException problem) {
            result.values.setProperty("status", "UNSUPPORTED");
            result.values.setProperty("reason", "access");
        }
        result.values.setProperty("durationNs", Long.toString(clock.getAsLong() - budget.start));
        result.values.setProperty("referrersExamined", Integer.toString(budget.referrers));
        return result;
    }

    private static void scanRegistry(Object registry, Layout layout, WeakPair weak, Budget budget, Snapshot result)
            throws ReflectiveOperationException {
        List<?> entries = list(call(layout.entries, registry, budget), List.class);
        Object[] observed = copy(entries, budget.limits.entries(), "registry-limit", budget);
        result.add("registryEntries", observed.length);
        Object matched = null;
        for (Object entry : observed) {
            budget.tick();
            if (entry == null) { result.add("nullEntries", 1); continue; }
            unsupported(layout.entry.isInstance(entry));
            Object file = call(layout.entryFile, entry, budget);
            String path = file instanceof File candidate ? candidate.getPath() : null;
            if (path != null && weak.fixturePath.contentEquals(path)) {
                unsupported(matched == null);
                matched = entry;
            }
        }
        stable(call(layout.entries, registry, budget) == entries);
        result.values.setProperty("fixtureEntryPresent", Boolean.toString(matched != null));
        if (matched == null) return;
        result.values.setProperty("fixtureLoadedFlag", Boolean.toString((Boolean) call(layout.entryLoaded, matched, budget)));
        Object wrapper = call(layout.entryPayload, matched, budget);
        result.values.setProperty("fixtureWrapperPresent", Boolean.toString(wrapper != null));
        Method source = sourceAccessor(matched.getClass());
        result.values.setProperty("fixtureSourceAccessor", source == null ? "absent" : "f");
        if (source != null) {
            Object carried = call(source, matched, budget);
            result.values.setProperty("fixtureCarriedSourcePresent", Boolean.toString(carried != null));
            result.values.setProperty("fixtureCarriedSourceIsRecordedSource", Boolean.toString(weak.sourceIs(carried)));
        }
        List<?> referrers = list(call(layout.entryReferrers, matched, budget), List.class);
        int size = referrers.size();
        limit(size <= budget.limits.referrers() - budget.referrers, "referrer-limit");
        TreeSet<String> names = new TreeSet<>();
        for (int i = 0; i < size; i++) {
            budget.tick();
            budget.referrers++;
            Object referrer = referrers.get(i);
            if (referrer == null || names.size() >= REFERRER_CLASS_LIMIT) continue;
            names.add(referrer.getClass().getName());
        }
        result.values.setProperty("fixtureReferrerCount", Long.toString(size));
        result.values.setProperty("fixtureReferrerClasses", summarise(names));
    }

    /** The base payload accessor returns the loader wrapper; only the concrete model entry exposes the source itself. */
    private static Method sourceAccessor(Class<?> concrete) {
        try {
            Method method = concrete.getMethod("f");
            boolean shaped = method.getParameterCount() == 0 && !Modifier.isStatic(method.getModifiers())
                    && method.getReturnType().getName().contentEquals("com.live2d.cubism.doc.model.CModelSource");
            return shaped ? method : null;
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static void scanController(Object controller, Layout layout, WeakPair weak, Budget budget, Snapshot result)
            throws ReflectiveOperationException {
        result.values.setProperty("currentDocNull", Boolean.toString(call(layout.currentDoc, controller, budget) == null));
        result.values.setProperty("currentViewNull", Boolean.toString(call(layout.currentView, controller, budget) == null));
        List<?> history = list(read(layout.history, controller, budget), List.class);
        Object[] observed = copy(history, budget.limits.views(), "history-limit", budget);
        result.add("historySize", observed.length);
        long referring = 0;
        for (Object view : observed) {
            budget.tick();
            if (view == null) { result.add("historyNullViews", 1); continue; }
            unsupported(layout.view.isInstance(view));
            Object document = call(layout.viewDocument, view, budget);
            if (document != null) result.add("historyViewsWithDocument", 1);
            if (weak.documentIs(document)) referring++;
        }
        stable(read(layout.history, controller, budget) == history);
        result.add("historyViewsReferringToDocument", referring);
    }

    private static String summarise(TreeSet<String> names) {
        StringBuilder text = new StringBuilder();
        for (String name : names) {
            if (text.length() > 0) text.append(',');
            text.append(name);
            if (text.length() >= REFERRER_TEXT_LIMIT) break;
        }
        return text.length() <= REFERRER_TEXT_LIMIT ? text.substring(0) : text.substring(0, REFERRER_TEXT_LIMIT);
    }
}
