package dev.turboism.validation.modelupdate;

import java.awt.EventQueue;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Offline benchmark-factor checks; no Editor, geometry changes or real GL. */
public final class MatrixScratchTrialTest {
    private static final String MATRIX = "turboism.optimization.matrixScratch";
    private static final String GATE = "turboism.matrix-scratch.admission";

    public static void main(String[] args) throws Exception {
        String previousUniform = System.getProperty(NarrowUniformTrial.ENABLE);
        String previousMatrix = System.getProperty(MATRIX);
        Object previousGate = System.getProperties().get(GATE);
        Object previousStats = System.getProperties().get(NarrowUniformTrial.STATS);
        Map<String, Long> counts = new HashMap<>(Map.of("active", 1L, "completedFrames", 0L,
            "queries", 0L, "hits", 0L, "nativeResults", 0L, "failures", 0L, "glErrors", 0L));
        try {
            System.setProperty(NarrowUniformTrial.ENABLE, "false");
            System.setProperty(MATRIX, "previous-matrix-value");
            AtomicBoolean gate = new AtomicBoolean(true);
            System.getProperties().put(GATE, gate);
            System.getProperties().put(NarrowUniformTrial.STATS, (Supplier<Map<String, Long>>) () -> Map.copyOf(counts));
            EventQueue.invokeAndWait(() -> {
                try (NarrowUniformTrial trial = new NarrowUniformTrial(true)) {
                    for (boolean enabled : new boolean[] {false, true, true, false}) {
                        trial.setEnabled(enabled);
                        check(Boolean.getBoolean(NarrowUniformTrial.ENABLE), "uniform cache must stay ON in both matrix controls");
                        check(Boolean.getBoolean(MATRIX) == enabled, "only matrix request may change");
                        Map<String, Long> before = trial.snapshot();
                        counts.merge("completedFrames", 2L, Long::sum);
                        counts.merge("queries", 4L, Long::sum);
                        counts.merge("hits", 2L, Long::sum);
                        trial.requireLeg(before, trial.snapshot(), enabled, 2);
                    }
                    Map<String, Long> before = trial.snapshot();
                    counts.merge("completedFrames", 2L, Long::sum);
                    counts.merge("queries", 4L, Long::sum);
                    counts.merge("hits", 2L, Long::sum);
                    gate.set(false);
                    expectRejected(() -> trial.requireLeg(before, trial.snapshot(), false, 2), "retired matrix gate");
                    gate.set(true);
                    System.getProperties().put(GATE, new AtomicBoolean(true));
                    expectRejected(() -> trial.requireLeg(before, trial.snapshot(), false, 2), "foreign matrix gate");
                    System.getProperties().put(GATE, gate);
                    System.setProperty(NarrowUniformTrial.ENABLE, "false");
                    expectRejected(() -> trial.requireLeg(before, trial.snapshot(), false, 2), "uniform control changed");
                }
            });
            check("false".equals(System.getProperty(NarrowUniformTrial.ENABLE)), "uniform choice restored exactly");
            check("previous-matrix-value".equals(System.getProperty(MATRIX)), "matrix choice restored exactly");
            System.getProperties().remove(GATE);
            expectRejected(() -> new NarrowUniformTrial(true), "no installed matrix gate");
            System.out.println("MatrixScratchTrialTest PASS (independent factor, admission ownership, retirement, control restoration)");
        } finally {
            restore(NarrowUniformTrial.ENABLE, previousUniform);
            restore(MATRIX, previousMatrix);
            restore(GATE, previousGate);
            restore(NarrowUniformTrial.STATS, previousStats);
        }
    }
    private static void restore(String key, Object value) {
        if (value == null) System.getProperties().remove(key); else System.getProperties().put(key, value);
    }
    private static void expectRejected(Runnable operation, String reason) {
        try { operation.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("must reject: " + reason);
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
