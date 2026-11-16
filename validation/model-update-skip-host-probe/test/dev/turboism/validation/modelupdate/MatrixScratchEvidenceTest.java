package dev.turboism.validation.modelupdate;

import java.util.Map;

/**
 * Offline checks for the matrix-scratch final-acceptance telemetry.
 *
 * <p>No Editor, GL context or model is used. The test drives the assertion helper with synthetic
 * counter snapshots and proves it rejects installation-only evidence.</p>
 */
public final class MatrixScratchEvidenceTest {

    private static final String PREFIX = "leg.0.";
    private static final String CALLS = "calls";
    private static final String ACTIVE = "active";
    private static final String FAILURES = "failures";

    public static void main(String[] args) throws Exception {
        acceptsExecutedLeg();
        rejectsAbsentStatistics();
        rejectsNoExecution();
        rejectsFailures();
        rejectsInactiveBridge();
        System.out.println("MatrixScratchEvidenceTest PASS (execution required, failures and inactivity rejected)");
    }

    private static void acceptsExecutedLeg() throws Exception {
        final Map<String, Long> before = Map.of(CALLS, 10L, FAILURES, 0L, ACTIVE, 1L);
        final Map<String, Long> after = Map.of(CALLS, 42L, FAILURES, 0L, ACTIVE, 1L);
        final StringBuilder report = new StringBuilder();
        require(before, after, report);
        check(report.toString().contains("matrixScratch.callsDelta=32"), "calls delta must be reported");
        check(report.toString().contains("matrixScratch.executionVerified=true"), "verified flag must be set");
    }

    private static void rejectsAbsentStatistics() {
        expectRejected(Map.of(), Map.of(), "absent statistics");
    }

    private static void rejectsNoExecution() {
        final Map<String, Long> snapshot = Map.of(CALLS, 7L, FAILURES, 0L, ACTIVE, 1L);
        expectRejected(snapshot, snapshot, "no execution");
    }

    private static void rejectsFailures() {
        expectRejected(
                Map.of(CALLS, 1L, FAILURES, 0L, ACTIVE, 1L),
                Map.of(CALLS, 9L, FAILURES, 2L, ACTIVE, 1L),
                "failures present");
    }

    private static void rejectsInactiveBridge() {
        expectRejected(
                Map.of(CALLS, 1L, FAILURES, 0L, ACTIVE, 1L),
                Map.of(CALLS, 9L, FAILURES, 0L, ACTIVE, 0L),
                "inactive bridge");
    }

    private static void require(
            final Map<String, Long> before, final Map<String, Long> after, final StringBuilder report)
            throws Exception {
        final var method = CanvasWheelWorkload.class.getDeclaredMethod(
                "requireMatrixScratchExecution", Map.class, Map.class, String.class, StringBuilder.class);
        method.setAccessible(true);
        method.invoke(null, before, after, PREFIX, report);
    }

    private static void expectRejected(
            final Map<String, Long> before, final Map<String, Long> after, final String reason) {
        try {
            require(before, after, new StringBuilder());
        } catch (final Exception expected) {
            return;
        }
        throw new AssertionError("must reject: " + reason);
    }

    private static void check(final boolean value, final String reason) {
        if (!value) throw new AssertionError(reason);
    }
}
