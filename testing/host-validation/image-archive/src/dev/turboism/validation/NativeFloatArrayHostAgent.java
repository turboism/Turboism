package dev.turboism.validation;

import java.awt.Frame;
import java.awt.event.WindowEvent;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** Test-only real native parser validation. Never starts a Cubism main class. */
public final class NativeFloatArrayHostAgent {
    private static final String ENABLE = "turboism.optimization.floatArrayParseCache";
    private static final String CALLBACK = "turboism.float-array-parse-cache.callback";
    private static final String STATS = "turboism.float-array-parse-cache.stats";
    private static final Properties RESULT = new Properties();

    /** Measures one freshly launched official Editor process, with optional untimed shadow comparison. */
    public static void premain(String args, Instrumentation instrumentation) {
        long start = System.nanoTime(), cpuStart = processCpu();
        Allocations allocations = new Allocations();
        Shadow shadow = new Shadow();
        boolean compare = Boolean.getBoolean("turboism.validation.floatArray.shadow");
        if (compare) shadow.start();
        Thread worker = new Thread(() -> run(instrumentation, start, cpuStart, allocations, shadow, compare), "turboism-float-array-validation");
        worker.setDaemon(true);
        worker.start();
    }

    private static void run(Instrumentation instrumentation, long start, long cpuStart,
                            Allocations allocations, Shadow shadow, boolean compare) {
        Path home = Path.of(System.getProperty("turboism.validation.floatArray.home"));
        Path output = home.resolve("state/float-array/result.properties");
        try {
            RESULT.setProperty("schemaVersion", "1");
            RESULT.setProperty("runId", System.getProperty("turboism.validation.runId", ""));
            RESULT.setProperty("fixtureName", System.getProperty("turboism.validation.fixtureName", ""));
            boolean enabled = Boolean.getBoolean(ENABLE);
            RESULT.setProperty("optimizationEnabled", Boolean.toString(enabled));
            RESULT.setProperty("shadowComparison", Boolean.toString(compare));
            RESULT.setProperty("hostRuntime", System.getProperty("java.runtime.version"));
            Class<?> target = null;
            long deadline = System.nanoTime() + 600_000_000_000L;
            // Observe host objects only after the existing runtime-ready barrier;
            // measurements/sampling and shadow attachment already began at premain.
            while (!Files.isRegularFile(home.resolve("state/float-array/start.flag")) && System.nanoTime() < deadline) Thread.sleep(100);
            require(Files.isRegularFile(home.resolve("state/float-array/start.flag")), "runtime-ready trigger absent");
            while (target == null && System.nanoTime() < deadline) {
                for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                    if (type.getName().equals("com.live2d.serialize.impl.G")) target = type;
                }
                if (target == null) Thread.sleep(100);
            }
            require(target != null, "native float serializer was not loaded");
            NativeAtlasWorkflow.awaitTaskDocument(target.getClassLoader());
            RESULT.setProperty("agentToDocumentReadyNs", Long.toString(System.nanoTime() - start));
            long cpuEnd = processCpu();
            RESULT.setProperty("processCpuToDocumentReadyNs", Long.toString(cpuStart < 0 || cpuEnd < 0 ? -1 : cpuEnd - cpuStart));
            RESULT.setProperty("observedThreadAllocatedBytes", Long.toString(allocations.stopAndTotal()));
            RESULT.setProperty("allocationMetric", "sum-of-observed-thread-allocation-maxima;lower-bound-not-retained-or-peak-memory");
            RESULT.setProperty("heapUsedAtReadyBytes", Long.toString(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()));
            Object statistics = System.getProperties().get(STATS);
            if (enabled) {
                require(statistics instanceof Supplier<?>, "requested parser optimization not installed");
                Map<?, ?> values = (Map<?, ?>) ((Supplier<?>) statistics).get();
                for (var entry : values.entrySet()) RESULT.setProperty("cache." + entry.getKey(), entry.getValue().toString());
                require(((Number) values.get("hits")).longValue() > 0, "no native token reuse during model loading");
            } else {
                require(statistics == null && System.getProperties().get(CALLBACK) == null, "off baseline unexpectedly installed parser hook");
            }
            Class<?> serializer = target;
            NativeAtlasWorkflow.onEdt(() -> {
                Object manager = NativeAtlasWorkflow.manager(serializer.getClassLoader());
                RESULT.setProperty("modelImageCount", Integer.toString(((List<?>) manager.getClass().getMethod("getAllModelImages").invoke(manager)).size()));
                RESULT.setProperty("atlasCount", Integer.toString(((List<?>) manager.getClass().getMethod("getTextureAtlases").invoke(manager)).size()));
                return null;
            });
            if (compare) {
                shadow.detach();
                RESULT.setProperty("shadow.arraysBeforeAttach", Long.toString(shadow.arraysBeforeAttach));
                RESULT.setProperty("shadow.arraysCompared", Long.toString(shadow.arrays.get()));
                RESULT.setProperty("shadow.valuesCompared", Long.toString(shadow.values.get()));
                RESULT.setProperty("shadow.mismatches", Long.toString(shadow.mismatches.get()));
                require(shadow.failure.get() == null && shadow.arrays.get() > 0 && shadow.mismatches.get() == 0,
                    "native shadow comparison failed: " + shadow.failure.get());
            }
            store(home.resolve("state/float-array/load.properties"));
            // Timing ends above. Contract calls, bytecode restoration and output IO are excluded.
            while (!Files.isRegularFile(home.resolve("state/float-array/start.flag")) && System.nanoTime() < deadline) Thread.sleep(100);
            require(Files.isRegularFile(home.resolve("state/float-array/start.flag")), "runner trigger absent");
            var constructor = target.getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            Object receiver = constructor.newInstance(" ");
            var parse = target.getMethod("a", int.class, List.class);
            List<String> tokens = new ArrayList<>(List.of("1.234567890123456789", "-0.0", "NaN", "0x0.000002p-126", "1e100"));
            checkBits(tokens, (float[]) parse.invoke(receiver, tokens.size(), tokens));
            float[] first = (float[]) parse.invoke(receiver, tokens.size(), tokens);
            first[0] = 99;
            checkBits(tokens, (float[]) parse.invoke(receiver, tokens.size(), tokens));
            if (enabled) {
                System.setProperty(ENABLE, "false");
                checkBits(tokens, (float[]) parse.invoke(receiver, tokens.size(), tokens));
                require(((Number) ((Map<?, ?>) ((Supplier<?>) statistics).get()).get("entries")).longValue() == 0, "live disable retained token keys");
                System.setProperty(ENABLE, "true");
                checkBits(tokens, (float[]) parse.invoke(receiver, tokens.size(), tokens));
                Class<?> agent = null;
                for (Class<?> type : instrumentation.getAllLoadedClasses()) if (type.getName().equals("dev.turboism.bootstrap.TurboismAgent")) agent = type;
                require(agent != null, "production agent absent");
                var field = agent.getDeclaredField("FLOAT_ARRAY_PARSE_CACHE"); field.setAccessible(true);
                Object installation = ((AtomicReference<?>) field.get(null)).get();
                require(installation != null, "parser installer absent");
                var close = installation.getClass().getDeclaredMethod("close"); close.setAccessible(true); close.invoke(installation);
                var restored = installation.getClass().getDeclaredMethod("restored"); restored.setAccessible(true);
                require(Boolean.TRUE.equals(restored.invoke(installation)), "native serializer bytecode was not restored");
                require(System.getProperties().get(CALLBACK) == null && System.getProperties().get(STATS) == null, "parser callbacks not removed");
                checkBits(tokens, (float[]) parse.invoke(receiver, tokens.size(), tokens));
                RESULT.setProperty("liveDisableAndRestoration", "PASS");
            }
            RESULT.setProperty("nativeParserBitsAndArrayOwnership", "PASS");
            RESULT.setProperty("fixtureWritten", "false");
            RESULT.setProperty("status", "PASS"); store(output);
            String fixture = System.getProperty("turboism.validation.fixtureName", "");
            SwingUtilities.invokeLater(() -> {
                for (Frame frame : Frame.getFrames()) if (frame.isVisible() && frame.getTitle().contains(fixture)) {
                    frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)); break;
                }
            });
        } catch (Throwable failure) {
            Throwable root = failure; while (root.getCause() != null) root = root.getCause();
            RESULT.setProperty("status", "FAIL"); RESULT.setProperty("failure", root.toString());
            root.printStackTrace(System.err);
            try { store(output); } catch (Exception ignored) { }
        } finally {
            allocations.stopAndTotal();
            try { shadow.detach(); } catch (Exception ignored) { }
        }
    }

    static long processCpu() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean ? bean.getProcessCpuTime() : -1;
    }
    private static void checkBits(List<String> tokens, float[] result) {
        require(result.length == tokens.size(), "native result length differs");
        for (int i = 0; i < result.length; i++) require(Float.floatToRawIntBits(result[i]) == Float.floatToRawIntBits(Float.parseFloat(tokens.get(i))), "native float bits differ");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void store(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (var out = Files.newOutputStream(temporary)) { RESULT.store(out, "Native float-array validation"); }
        Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    static final class Allocations {
        final ConcurrentHashMap<Long, Long> observed = new ConcurrentHashMap<>();
        final AtomicBoolean running = new AtomicBoolean();
        final com.sun.management.ThreadMXBean bean;
        Thread thread;
        boolean stopped;
        long total = -1;
        Allocations() {
            bean = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean candidate
                && candidate.isThreadAllocatedMemorySupported() ? candidate : null;
            if (bean == null) return;
            bean.setThreadAllocatedMemoryEnabled(true); running.set(true);
            thread = new Thread(() -> {
                while (running.get()) {
                    sample();
                    try { Thread.sleep(100); } catch (InterruptedException stopped) { break; }
                }
            }, "turboism-native-load-allocation-sampler");
            thread.setDaemon(true); thread.start();
        }
        void sample() {
            long[] ids = bean.getAllThreadIds(), bytes = bean.getThreadAllocatedBytes(ids);
            for (int i = 0; i < ids.length; i++) if (bytes[i] >= 0) observed.merge(ids[i], bytes[i], Math::max);
        }
        synchronized long stopAndTotal() {
            if (bean == null || stopped) return total;
            stopped = true; running.set(false);
            if (thread != null) {
                thread.interrupt();
                try {thread.join(1000);} catch (InterruptedException interrupted) {Thread.currentThread().interrupt();}
                if (thread.isAlive()) return -1;
            }
            sample(); total = observed.values().stream().mapToLong(Long::longValue).sum();
            return total;
        }
    }

    private static final class Shadow {
        final AtomicLong arrays = new AtomicLong(), values = new AtomicLong(), mismatches = new AtomicLong();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean stopped = new AtomicBoolean();
        volatile long arraysBeforeAttach = -1;
        volatile Object original;
        volatile BiFunction<Object, Object, Object> wrapper;
        Thread watcher;
        void start() {
            watcher = new Thread(() -> {
                try {
                    long deadline = System.nanoTime() + 120_000_000_000L;
                    while (!stopped.get() && System.nanoTime() < deadline) {
                        Properties properties = System.getProperties();
                        synchronized (properties) {
                            Object found = properties.get(CALLBACK), stats = properties.get(STATS);
                            if (found instanceof BiFunction<?, ?, ?> && stats instanceof Supplier<?>) {
                                original = found;
                                arraysBeforeAttach = ((Number) ((Map<?, ?>) ((Supplier<?>) stats).get()).get("arrays")).longValue();
                                @SuppressWarnings("unchecked") BiFunction<Object, Object, Object> delegate = (BiFunction<Object, Object, Object>) found;
                                wrapper = (n, input) -> {
                                    try {
                                    Object parsed = delegate.apply(n, input);
                                    if (parsed instanceof float[] output && input instanceof List<?> list) {
                                        arrays.incrementAndGet(); values.addAndGet(output.length);
                                        for (int i = 0; i < output.length; i++) {
                                            if (Float.floatToRawIntBits(output[i]) != Float.floatToRawIntBits(Float.parseFloat((String) list.get(i)))) mismatches.incrementAndGet();
                                        }
                                    }
                                    return parsed;
                                    } catch (Throwable rejected) {
                                        failure.compareAndSet(null, rejected);
                                        return null;
                                    }
                                };
                                properties.put(CALLBACK, wrapper);
                                return;
                            }
                        }
                        Thread.sleep(1);
                    }
                    if (!stopped.get()) throw new IllegalStateException("shadow callback attachment timed out");
                } catch (Throwable rejected) { failure.set(rejected); }
            }, "turboism-float-shadow-attachment");
            watcher.setDaemon(true); watcher.start();
        }
        synchronized void detach() throws Exception {
            stopped.set(true);
            if (watcher != null) {watcher.interrupt(); watcher.join(1000);}
            if (wrapper != null) {
                require(System.getProperties().replace(CALLBACK, wrapper, original), "shadow callback ownership changed");
                wrapper = null;
            }
        }
    }
}
