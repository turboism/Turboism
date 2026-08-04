package dev.turboism.preview;

import dev.turboism.adapter.host.HostSession;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PreviewRuntimeCloseTest {

    @Test
    void processExitSkipsHostBoundCleanupButStillPersistsAndClosesTheLog() {
        final RecordingLifecycle lifecycle = new RecordingLifecycle();
        final PreviewRuntime runtime = new PreviewRuntime(lifecycle);

        runtime.closeForProcessExit();

        assertEquals(
            List.of("stop-log", "host-state", "final-report", "log"),
            lifecycle.order
        );
        assertEquals(false, lifecycle.shutdownAttempted);
        assertEquals(List.of(), codes(runtime));

        runtime.close();
        assertEquals(
            List.of("stop-log", "host-state", "final-report", "log"),
            lifecycle.order,
            "process-exit close remains idempotent"
        );
    }

    @Test
    void pluginRuntimeFailureStillClosesHostIngressSchedulerAndLogAndAttemptsReport() {
        final RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.fail("plugin-runtime");
        final PreviewRuntime runtime = new PreviewRuntime(lifecycle);

        runtime.close();
        assertEquals(true, lifecycle.shutdownAttempted);

        assertEquals(
            List.of(
                "stop-log",
                "host-state",
                "plugin-runtime",
                "host-ingress",
                "scheduler",
                "final-report",
                "summary-log",
                "log"
            ),
            lifecycle.order
        );
        assertEquals(
            List.of("PLUGIN_RUNTIME_CLOSE_FAILED"),
            codes(runtime)
        );
    }

    @Test
    void everyShutdownStageFailureUsesOnlyItsStableCodeAndDoesNotSkipLaterStages() {
        final RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.fail(
            "stop-log",
            "host-state",
            "plugin-runtime",
            "host-ingress",
            "scheduler",
            "final-report",
            "summary-log",
            "log"
        );
        final PreviewRuntime runtime = new PreviewRuntime(lifecycle);

        runtime.close();

        assertEquals(
            List.of(
                "stop-log",
                "host-state",
                "plugin-runtime",
                "host-ingress",
                "scheduler",
                "final-report",
                "summary-log",
                "log"
            ),
            lifecycle.order
        );
        assertEquals(
            List.of(
                "STOP_LOG_FAILED",
                "HOST_STATE_CAPTURE_FAILED",
                "PLUGIN_RUNTIME_CLOSE_FAILED",
                "HOST_INGRESS_CLOSE_FAILED",
                "SCHEDULER_SHUTDOWN_FAILED",
                "FINAL_REPORT_WRITE_FAILED",
                "SHUTDOWN_SUMMARY_LOG_FAILED",
                "LOG_CLOSE_FAILED"
            ),
            codes(runtime)
        );
        assertEquals(
            List.of("Runtime shutdown stage failed safely."),
            runtime.shutdownFailures().stream()
                .map(PreviewRuntime.ShutdownFailure::message)
                .distinct()
                .toList()
        );
    }

    @Test
    void multipleShutdownFailuresAreAggregatedWithoutSkippingLaterStages() {
        final RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.fail("plugin-runtime", "host-ingress", "scheduler");
        final PreviewRuntime runtime = new PreviewRuntime(lifecycle);

        runtime.close();

        assertEquals(
            List.of(
                "stop-log",
                "host-state",
                "plugin-runtime",
                "host-ingress",
                "scheduler",
                "final-report",
                "summary-log",
                "log"
            ),
            lifecycle.order
        );
        assertEquals(
            List.of(
                "PLUGIN_RUNTIME_CLOSE_FAILED",
                "HOST_INGRESS_CLOSE_FAILED",
                "SCHEDULER_SHUTDOWN_FAILED"
            ),
            codes(runtime)
        );
        assertFalse(runtime.shutdownFailures().stream().anyMatch(failure ->
            failure.message().contains("C:/Users/private")
                || failure.message().contains("private-exception-detail")
        ));
    }

    @Test
    void reportWriterFalseDoesNotPreventLogClose() {
        final RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.reportResult = false;
        final PreviewRuntime runtime = new PreviewRuntime(lifecycle);

        runtime.close();

        assertReportFailureStillClosesLog(runtime, lifecycle);
    }

    @Test
    void reportWriterThrowDoesNotPreventLogClose() {
        final RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.fail("final-report");
        final PreviewRuntime runtime = new PreviewRuntime(lifecycle);

        runtime.close();

        assertReportFailureStillClosesLog(runtime, lifecycle);
    }

    private static void assertReportFailureStillClosesLog(
        final PreviewRuntime runtime,
        final RecordingLifecycle lifecycle
    ) {
        assertEquals(
            List.of(
                "stop-log",
                "host-state",
                "plugin-runtime",
                "host-ingress",
                "scheduler",
                "final-report",
                "summary-log",
                "log"
            ),
            lifecycle.order
        );
        assertEquals(List.of("FINAL_REPORT_WRITE_FAILED"), codes(runtime));
        assertEquals(
            lifecycle.order.size() - 1,
            lifecycle.order.indexOf("log"),
            "log close must remain the final shutdown stage"
        );
        assertEquals(
            lifecycle.order.indexOf("log") - 2,
            lifecycle.order.indexOf("final-report"),
            "final report must be attempted before degraded summary and log close"
        );
    }

    private static List<String> codes(final PreviewRuntime runtime) {
        return runtime.shutdownFailures().stream()
            .map(PreviewRuntime.ShutdownFailure::code)
            .toList();
    }

    private static final class RecordingLifecycle implements PreviewRuntime.ShutdownLifecycle {
        private final List<String> order = new ArrayList<>();
        private final List<String> failingStages = new ArrayList<>();
        private boolean reportResult = true;
        private Boolean shutdownAttempted;

        private void fail(final String... stages) {
            failingStages.addAll(List.of(stages));
        }

        private void record(final String stage) {
            order.add(stage);
            if (failingStages.contains(stage)) {
                throw new AssertionError("C:/Users/private/private-exception-detail");
            }
        }

        @Override
        public void logStopping() {
            record("stop-log");
        }

        @Override
        public HostSession.State hostState() {
            record("host-state");
            return HostSession.State.ACTIVE;
        }

        @Override
        public void closePluginRuntime() {
            record("plugin-runtime");
        }

        @Override
        public void closeHostIngress() {
            record("host-ingress");
        }

        @Override
        public void shutdownScheduler() {
            record("scheduler");
        }

        @Override
        public boolean writeFinalReport(
            final HostSession.State observedHostState,
            final boolean shutdownAttempted
        ) {
            this.shutdownAttempted = shutdownAttempted;
            record("final-report");
            assertEquals(
                failingStages.contains("host-state")
                    ? HostSession.State.FAILED
                    : HostSession.State.ACTIVE,
                observedHostState
            );
            return reportResult;
        }

        @Override
        public void logDegradedShutdown() {
            record("summary-log");
        }

        @Override
        public void closeLog() {
            record("log");
        }
    }
}
