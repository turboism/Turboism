package dev.turboism.adapter.cubism.mesh;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.jar.JarFile;

/**
 * Definition ownership and full-operation leases for the guarded lazy-edge weave.
 *
 * <p>The supported protocol has one trusted premain Agent, disabled dynamic attach,
 * and no native Agent. Bootstrap must pass only {@link #instrumentation()} to all
 * subsequent installers and retain no usable raw handle. This is not a security
 * boundary against hostile in-process code, JNI, or an escaped raw handle.</p>
 *
 * <p>Capture and instrumentation mutations take an exclusive lock before entering
 * the JVM. An optimized operation must hold a {@link Lease} for its entire duration.
 * Definition changes wait outside JVM callbacks, then permanently revoke existing
 * gates before proceeding. A Boolean admission observation is not an execution lease.</p>
 */
public final class TriangulationDefinitionLifecycle implements AutoCloseable {
    interface Ownership {
        TriangulationDefinitionLifecycle lifecycle();
    }

    private static final StackWalker STACK = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private final Instrumentation raw;
    private final Instrumentation owned;
    private final String startupReason;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final List<WeakReference<Gate>> gates = new ArrayList<>();
    private boolean closed;
    private boolean captureBroken;

    private TriangulationDefinitionLifecycle(Instrumentation supplied, String reason) {
        raw = Objects.requireNonNull(supplied, "instrumentation");
        startupReason = reason;
        owned = (Instrumentation) Proxy.newProxyInstance(
                TriangulationDefinitionLifecycle.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class, Ownership.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Ownership.class) return this;
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == arguments[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "TriangulationOwnedInstrumentation";
                        };
                    }
                    boolean mutation =
                            switch (method.getName()) {
                                case "addTransformer",
                                        "removeTransformer",
                                        "retransformClasses",
                                        "redefineClasses",
                                        "setNativeMethodPrefix",
                                        "redefineModule" -> true;
                                default -> false;
                            };
                    if (mutation) {
                        rejectCallbackOrUpgrade();
                        lock.writeLock().lock();
                    }
                    try {
                        if (mutation) revokeAll("OWNED_DEFINITION_MUTATION");
                        try {
                            return method.invoke(raw, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    } finally {
                        if (mutation) lock.writeLock().unlock();
                    }
                });
    }

    /** Call only from the sole trusted Agent's premain, before any installer receives a handle. */
    public static TriangulationDefinitionLifecycle forPremain(Instrumentation supplied, String premainClass) {
        String reason;
        try {
            List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
            boolean attachDisabled =
                    Boolean.parseBoolean(ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                            .getVMOption("DisableAttachMechanism")
                            .getValue());
            reason = startupOptionsReason(arguments, attachDisabled);
            if (reason.equals("SUPPORTED_OWNED_PREMAIN")) {
                String option = arguments.stream()
                        .filter(a -> a.startsWith("-javaagent:"))
                        .findFirst()
                        .orElseThrow();
                String path = option.substring("-javaagent:".length()).split("=", 2)[0];
                try (JarFile jar = new JarFile(Path.of(path).toFile())) {
                    var attributes = jar.getManifest().getMainAttributes();
                    if (!Objects.requireNonNull(premainClass, "premainClass")
                                    .equals(attributes.getValue("Premain-Class"))
                            || !"true".equalsIgnoreCase(attributes.getValue("Can-Retransform-Classes"))) {
                        reason = "AGENT_MANIFEST_REJECTED";
                    }
                }
            }
            if (!supplied.isRetransformClassesSupported()) reason = "RETRANSFORM_UNAVAILABLE";
        } catch (Exception | LinkageError unavailable) {
            reason = "STARTUP_UNVERIFIED";
        }
        return new TriangulationDefinitionLifecycle(supplied, reason);
    }

    // Package-visible for parser checks; real admission always queries the actual VM and Agent JAR.
    static String startupOptionsReason(List<String> arguments, boolean attachDisabled) {
        if (!attachDisabled) return "DYNAMIC_ATTACH_ENABLED";
        if (arguments.stream().filter(a -> a.startsWith("-javaagent:")).count() != 1) return "AGENT_SET_REJECTED";
        if (arguments.stream()
                .anyMatch(a -> a.startsWith("-agentlib:")
                        || a.startsWith("-agentpath:")
                        || a.startsWith("-Xrun")
                        || a.equals("-Xdebug")
                        || a.startsWith("-Xbootclasspath")
                        || a.startsWith("--patch-module")
                        || a.startsWith("--upgrade-module-path")
                        || a.startsWith("-Djava.system.class.loader="))) return "EXTERNAL_DEFINITION_SOURCE";
        return "SUPPORTED_OWNED_PREMAIN";
    }

    /** Verified startup admission or the stable reason this protocol is unavailable. */
    public String startupReason() {
        return startupReason;
    }

    /** Null means there is no verified ownership protocol for the supplied handle. */
    public static TriangulationDefinitionLifecycle ownedBy(Instrumentation instrumentation) {
        return instrumentation instanceof Ownership ownership ? ownership.lifecycle() : null;
    }

    /** Unverified startup retains ordinary instrumentation behavior and can never admit a gate. */
    public Instrumentation instrumentation() {
        return startupReason.equals("SUPPORTED_OWNED_PREMAIN") ? owned : raw;
    }

    /** A captured definition set; acquiring a lease is required before optimized execution. */
    public static final class Gate {
        private final TriangulationDefinitionLifecycle owner;
        private volatile boolean admitted;
        private Map<String, WeakReference<Class<?>>> covered = Map.of();
        private volatile String reason = "UNVERIFIED";

        private Gate(TriangulationDefinitionLifecycle owner) {
            this.owner = owner;
        }
        /** Latest capture or permanent revocation reason; this observation grants no lease. */
        public String reason() {
            return reason;
        }

        boolean covers(Class<?> type) {
            if (!admitted || type == null) return false;
            WeakReference<Class<?>> actual = covered.get(type.getName());
            return actual != null && actual.get() == type;
        }

        /** Null means native fallback. The lease is confined to its acquiring thread. */
        public Lease acquire() {
            if (!admitted || inTransformerCallback()) return null;
            owner.lock.readLock().lock();
            if (!admitted || owner.closed || owner.captureBroken) {
                owner.lock.readLock().unlock();
                return null;
            }
            try {
                return new Lease(owner);
            } catch (RuntimeException | Error failure) {
                owner.lock.readLock().unlock();
                throw failure;
            }
        }

        private void revoke(String why) {
            admitted = false;
            reason = why;
        }
    }

    /** Thread-confined protection against owned definition mutations until close. */
    public static final class Lease implements AutoCloseable {
        private final TriangulationDefinitionLifecycle owner;
        private final Thread thread = Thread.currentThread();
        private boolean closed;

        private Lease(TriangulationDefinitionLifecycle owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (Thread.currentThread() != thread)
                throw new IllegalStateException("definition lease belongs to another thread");
            if (!closed) {
                closed = true;
                owner.lock.readLock().unlock();
            }
        }
    }

    /**
     * Capture at the last owned capable transformer under exclusive registration/definition
     * ownership. The fingerprinter is trusted code and receives a defensive copy. No resources
     * are used as substitutes for live definitions. Gates hold no application Class or loader.
     */
    public Gate capture(Class<?>[] dependencies, Map<String, String> expected, Function<byte[], String> fingerprinter) {
        Gate gate = new Gate(this);
        if (!startupReason.equals("SUPPORTED_OWNED_PREMAIN")) {
            gate.revoke(startupReason);
            return gate;
        }
        if (dependencies == null || dependencies.length == 0 || expected == null || fingerprinter == null) {
            gate.revoke("DEPENDENCIES_MISSING");
            return gate;
        }
        try {
            rejectCallbackOrUpgrade();
        } catch (IllegalStateException refused) {
            gate.revoke("CAPTURE_CONTEXT_REJECTED");
            return gate;
        }
        lock.writeLock().lock();
        Capture collector = new Capture(fingerprinter);
        boolean registered = false;
        try {
            if (closed || captureBroken) {
                gate.revoke("CAPTURE_UNAVAILABLE");
                return gate;
            }
            Map<String, String> reviewed = Map.copyOf(expected);
            Class<?>[] actual = dependencies.clone();
            if (actual.length != reviewed.size()) {
                gate.revoke("DEPENDENCY_SET_INCOMPLETE");
                return gate;
            }
            HashSet<String> names = new HashSet<>();
            for (Class<?> type : actual) {
                if (type == null
                        || !names.add(type.getName())
                        || reviewed.get(type.getName()) == null
                        || !raw.isModifiableClass(type)) {
                    gate.revoke("DEPENDENCY_REJECTED");
                    return gate;
                }
                collector.wanted.put(type, reviewed.get(type.getName()));
            }
            // A retransform can change earlier dependencies even when this capture fails.
            // Retire old gates before entering the JVM, while no operation holds a lease.
            revokeAll("OWNED_CAPTURE_RETRANSFORM");
            raw.addTransformer(collector, true);
            registered = true;
            raw.retransformClasses(actual);
            if (collector.fatal instanceof VirtualMachineError failure) throw failure;
            if (collector.fatal instanceof ThreadDeath failure) throw failure;
            if (!collector.failed
                    && collector.captured.size() == collector.wanted.size()
                    && collector.wanted.entrySet().stream()
                            .allMatch(e -> e.getValue().equals(collector.captured.get(e.getKey())))) {
                Map<String, WeakReference<Class<?>>> covered = new java.util.HashMap<>();
                for (Class<?> type : actual) covered.put(type.getName(), new WeakReference<>(type));
                gate.covered = Map.copyOf(covered);
                gate.reason = "OWNED_FINAL_DEFINITION_MATCH";
                gate.admitted = true;
            } else gate.revoke("DEFINITION_MISMATCH_OR_INCOMPLETE");
        } catch (Exception failure) {
            gate.revoke("CAPTURE_FAILED");
        } finally {
            try {
                if (registered && !raw.removeTransformer(collector)) {
                    captureBroken = true;
                    gate.revoke("CAPTURE_REMOVAL_FAILED");
                    revokeAll("CAPTURE_REMOVAL_FAILED");
                }
            } catch (RuntimeException failure) {
                captureBroken = true;
                gate.revoke("CAPTURE_REMOVAL_FAILED");
                revokeAll("CAPTURE_REMOVAL_FAILED");
            } catch (Error failure) {
                captureBroken = true;
                gate.revoke("CAPTURE_REMOVAL_FAILED");
                revokeAll("CAPTURE_REMOVAL_FAILED");
                throw failure;
            } finally {
                collector.clear();
                gates.removeIf(reference -> reference.get() == null);
                if (gate.admitted) gates.add(new WeakReference<>(gate));
                lock.writeLock().unlock();
            }
        }
        return gate;
    }

    private static final class Capture implements ClassFileTransformer {
        private Thread thread = Thread.currentThread();
        private Function<byte[], String> fingerprinter;
        private final IdentityHashMap<Class<?>, String> wanted = new IdentityHashMap<>();
        private final IdentityHashMap<Class<?>, String> captured = new IdentityHashMap<>();
        private boolean failed;
        private Error fatal;

        Capture(Function<byte[], String> fingerprinter) {
            this.fingerprinter = fingerprinter;
        }

        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
            if (thread != Thread.currentThread() || !wanted.containsKey(type)) return null;
            try {
                if (name == null
                        || !name.replace('/', '.').equals(type.getName())
                        || bytes == null
                        || captured.containsKey(type)) {
                    failed = true;
                    return null;
                }
                captured.put(type, fingerprinter.apply(bytes.clone()));
            } catch (RuntimeException | LinkageError invalid) {
                failed = true;
            } catch (VirtualMachineError | ThreadDeath failure) {
                failed = true;
                fatal = failure;
                throw failure;
            }
            return null;
        }

        void clear() {
            thread = null;
            fingerprinter = null;
            fatal = null;
            wanted.clear();
            captured.clear();
        }
    }

    private void rejectCallbackOrUpgrade() {
        if (inTransformerCallback()) {
            throw new IllegalStateException("definition mutations cannot wait inside a transformer callback");
        }
        if (lock.getReadHoldCount() != 0)
            throw new IllegalStateException("definition mutation during an owned operation");
    }

    static boolean inTransformerCallback() {
        return STACK.walk(frames -> frames.anyMatch(frame -> {
            Class<?> type = frame.getDeclaringClass();
            // Module-aware implementations can be inherited from a base which does
            // not implement the interface. In that case the JDK dispatch frame,
            // rather than the method's declaring class, identifies the callback.
            return ClassFileTransformer.class.isAssignableFrom(type)
                    || (type.getModule() == ClassFileTransformer.class.getModule()
                            && frame.getMethodName().equals("transform"));
        }));
    }

    private void revokeAll(String reason) {
        gates.removeIf(reference -> reference.get() == null);
        for (WeakReference<Gate> reference : gates) {
            Gate gate = reference.get();
            if (gate != null) gate.revoke(reason);
        }
        gates.clear();
    }

    @Override
    public void close() {
        rejectCallbackOrUpgrade();
        lock.writeLock().lock();
        try {
            if (!closed) {
                closed = true;
                revokeAll("CLOSED");
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
