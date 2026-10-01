package dev.turboism.validation.tlindex.diagnostic;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/**
 * Owned-JVM research admission; not wired into production or an official host.
 * A match describes bytes at this collector's position. Instrumentation does
 * not guarantee that another transformer cannot register behind the collector.
 * That lifecycle/ordering boundary must be closed before production adoption.
 */
final class LiveDefinitionAdmission implements AutoCloseable {
    static final class Gate implements BooleanSupplier {
        private volatile boolean admitted;
        private volatile String reason = "UNVERIFIED";
        private volatile Thread captureOwner;
        private IdentityHashMap<Class<?>, Integer> callbacks;
        private boolean invalidated;

        @Override public boolean getAsBoolean() { return admitted; }
        String reason() { return reason; }
        private synchronized void event(Class<?> type) {
            admitted = false;
            if (captureOwner == Thread.currentThread() && callbacks != null && callbacks.containsKey(type)) {
                callbacks.put(type, callbacks.get(type) + 1);
            } else {
                invalidated = true; reason = "DEFINITION_CHANGED";
            }
        }
        private synchronized void revoke(String why) {
            admitted = false; invalidated = true; reason = why;
        }
    }

    private final Instrumentation instrumentation;
    // Class keys have JDK identity equality; values contain only weak gate links.
    // Neither side registers a strong application class or class-loader root.
    private final WeakHashMap<Class<?>, List<WeakReference<Gate>>> watches = new WeakHashMap<>();
    private final ClassFileTransformer observer = new ClassFileTransformer() {
        @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                ProtectionDomain domain, byte[] bytes) {
            if (redefined == null) return null;
            synchronized (watches) {
                List<WeakReference<Gate>> gates = watches.get(redefined);
                if (gates != null) {
                    gates.removeIf(reference -> reference.get() == null);
                    for (WeakReference<Gate> reference : gates) {
                        Gate gate = reference.get(); if (gate != null) gate.event(redefined);
                    }
                }
            }
            return null;
        }
    };
    private volatile boolean closed;
    private final boolean registered;

    LiveDefinitionAdmission(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        boolean installed = false;
        if (instrumentation != null && instrumentation.isRetransformClassesSupported()) {
            try {
                instrumentation.addTransformer(observer, true); installed = true;
            } catch (RuntimeException unavailable) { installed = false; }
        }
        registered = installed;
    }

    Gate capture(Class<?>[] dependencies, Map<String, String> expected) {
        Gate gate = new Gate();
        if (!registered) { gate.revoke("RETRANSFORM_UNAVAILABLE"); return gate; }
        if (closed) { gate.revoke("CLOSED"); return gate; }
        if (dependencies == null || dependencies.length == 0 || expected == null) {
            gate.revoke("DEPENDENCIES_MISSING"); return gate;
        }
        Class<?>[] actual = dependencies.clone();
        Map<String, String> reviewed;
        try { reviewed = Map.copyOf(expected); }
        catch (RuntimeException invalid) { gate.revoke("DEPENDENCY_MAP_REJECTED"); return gate; }
        if (actual.length != reviewed.size()) { gate.revoke("DEPENDENCY_SET_INCOMPLETE"); return gate; }
        IdentityHashMap<Class<?>, String> wanted = new IdentityHashMap<>();
        IdentityHashMap<Class<?>, String> captured = new IdentityHashMap<>();
        try {
            HashSet<String> names = new HashSet<>();
            for (Class<?> type : actual) {
                if (type == null || wanted.containsKey(type) || !names.add(type.getName()) || reviewed.get(type.getName()) == null
                        || !instrumentation.isModifiableClass(type)) {
                    gate.revoke("DEPENDENCY_REJECTED"); return gate;
                }
                wanted.put(type, reviewed.get(type.getName()));
            }
        } catch (RuntimeException uncertain) { gate.revoke("DEPENDENCY_QUERY_FAILED"); return gate; }
        synchronized (gate) {
            gate.captureOwner = Thread.currentThread(); gate.callbacks = new IdentityHashMap<>();
            for (Class<?> type : actual) gate.callbacks.put(type, 0);
        }
        synchronized (watches) {
            if (closed) { gate.revoke("CLOSED"); clearCapture(gate); return gate; }
            for (Class<?> type : actual) watches.computeIfAbsent(type, ignored -> new ArrayList<>()).add(new WeakReference<>(gate));
        }
        ClassFileTransformer collector = new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                    ProtectionDomain domain, byte[] bytes) {
                if (Thread.currentThread() != gate.captureOwner || !wanted.containsKey(redefined)) return null;
                try {
                    if (name == null || !name.replace('/', '.').equals(redefined.getName()) || bytes == null
                            || captured.containsKey(redefined)) {
                        gate.revoke("CAPTURE_IDENTITY_REJECTED"); return null;
                    }
                    captured.put(redefined, DefinitionFingerprint.of(bytes));
                } catch (RuntimeException refused) { gate.revoke("CAPTURE_BYTES_REJECTED"); }
                // Capture observes bytes but never edits or returns replacement code.
                return null;
            }
        };
        boolean collectorRegistered = false;
        try {
            instrumentation.addTransformer(collector, true); collectorRegistered = true;
            instrumentation.retransformClasses(actual);
            synchronized (watches) {
                synchronized (gate) {
                    if (!closed && !gate.invalidated && captured.size() == wanted.size()
                            && wanted.entrySet().stream().allMatch(e -> e.getValue().equals(captured.get(e.getKey())))
                            && gate.callbacks.values().stream().allMatch(count -> count == 1)) {
                        gate.captureOwner = null; gate.callbacks = null;
                        gate.reason = "ACTUAL_DEFINITION_MATCH"; gate.admitted = true;
                    } else if (!gate.invalidated) {
                        gate.revoke(closed ? "CLOSED" : "DEFINITION_MISMATCH_OR_INCOMPLETE");
                    }
                }
            }
        } catch (Exception failure) { gate.revoke("CAPTURE_FAILED"); }
        finally {
            if (collectorRegistered) {
                try {
                    if (!instrumentation.removeTransformer(collector)) gate.revoke("CAPTURE_REMOVAL_FAILED");
                } catch (RuntimeException failure) { gate.revoke("CAPTURE_REMOVAL_FAILED"); }
            }
            clearCapture(gate);
            if (!gate.getAsBoolean()) removeWatchLinks(gate);
        }
        return gate;
    }

    private static void clearCapture(Gate gate) {
        synchronized (gate) { gate.captureOwner = null; gate.callbacks = null; }
    }
    private void removeWatchLinks(Gate target) {
        synchronized (watches) {
            for (List<WeakReference<Gate>> list : watches.values())
                list.removeIf(reference -> reference.get() == null || reference.get() == target);
            watches.values().removeIf(List::isEmpty);
        }
    }
    int watchedClassCount() { synchronized (watches) { return watches.size(); } }

    @Override public void close() {
        synchronized (watches) {
            if (closed) return;
            closed = true;
            for (List<WeakReference<Gate>> gates : watches.values()) for (WeakReference<Gate> reference : gates) {
                Gate gate = reference.get(); if (gate != null) gate.revoke("CLOSED");
            }
            watches.clear();
        }
        if (registered) instrumentation.removeTransformer(observer);
    }
}
