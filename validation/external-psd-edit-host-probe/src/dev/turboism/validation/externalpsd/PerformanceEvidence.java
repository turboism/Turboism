package dev.turboism.validation.externalpsd;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * Opt-in performance sampling admission, cycle timing aggregation, and evidence recording.
 *
 * <p>Sampling is admitted only for the declared pipeline shape so a timing number can never be
 * published for a run that did not measure the intended scenario.</p>
 */
final class PerformanceEvidence {
    static final String PERFORMANCE_PROPERTY =
        "turboism.validation.externalpsd.performance";
    static final String PERFORMANCE_WARM_COLD_PROPERTY =
        "turboism.validation.externalpsd.performanceWarmCold";
    static final long PERFORMANCE_FINISH_TIMEOUT_MILLIS = 1_000L;

    /**
     * Performance observation is deliberately narrower than the content-profile switch.  Only
     * an explicitly requested F1 ten-cycle pipeline may start the sampler; all other requests
     * are rejected before readiness/export/mutation rather than silently producing partial data.
     */
    static boolean parsePerformanceOptIn(final String requested) {
        final String value = requested == null || requested.isBlank()
            ? "0" : requested.trim();
        return switch (value) {
            case "0" -> false;
            case "1" -> true;
            default -> throw new IllegalArgumentException(
                "performance must be namespaced value 1 (or 0); actual=" + requested);
        };
    }

    static PerformanceAdmission performanceAdmission(final String phase,
        final PsdValidationContent.Profile profile, final int cycles, final String requested) {
        Objects.requireNonNull(profile, "profile");
        final boolean requestedOptIn = parsePerformanceOptIn(requested);
        if (!requestedOptIn) {
            return PerformanceAdmission.disabled("performance opt-in was not requested");
        }
        if (!"pipeline".equals(phase)) {
            return PerformanceAdmission.rejected(
                "performance observation is admitted only for the pipeline phase");
        }
        if (profile != PsdValidationContent.Profile.F1_2048_20) {
            return PerformanceAdmission.rejected(
                "performance observation requires the explicit f1 content profile");
        }
        if (cycles != 10) {
            return PerformanceAdmission.rejected(
                "performance observation requires exactly 10 save cycles; actual=" + cycles);
        }
        return PerformanceAdmission.admitted(
            "f1 pipeline with exactly 10 save cycles is eligible for observation");
    }

    static void recordPerformanceAdmission(final Properties result,
        final PerformanceAdmission admission) {
        result.setProperty("performance.admission", admission.admitted()
            ? "ADMITTED" : admission.requested() ? "REJECTED" : "DISABLED");
        result.setProperty("performance.admissionDiagnostic", admission.diagnostic());
        result.setProperty("performance.sampling", admission.admitted() ? "OPT_IN" : "OFF");
        result.setProperty("performance.sc006", "NOT_CLAIMED");
    }

    static String performanceWarmColdDeclaration() {
        final String value = System.getProperty(PERFORMANCE_WARM_COLD_PROPERTY, "").trim()
            .toLowerCase(Locale.ROOT);
        return switch (value) {
            case "warm", "cold" -> value;
            case "" -> "UNDECLARED";
            default -> "UNAVAILABLE";
        };
    }

    static void recordPerformanceEnvironment(final Properties result) {
        final Runtime runtime = Runtime.getRuntime();
        result.setProperty("performance.jvm.javaVersion",
            System.getProperty("java.runtime.version", "UNAVAILABLE"));
        result.setProperty("performance.jvm.vmName",
            System.getProperty("java.vm.name", "UNAVAILABLE"));
        result.setProperty("performance.os.name",
            System.getProperty("os.name", "UNAVAILABLE"));
        result.setProperty("performance.os.version",
            System.getProperty("os.version", "UNAVAILABLE"));
        result.setProperty("performance.os.arch",
            System.getProperty("os.arch", "UNAVAILABLE"));
        result.setProperty("performance.processors",
            Integer.toString(runtime.availableProcessors()));
        result.setProperty("performance.jvm.maxHeapBytes", Long.toString(runtime.maxMemory()));
        try {
            result.setProperty("performance.jvm.collectors", ManagementFactory
                .getGarbageCollectorMXBeans().stream().map(bean -> bean.getName())
                .collect(java.util.stream.Collectors.joining(",")));
        } catch (RuntimeException failure) {
            result.setProperty("performance.jvm.collectors", "UNAVAILABLE");
        }
        for (final String option : List.of("UseG1GC", "G1HeapRegionSize",
            "UseCompressedOops", "UseCompressedClassPointers", "ObjectAlignmentInBytes")) {
            final String key = "performance.jvm.option." + option;
            try {
                final com.sun.management.HotSpotDiagnosticMXBean bean = ManagementFactory
                    .getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
                if (bean == null) throw new IllegalStateException("HotSpot diagnostic bean absent");
                final com.sun.management.VMOption observed = bean.getVMOption(option);
                result.setProperty(key, observed.getValue());
                result.setProperty(key + ".origin", observed.getOrigin().name());
            } catch (RuntimeException | LinkageError failure) {
                result.setProperty(key, "UNAVAILABLE");
            }
        }
        result.setProperty("performance.warmCold", performanceWarmColdDeclaration());
        result.setProperty("performance.rssBytes", "UNAVAILABLE");
        result.setProperty("performance.nativeStableRefresh", "UNAVAILABLE");
        result.setProperty("performance.timing.clock", "System.nanoTime");
        result.setProperty("performance.timing.nativeRefresh", "UNAVAILABLE");
        result.setProperty("performance.timing.writeToObservationSemantics",
            "write-complete to public completion/fresh-current observation is an upper bound; "
                + "revision-callback to the same observation is a lower bound; neither is a "
                + "native-final-refresh timestamp");
    }

    static void finishPerformanceEvidence(final Properties result,
        final ExternalPsdPerformanceSampler sampler, final PerformanceCycleTimings timings,
        final int expectedCycles, final Throwable terminalFailure) {
        ExternalPsdPerformanceSampler.Snapshot snapshot = null;
        Throwable finishFailure = null;
        try {
            snapshot = sampler.finish(PERFORMANCE_FINISH_TIMEOUT_MILLIS);
        } catch (Throwable failure) {
            finishFailure = failure;
            try {
                sampler.close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        result.setProperty("performance.lifecycle.finish", "CALLED");
        result.setProperty("performance.lifecycle.stop", "CLOSED");
        if (terminalFailure != null) {
            result.setProperty("performance.lifecycle.terminalFailure",
                terminalFailure.toString());
        }
        if (finishFailure != null) {
            result.setProperty("performance.lifecycle.finishFailure", finishFailure.toString());
        }
        if (snapshot == null) {
            recordUnavailablePerformanceSnapshot(result);
        } else {
            result.setProperty("performance.heartbeat.periodMillis",
                Long.toString(ExternalPsdPerformanceSampler.PERIOD_MILLIS));
            result.setProperty("performance.heartbeat.samples",
                Long.toString(snapshot.heartbeatSamples()));
            result.setProperty("performance.memory.samples",
                Long.toString(snapshot.memorySamples()));
            result.setProperty("performance.heartbeat.pending",
                Boolean.toString(snapshot.heartbeatPending()));
            result.setProperty("performance.heartbeat.pendingAgeNanos",
                Long.toString(snapshot.pendingHeartbeatAgeNanos()));
            result.setProperty("performance.heartbeat.maximumQueueDelayNanos",
                Long.toString(snapshot.maximumQueueDelayNanos()));
            result.setProperty("performance.sampling.maximumGapNanos",
                Long.toString(snapshot.maximumTickGapNanos()));
            result.setProperty("performance.heap.peakBytes",
                Long.toString(snapshot.peakHeapBytes()));
            result.setProperty("performance.nonHeap.peakBytes",
                Long.toString(snapshot.peakNonHeapBytes()));
            result.setProperty("performance.elapsedNanos", Long.toString(snapshot.elapsedNanos()));
            result.setProperty("performance.sampler.failures",
                Long.toString(snapshot.failures()));
            result.setProperty("performance.sampler.diagnostic", snapshot.diagnostic());
        }
        if (timings == null) {
            result.setProperty("performance.timing.sampleCount", "0");
            result.setProperty("performance.timing.expectedSampleCount",
                Integer.toString(expectedCycles));
            result.setProperty("performance.timing.coverage", "INCOMPLETE");
        } else {
            timings.recordAggregate(result, expectedCycles);
        }
        final int timingSamples = timings == null ? 0 : timings.sampleCount();
        final boolean complete = snapshot != null
            && performanceCoverageComplete(snapshot, timingSamples, expectedCycles)
            && finishFailure == null;
        result.setProperty("performance.coverage.complete", Boolean.toString(complete));
        result.setProperty("performance.coverage.status", complete ? "COMPLETE" : "INCOMPLETE");
        result.setProperty("performance.evidence.status", complete ? "OBSERVED" : "UNAVAILABLE");
        result.setProperty("performance.gate", "EVIDENCE_ONLY");
        result.setProperty("performance.sc006", "NOT_CLAIMED");
        if (!complete && !"BLOCKED".equals(result.getProperty("status")) && !Thread.currentThread().isInterrupted()) {
            result.setProperty("status", "FAIL");
            result.setProperty("expected", "complete F1 ten-cycle performance observation");
            result.setProperty("actual", "performance observation coverage is incomplete; no performance PASS");
        }
    }

    static void recordUnavailablePerformanceSnapshot(final Properties result) {
        for (final String key : List.of(
            "performance.heartbeat.samples", "performance.memory.samples",
            "performance.heartbeat.pendingAgeNanos", "performance.heartbeat.maximumQueueDelayNanos",
            "performance.sampling.maximumGapNanos", "performance.heap.peakBytes",
            "performance.nonHeap.peakBytes", "performance.elapsedNanos",
            "performance.sampler.failures")) {
            result.setProperty(key, "UNAVAILABLE");
        }
        result.setProperty("performance.heartbeat.periodMillis",
            Long.toString(ExternalPsdPerformanceSampler.PERIOD_MILLIS));
        result.setProperty("performance.heartbeat.pending", "UNAVAILABLE");
        result.setProperty("performance.sampler.diagnostic", "UNAVAILABLE");
    }

    static boolean performanceCoverageComplete(final ExternalPsdPerformanceSampler.Snapshot snapshot,
        final int timingSamples, final int expectedCycles) {
        return snapshot != null && snapshot.complete() && expectedCycles == 10
            && timingSamples == expectedCycles;
    }

    /** Opt-in per-cycle memory attribution: raw heap pools plus a post-GC retention snapshot. */
    static boolean memoryAttributionRequested() {
        return Boolean.parseBoolean(System.getProperty(
            "turboism.validation.externalpsd.memoryAttribution", "false").strip());
    }

    static void recordMemoryPools(final Properties result, final String prefix) {
        for (final java.lang.management.MemoryPoolMXBean pool
                : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != java.lang.management.MemoryType.HEAP) continue;
            final java.lang.management.MemoryUsage usage = pool.getUsage();
            if (usage == null) continue;
            final String key = prefix + pool.getName().replaceAll("[^A-Za-z0-9]", "");
            result.setProperty(key + ".usedBytes", Long.toString(usage.getUsed()));
            result.setProperty(key + ".committedBytes", Long.toString(usage.getCommitted()));
        }
        final java.lang.management.MemoryUsage heap =
            ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        result.setProperty(prefix + "heap.usedBytes", Long.toString(heap.getUsed()));
        result.setProperty(prefix + "heap.committedBytes", Long.toString(heap.getCommitted()));
    }

    record PerformanceAdmission(boolean requested, boolean admitted,
        String diagnostic) {
        PerformanceAdmission {
            diagnostic = diagnostic == null ? "" : diagnostic;
            if (admitted && !requested) {
                throw new IllegalArgumentException("admitted performance must be requested");
            }
        }

        static PerformanceAdmission disabled(final String diagnostic) {
            return new PerformanceAdmission(false, false, diagnostic);
        }

        static PerformanceAdmission rejected(final String diagnostic) {
            return new PerformanceAdmission(true, false, diagnostic);
        }

        static PerformanceAdmission admitted(final String diagnostic) {
            return new PerformanceAdmission(true, true, diagnostic);
        }
    }

    record PerformanceTimingSample(boolean available,
        long writeCompleteToPublicCompletionUpperBoundNanos,
        long writeCompleteToFreshObservationUpperBoundNanos,
        long revisionCallbackToFreshObservationLowerBoundNanos,
        String diagnostic) {
        PerformanceTimingSample {
            diagnostic = diagnostic == null ? "" : diagnostic;
            if (available && (writeCompleteToPublicCompletionUpperBoundNanos < 0L
                || writeCompleteToFreshObservationUpperBoundNanos < 0L
                || revisionCallbackToFreshObservationLowerBoundNanos < 0L)) {
                throw new IllegalArgumentException(
                    "available performance timing values must not be negative");
            }
        }

        static PerformanceTimingSample unavailable(final String diagnostic) {
            return new PerformanceTimingSample(false, -1L, -1L, -1L, diagnostic);
        }
    }

    static PerformanceTimingSample performanceTimingSampleForTest(
        final long writeCompleteNanos, final long revisionCallbackNanos,
        final long publicCompletionNanos, final long freshObservationNanos) {
        return performanceTimingSample(writeCompleteNanos, revisionCallbackNanos,
            publicCompletionNanos, freshObservationNanos);
    }

    static PerformanceTimingSample performanceTimingSample(
        final long writeCompleteNanos, final long revisionCallbackNanos,
        final long publicCompletionNanos, final long freshObservationNanos) {
        if (writeCompleteNanos < 0L || revisionCallbackNanos < 0L
            || publicCompletionNanos < 0L || freshObservationNanos < 0L) {
            return PerformanceTimingSample.unavailable("monotonic timing value is negative");
        }
        if (revisionCallbackNanos < writeCompleteNanos) {
            return PerformanceTimingSample.unavailable(
                "revision callback preceded write completion");
        }
        if (publicCompletionNanos < revisionCallbackNanos) {
            return PerformanceTimingSample.unavailable(
                "public completion preceded revision callback");
        }
        if (freshObservationNanos < publicCompletionNanos) {
            return PerformanceTimingSample.unavailable(
                "fresh current observation preceded public completion");
        }
        return new PerformanceTimingSample(
            true,
            publicCompletionNanos - writeCompleteNanos,
            freshObservationNanos - writeCompleteNanos,
            freshObservationNanos - revisionCallbackNanos,
            "");
    }

    static final class PerformanceCycleTimings {
        final List<Long> writeToPublic = new ArrayList<>();
        final List<Long> writeToFresh = new ArrayList<>();
        final List<Long> callbackToFresh = new ArrayList<>();

        void record(final Properties result, final String prefix,
            final long writeCompleteNanos, final long revisionCallbackNanos,
            final long publicCompletionNanos, final long freshObservationNanos) {
            final PerformanceTimingSample sample = performanceTimingSample(writeCompleteNanos,
                revisionCallbackNanos, publicCompletionNanos, freshObservationNanos);
            result.setProperty(prefix + "timing.status",
                sample.available() ? "AVAILABLE" : "UNAVAILABLE");
            result.setProperty(prefix + "timing.nativeRefresh", "UNAVAILABLE");
            result.setProperty(prefix + "timing.semantics",
                "write-complete to public/fresh-current observation bounds; "
                    + "revision callback to same observation lower bound; not native refresh time");
            if (!sample.available()) {
                result.setProperty(prefix + "timing.diagnostic", sample.diagnostic());
                return;
            }
            result.setProperty(prefix + "timing.writeCompleteToPublicCompletionUpperBoundNanos",
                Long.toString(sample.writeCompleteToPublicCompletionUpperBoundNanos()));
            result.setProperty(prefix + "timing.writeCompleteToFreshObservationUpperBoundNanos",
                Long.toString(sample.writeCompleteToFreshObservationUpperBoundNanos()));
            result.setProperty(prefix + "timing.revisionCallbackToFreshObservationLowerBoundNanos",
                Long.toString(sample.revisionCallbackToFreshObservationLowerBoundNanos()));
            writeToPublic.add(sample.writeCompleteToPublicCompletionUpperBoundNanos());
            writeToFresh.add(sample.writeCompleteToFreshObservationUpperBoundNanos());
            callbackToFresh.add(sample.revisionCallbackToFreshObservationLowerBoundNanos());
        }

        int sampleCount() {
            return writeToFresh.size();
        }

        void recordAggregate(final Properties result, final int expectedCycles) {
            final int count = sampleCount();
            result.setProperty("performance.timing.sampleCount", Integer.toString(count));
            result.setProperty("performance.timing.expectedSampleCount",
                Integer.toString(expectedCycles));
            result.setProperty("performance.timing.coverage",
                count == expectedCycles ? "COMPLETE" : "INCOMPLETE");
            result.setProperty("performance.timing.p95.method", "nearest-rank");
            result.setProperty("performance.timing.p95.rank",
                Integer.toString((int) Math.ceil(expectedCycles * 0.95d)));
            if (count != expectedCycles || expectedCycles != 10) {
                result.setProperty(
                    "performance.timing.p95.writeCompleteToPublicCompletionUpperBoundNanos",
                    "UNAVAILABLE");
                result.setProperty(
                    "performance.timing.p95.writeCompleteToFreshObservationUpperBoundNanos",
                    "UNAVAILABLE");
                result.setProperty(
                    "performance.timing.p95.revisionCallbackToFreshObservationLowerBoundNanos",
                    "UNAVAILABLE");
                return;
            }
            result.setProperty(
                "performance.timing.p95.writeCompleteToPublicCompletionUpperBoundNanos",
                Long.toString(nearestRankP95(writeToPublic)));
            result.setProperty(
                "performance.timing.p95.writeCompleteToFreshObservationUpperBoundNanos",
                Long.toString(nearestRankP95(writeToFresh)));
            result.setProperty(
                "performance.timing.p95.revisionCallbackToFreshObservationLowerBoundNanos",
                Long.toString(nearestRankP95(callbackToFresh)));
        }
    }

    static long nearestRankP95(final List<Long> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("p95 requires at least one sample");
        }
        final List<Long> ordered = samples.stream().map(Objects::requireNonNull).sorted().toList();
        final int rank = Math.max(1, (int) Math.ceil(ordered.size() * 0.95d));
        return ordered.get(rank - 1);
    }

    static boolean isFinalRgbMutationCycle(final int cycle, final int cycles) {
        if (cycles < 1 || cycle < 1 || cycle > cycles) {
            throw new IllegalArgumentException("cycle must be within a positive cycle count");
        }
        return cycle == cycles;
    }
}
