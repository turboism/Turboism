package dev.turboism.validation.externalpsd;

import static dev.turboism.validation.externalpsd.RawExportCoordination.modelImageIds;
import static dev.turboism.validation.externalpsd.RawExportCoordination.selectorProjection;
import static dev.turboism.validation.externalpsd.RawExportCoordination.valueOrUnavailable;
import static dev.turboism.validation.externalpsd.TargetResolution.remainingMillis;

import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
import dev.turboism.sdk.cubism.id.RawImageId;
import javax.swing.SwingUtilities;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/**
 * Bounded diagnostic observation and settle protocol for one native export target.
 *
 * <p>A diagnostic read can return stale or unavailable data. The recorder therefore settles each
 * observation against a deadline and a bounded attempt budget, and it records why an observation
 * was rejected instead of retrying without limit.</p>
 */
final class DiagnosticObservationRecorder {
    static final int BOUNDED_SETTLE_MAX_ATTEMPTS = 3;
    static final long BOUNDED_SETTLE_MAX_DURATION_MILLIS = 15_000L;
    static final long BOUNDED_SETTLE_INITIAL_BACKOFF_MILLIS = 100L;
    static final long BOUNDED_SETTLE_MAX_BACKOFF_MILLIS = 500L;

    static PsdValidationContent.Fingerprint targetFingerprint(final byte[] bytes,
        final String label) {
        return targetFingerprint(bytes, label,
            PsdValidationContent.Profile.SEVEN_LAYER_CONTROL);
    }

    static PsdValidationContent.Fingerprint targetFingerprint(final byte[] bytes,
        final String label, final PsdValidationContent.Profile profile) {
        try {
            return PsdValidationContent.targetLayerRgbFingerprint(bytes, profile);
        } catch (PsdValidationContent.ValidationException invalid) {
            throw new IllegalStateException(label + " is not a supported validation PSD: "
                + invalid.getMessage(), invalid);
        }
    }

    static void recordTargetFingerprint(final Properties result, final String prefix,
        final PsdValidationContent.Fingerprint fingerprint) {
        final PsdValidationContent.Bounds bounds = fingerprint.bounds();
        result.setProperty(prefix + ".sha256", fingerprint.sha256());
        result.setProperty(prefix + ".bounds",
            bounds.top() + "," + bounds.left() + "," + bounds.bottom() + "," + bounds.right());
        result.setProperty(prefix + ".width", Integer.toString(fingerprint.width()));
        result.setProperty(prefix + ".height", Integer.toString(fingerprint.height()));
        result.setProperty(prefix + ".channelIds", fingerprint.channelIds().toString());
    }

    static void recordDiagnosticCapture(final Properties result, final String prefix,
        final TargetResolution.Target target, final DiagnosticObservation observation, final String boundary,
        final String diagnostic) {
        final String normalizedPrefix = RawExportCoordination.normalizePrefix(prefix);
        result.setProperty(normalizedPrefix + ".boundary", valueOrUnavailable(boundary));
        result.setProperty(normalizedPrefix + ".requestedRawId", target.raw().value());
        PersistSaveChecks.recordExpectedTargetIdentity(result, normalizedPrefix, target.identity());
        if (diagnostic != null && !diagnostic.isBlank()) {
            result.setProperty(normalizedPrefix + ".boundaryDiagnostic", diagnostic);
        }
        recordDiagnosticObservation(result, normalizedPrefix, observation);
        if (normalizedPrefix.endsWith(".importCompletion")) {
            recordNativeReturnUnavailable(result, normalizedPrefix);
        }
    }

    static DiagnosticObservation diagnosticUnavailable(final String diagnostic,
        final PsdValidationContent.Fingerprint fingerprint, final TargetResolution.TargetIdentity observed,
        final long freshExportStartedAtEpochMillis, final long freshExportCompletedAtEpochMillis,
        final long metadataStartedAtEpochMillis, final long metadataCompletedAtEpochMillis) {
        return new DiagnosticObservation(
            DiagnosticStatus.UNAVAILABLE,
            diagnostic,
            RawExportCoordination.UNAVAILABLE_VALUE,
            RawExportCoordination.UNAVAILABLE_VALUE,
            RawExportCoordination.UNAVAILABLE_VALUE,
            RawExportCoordination.UNAVAILABLE_VALUE,
            false,
            false,
            fingerprint,
            observed,
            false,
            freshExportStartedAtEpochMillis,
            freshExportCompletedAtEpochMillis,
            metadataStartedAtEpochMillis,
            metadataCompletedAtEpochMillis
        );
    }

    /**
     * Records one observation without upgrading an unavailable public projection to content
     * evidence. The nested status values are intentionally lower-case for machine-readable field
     * presence; the top-level status and native-return status remain explicit enums.
     */
    static void recordDiagnosticObservation(final Properties result, final String prefix,
        final DiagnosticObservation observation) {
        Objects.requireNonNull(result, "result");
        final String normalizedPrefix = RawExportCoordination.normalizePrefix(prefix);
        final DiagnosticObservation value = Objects.requireNonNull(observation, "observation");
        result.setProperty(normalizedPrefix + ".status", value.status().name());
        result.setProperty(normalizedPrefix + ".diagnostic", value.diagnostic());
        result.setProperty(normalizedPrefix + ".relations.status",
            value.relationsAvailable() ? "AVAILABLE" : "UNAVAILABLE");
        result.setProperty(normalizedPrefix + ".relations.rawIds", value.rawIds());
        result.setProperty(normalizedPrefix + ".relations.modelImageIds", value.modelImageIds());
        result.setProperty(normalizedPrefix + ".selectorProjection", value.selectorProjection());
        result.setProperty(normalizedPrefix + ".livePsd.status",
            value.livePsdAvailable() ? "AVAILABLE" : "UNAVAILABLE");
        result.setProperty(normalizedPrefix + ".livePsd", value.livePsd());
        result.setProperty(normalizedPrefix + ".target.identityVerified",
            Boolean.toString(value.targetIdentityVerified()));
        PersistSaveChecks.recordTargetIdentity(result, normalizedPrefix + ".target.observed",
            value.observedTarget());
        result.setProperty(normalizedPrefix + ".observation.freshExportStartedAtEpochMs",
            Long.toString(value.freshExportStartedAtEpochMillis()));
        result.setProperty(normalizedPrefix + ".observation.freshExportCompletedAtEpochMs",
            Long.toString(value.freshExportCompletedAtEpochMillis()));
        result.setProperty(normalizedPrefix + ".observation.metadataStartedAtEpochMs",
            Long.toString(value.metadataStartedAtEpochMillis()));
        result.setProperty(normalizedPrefix + ".observation.metadataCompletedAtEpochMs",
            Long.toString(value.metadataCompletedAtEpochMillis()));
        if (value.status() != DiagnosticStatus.AVAILABLE || value.freshNativeRgb() == null) {
            result.setProperty(normalizedPrefix + ".freshNativeRgb.status", "unavailable");
        } else {
            result.setProperty(normalizedPrefix + ".freshNativeRgb.status", "available");
            result.setProperty(normalizedPrefix + ".freshNativeRgb.source",
                "fresh native export decoded by validation-only fixture helper");
            recordTargetFingerprint(result, normalizedPrefix + ".freshNativeRgb",
                value.freshNativeRgb());
        }
    }

    /** Records only the public completion result; PsdReplaceResult exposes no native return value. */
    static void recordImportCompletion(final Properties result, final String prefix,
        final PsdReplaceResult replaced) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(replaced, "replaced");
        final String completionPrefix = RawExportCoordination.joinPrefix(prefix, "importCompletion");
        result.setProperty(completionPrefix + ".observed", "true");
        result.setProperty(completionPrefix + ".publicCompletion.observed", "true");
        result.setProperty(completionPrefix + ".publicCompletion.status",
            replaced.status().name());
        result.setProperty(completionPrefix + ".status", replaced.status().name());
        result.setProperty(completionPrefix + ".diagnostic", replaced.diagnostic());
        result.setProperty(completionPrefix + ".beforeRawId", replaced.before().value());
        result.setProperty(completionPrefix + ".afterRawId",
            replaced.after().map(RawImageId::value).orElse(RawExportCoordination.UNAVAILABLE_VALUE));
        result.setProperty(completionPrefix + ".consumedRevision",
            Boolean.toString(replaced.consumedRevision().isPresent()));
        if (replaced.relations().isPresent()) {
            RawExportCoordination.recordRelationProjection(result, completionPrefix + ".relations",
                replaced.relations().orElseThrow());
        } else {
            RawExportCoordination.recordUnavailableRelationProjection(result, completionPrefix + ".relations");
        }
        result.setProperty(completionPrefix + ".livePsd.status", "UNAVAILABLE");
        result.setProperty(completionPrefix + ".livePsd", RawExportCoordination.UNAVAILABLE_VALUE);
        result.setProperty(completionPrefix + ".freshNativeRgb.status", "unavailable");
        recordNativeReturnUnavailable(result, completionPrefix);
    }

    static void recordNativeReturnUnavailable(final Properties result,
        final String prefix) {
        result.setProperty(prefix + ".nativeReturn.observation", "UNAVAILABLE");
        result.setProperty(prefix + ".nativeReturn.diagnostic",
            "PsdReplaceResult exposes public completion only; native return is not observable");
    }

    /**
     * Waits for two consecutive complete public observations to compare equal. The caller is the
     * probe worker; EDT use is rejected so a fresh export or public read can never block UI work.
     * The pacing callback only throttles bounded polls and is not a completion signal. Public SDK
     * calls remain synchronous: elapsed time is checked before and after each call, but an SDK call
     * that cannot be cancelled can make this worker return after the advisory window.
     */
    static BoundedSettleResult awaitBoundedSettle(final DiagnosticObservation initial,
        final ObservationSupplier supplier, final int maxAttempts, final long maxDurationMillis,
        final LongConsumer pacing) {
        Objects.requireNonNull(supplier, "supplier");
        return awaitBoundedSettleBounded(
            initial,
            remainingMillis -> supplier.get(),
            maxAttempts,
            maxDurationMillis,
            pacing,
            observation -> { }
        );
    }

    static BoundedSettleResult awaitBoundedSettle(final DiagnosticObservation initial,
        final BoundedObservationSupplier supplier, final int maxAttempts,
        final long maxDurationMillis, final LongConsumer pacing) {
        return awaitBoundedSettleBounded(
            initial, supplier, maxAttempts, maxDurationMillis, pacing, observation -> { });
    }

    static BoundedSettleResult awaitBoundedSettle(final DiagnosticObservation initial,
        final BoundedObservationSupplier supplier, final int maxAttempts,
        final long maxDurationMillis, final LongConsumer pacing,
        final ObservationRecorder recorder) {
        return awaitBoundedSettleBounded(
            initial, supplier, maxAttempts, maxDurationMillis, pacing, recorder);
    }

    static BoundedSettleResult awaitBoundedSettleBounded(
        final DiagnosticObservation initial, final BoundedObservationSupplier supplier,
        final int maxAttempts, final long maxDurationMillis, final LongConsumer pacing,
        final ObservationRecorder recorder) {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("bounded settle must run on the probe worker");
        }
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        if (maxDurationMillis < 1) {
            throw new IllegalArgumentException("maxDurationMillis must be positive");
        }
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(pacing, "pacing");
        Objects.requireNonNull(recorder, "recorder");
        final String criterion = "two consecutive complete observations equal (observation-stable "
            + "only; does not prove native completion); maxAttempts="
            + maxAttempts + ", maxDurationMs=" + maxDurationMillis;
        final long started = System.nanoTime();
        if (initial == null || !initial.complete()) {
            return settleResult(SettleStatus.UNAVAILABLE, 0, started, criterion,
                "initial public observation is unavailable", initial, false);
        }
        final long durationNanos = TimeUnit.MILLISECONDS.toNanos(maxDurationMillis);
        final long deadline = started + durationNanos;
        DiagnosticObservation previous = initial;
        DiagnosticObservation last = initial;
        int attempts = 0;
        long backoffMillis = BOUNDED_SETTLE_INITIAL_BACKOFF_MILLIS;
        while (attempts < maxAttempts && System.nanoTime() < deadline) {
            final long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) break;
            attempts++;
            final DiagnosticObservation current;
            try {
                current = supplier.get(Math.max(1L,
                    (remainingNanos + 999_999L) / 1_000_000L));
            } catch (ObservationBudgetExceededException late) {
                return settleResult(SettleStatus.TIMEOUT, attempts, started, criterion,
                    late.getMessage(), last, false);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                return settleResult(SettleStatus.FAILED, attempts, started, criterion,
                    failure.toString(), last, false);
            }
            if (System.nanoTime() >= deadline) {
                return settleResult(SettleStatus.TIMEOUT, attempts, started, criterion,
                    "observation returned after the settle deadline; late value was not recorded "
                        + "or considered stable", last, false);
            }
            last = current;
            if (current != null) {
                try {
                    recorder.record(current);
                } catch (RuntimeException failure) {
                    return settleResult(SettleStatus.FAILED, attempts, started, criterion,
                        "observation recording failed: " + failure, last, false);
                }
            }
            if (current == null || !current.complete()) {
                return settleResult(SettleStatus.UNAVAILABLE, attempts, started, criterion,
                    "public observation became unavailable", current, false);
            }
            if (sameDiagnosticObservation(previous, current)) {
                return settleResult(SettleStatus.STABLE, attempts, started, criterion,
                    "consecutive complete observations matched; observation-stable only", current,
                    true);
            }
            previous = current;
            if (attempts < maxAttempts && System.nanoTime() < deadline) {
                final long remainingForPacing = Math.max(0L,
                    remainingMillis(started, maxDurationMillis));
                final long waitMillis = Math.min(backoffMillis,
                    Math.max(0L, remainingForPacing));
                if (waitMillis > 0) {
                    try {
                        pacing.accept(TimeUnit.MILLISECONDS.toNanos(waitMillis));
                    } catch (RuntimeException failure) {
                        return settleResult(SettleStatus.FAILED, attempts, started, criterion,
                            "settle pacing failed: " + failure, last, false);
                    }
                }
                backoffMillis = Math.min(BOUNDED_SETTLE_MAX_BACKOFF_MILLIS,
                    Math.max(BOUNDED_SETTLE_INITIAL_BACKOFF_MILLIS, backoffMillis * 2));
            }
        }
        return settleResult(SettleStatus.TIMEOUT, attempts, started, criterion,
            "observations did not become stable within the bounded window", last, false);
    }

    static boolean sameDiagnosticObservation(final DiagnosticObservation first,
        final DiagnosticObservation second) {
        return first != null && second != null
            && first.status() == second.status()
            && first.diagnostic().equals(second.diagnostic())
            && first.rawIds().equals(second.rawIds())
            && first.modelImageIds().equals(second.modelImageIds())
            && first.selectorProjection().equals(second.selectorProjection())
            && first.livePsd().equals(second.livePsd())
            && first.relationsAvailable() == second.relationsAvailable()
            && first.livePsdAvailable() == second.livePsdAvailable()
            && Objects.equals(first.freshNativeRgb(), second.freshNativeRgb())
            && Objects.equals(first.observedTarget(), second.observedTarget())
            && first.targetIdentityVerified() == second.targetIdentityVerified();
    }

    static BoundedSettleResult settleResult(final SettleStatus status, final int attempts,
        final long started, final String criterion, final String diagnostic,
        final DiagnosticObservation last, final boolean stable) {
        return new BoundedSettleResult(status, attempts, elapsedMillis(started), criterion,
            diagnostic, stable, last);
    }

    static long elapsedMillis(final long started) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - started));
    }

    static void recordBoundedSettle(final Properties result, final String prefix,
        final BoundedSettleResult settled) {
        final String normalizedPrefix = RawExportCoordination.normalizePrefix(prefix);
        result.setProperty(normalizedPrefix + ".status", settled.status().name());
        result.setProperty(normalizedPrefix + ".stable", Boolean.toString(settled.stable()));
        result.setProperty(normalizedPrefix + ".attempts", Integer.toString(settled.attempts()));
        result.setProperty(normalizedPrefix + ".durationMs", Long.toString(settled.durationMillis()));
        result.setProperty(normalizedPrefix + ".criterion", settled.criterion());
        result.setProperty(normalizedPrefix + ".diagnostic", settled.diagnostic());
        result.setProperty(normalizedPrefix + ".nativeCompletion", "UNAVAILABLE");
        result.setProperty(normalizedPrefix + ".deadline.mode",
            "ADVISORY_SYNCHRONOUS_SDK_CALLS");
        result.setProperty(normalizedPrefix + ".deadline.hard", "false");
        result.setProperty(normalizedPrefix + ".deadline.sdkCallsCancellable", "false");
        result.setProperty(normalizedPrefix + ".deadline.diagnostic",
            "worker elapsed time is checked before and after each observation; public SDK "
                + "invokeAndWait/export calls are synchronous and cannot be cancelled safely");
        if (settled.lastObservation() != null) {
            recordDiagnosticObservation(result, normalizedPrefix + ".last",
                settled.lastObservation());
        }
    }

    static void recordSettleNotAttempted(final Properties result, final String prefix,
        final String diagnostic) {
        final String normalizedPrefix = RawExportCoordination.normalizePrefix(prefix);
        result.setProperty(normalizedPrefix + ".status", "NOT_ATTEMPTED");
        result.setProperty(normalizedPrefix + ".stable", "false");
        result.setProperty(normalizedPrefix + ".attempts", "0");
        result.setProperty(normalizedPrefix + ".durationMs", "0");
        result.setProperty(normalizedPrefix + ".criterion",
            "requires public APPLIED completion and a fresh native observation");
        result.setProperty(normalizedPrefix + ".diagnostic", diagnostic);
        result.setProperty(normalizedPrefix + ".nativeCompletion", "UNAVAILABLE");
        result.setProperty(normalizedPrefix + ".deadline.mode",
            "ADVISORY_SYNCHRONOUS_SDK_CALLS");
        result.setProperty(normalizedPrefix + ".deadline.hard", "false");
        result.setProperty(normalizedPrefix + ".deadline.sdkCallsCancellable", "false");
        result.setProperty(normalizedPrefix + ".deadline.diagnostic",
            "settle was not attempted; public SDK completion is not a native return signal");
    }

    static void recordFreshDiagnosticFailure(final Properties result,
        final String completionPrefix, final String settlePrefix, final Throwable failure) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(failure, "failure");
        final String completion = RawExportCoordination.normalizePrefix(completionPrefix);
        result.setProperty(completion + ".freshNativeRgb.status", "failed");
        result.setProperty(completion + ".freshNativeRgb.observed", "false");
        result.setProperty(completion + ".freshNativeRgb.failure", failure.toString());
        result.setProperty(completion + ".diagnosticFailure.status", "FAILED");
        result.setProperty(completion + ".diagnosticFailure.exception", failure.toString());
        result.setProperty(completion + ".diagnosticFailure.phase", "fresh-native-export");
        recordNativeReturnUnavailable(result, completion);
        recordSettleNotAttempted(
            result,
            settlePrefix,
            "fresh native diagnostic observation failed before settle: " + failure);
    }

    static void recordFreshDiagnosticFailurePreservingPrimary(
        final Properties result, final String completionPrefix, final String settlePrefix,
        final Throwable primary) {
        try {
            recordFreshDiagnosticFailure(result, completionPrefix, settlePrefix, primary);
        } catch (Throwable evidenceFailure) {
            TempExportTracker.addSuppressed(primary, evidenceFailure);
        }
    }

    /**
     * Returns the budget available to the next diagnostic SDK stage. A zero input retains the
     * existing unlimited-budget sentinel; a positive input is recomputed from the caller's
     * operation start so elapsed work is not granted again to the next stage. A positive budget
     * never returns zero: exhaustion throws before a caller can pass an unlimited sentinel onward.
     */
    static long remainingDiagnosticBudgetMillis(final long started, final long budgetMillis) {
        return remainingDiagnosticBudgetMillis(started, budgetMillis, "diagnostic stage");
    }

    static long remainingDiagnosticBudgetMillis(final long started,
        final long budgetMillis, final String operation) {
        if (budgetMillis <= 0L) return budgetMillis;
        final long remaining = remainingMillis(started, budgetMillis);
        if (remaining == 0L) {
            throw new ObservationBudgetExceededException(
                operation + " exceeded the remaining settle budget; SDK calls are synchronous "
                    + "and non-cancellable");
        }
        return remaining;
    }

    static void requireBudgetAvailable(final long started, final long budgetMillis,
        final String operation) throws ObservationBudgetExceededException {
        if (budgetMillis > 0L) {
            remainingDiagnosticBudgetMillis(started, budgetMillis, operation);
        }
    }

    enum DiagnosticStatus {
        AVAILABLE,
        UNAVAILABLE
    }

    /**
     * Immutable diagnostic snapshot of public relation/PSD metadata and one fresh native RGB
     * fingerprint. Metadata is evidence about observed bindings only; it is never a substitute
     * for the decoded fingerprint.
     */
    record DiagnosticObservation(DiagnosticStatus status, String diagnostic,
        String rawIds, String modelImageIds, String selectorProjection, String livePsd,
        boolean relationsAvailable, boolean livePsdAvailable,
        PsdValidationContent.Fingerprint freshNativeRgb, TargetResolution.TargetIdentity observedTarget,
        boolean targetIdentityVerified, long freshExportStartedAtEpochMillis,
        long freshExportCompletedAtEpochMillis, long metadataStartedAtEpochMillis,
        long metadataCompletedAtEpochMillis) {
        DiagnosticObservation(final DiagnosticStatus status, final String diagnostic,
            final String rawIds, final String modelImageIds, final String selectorProjection,
            final String livePsd, final boolean relationsAvailable,
            final boolean livePsdAvailable, final PsdValidationContent.Fingerprint freshNativeRgb) {
            this(
                status,
                diagnostic,
                rawIds,
                modelImageIds,
                selectorProjection,
                livePsd,
                relationsAvailable,
                livePsdAvailable,
                freshNativeRgb,
                TargetResolution.TargetIdentity.unavailable(),
                false,
                0L,
                0L,
                0L,
                0L
            );
        }

        DiagnosticObservation {
            status = Objects.requireNonNull(status, "status");
            diagnostic = diagnostic == null ? "" : diagnostic;
            rawIds = RawExportCoordination.valueOrUnavailable(rawIds);
            modelImageIds = RawExportCoordination.valueOrUnavailable(modelImageIds);
            selectorProjection = RawExportCoordination.valueOrUnavailable(selectorProjection);
            livePsd = RawExportCoordination.valueOrUnavailable(livePsd);
            observedTarget = observedTarget == null
                ? TargetResolution.TargetIdentity.unavailable() : observedTarget;
            if (freshExportStartedAtEpochMillis < 0L
                || freshExportCompletedAtEpochMillis < 0L
                || metadataStartedAtEpochMillis < 0L
                || metadataCompletedAtEpochMillis < 0L) {
                throw new IllegalArgumentException("diagnostic observation times must not be negative");
            }
        }

        static DiagnosticObservation available(final String rawIds, final String modelImageIds,
            final String selectorProjection, final String livePsd,
            final PsdValidationContent.Fingerprint freshNativeRgb) {
            return new DiagnosticObservation(
                DiagnosticStatus.AVAILABLE,
                "",
                rawIds,
                modelImageIds,
                selectorProjection,
                livePsd,
                true,
                true,
                freshNativeRgb
            );
        }

        static DiagnosticObservation unavailable(final String diagnostic) {
            return unavailable(diagnostic, null);
        }

        static DiagnosticObservation unavailable(final String diagnostic,
            final PsdValidationContent.Fingerprint freshNativeRgb) {
            return new DiagnosticObservation(
                DiagnosticStatus.UNAVAILABLE,
                diagnostic,
                RawExportCoordination.UNAVAILABLE_VALUE,
                RawExportCoordination.UNAVAILABLE_VALUE,
                RawExportCoordination.UNAVAILABLE_VALUE,
                RawExportCoordination.UNAVAILABLE_VALUE,
                false,
                false,
                freshNativeRgb
            );
        }

        boolean complete() {
            return status == DiagnosticStatus.AVAILABLE
                && relationsAvailable && livePsdAvailable && freshNativeRgb != null;
        }
    }

    enum SettleStatus {
        STABLE,
        TIMEOUT,
        FAILED,
        UNAVAILABLE
    }

    record BoundedSettleResult(SettleStatus status, int attempts, long durationMillis,
        String criterion, String diagnostic, boolean stable,
        DiagnosticObservation lastObservation) {
        BoundedSettleResult {
            status = Objects.requireNonNull(status, "status");
            if (attempts < 0) throw new IllegalArgumentException("attempts must not be negative");
            if (durationMillis < 0) {
                throw new IllegalArgumentException("durationMillis must not be negative");
            }
            criterion = Objects.requireNonNull(criterion, "criterion");
            diagnostic = diagnostic == null ? "" : diagnostic;
            if (stable && status != SettleStatus.STABLE) {
                throw new IllegalArgumentException("only STABLE settle results may be stable");
            }
        }
    }

    @FunctionalInterface
    interface ObservationSupplier {
        DiagnosticObservation get() throws Exception;
    }

    @FunctionalInterface
    interface BoundedObservationSupplier {
        DiagnosticObservation get(long remainingMillis) throws Exception;
    }

    @FunctionalInterface
    interface ObservationRecorder {
        void record(DiagnosticObservation observation);
    }

    static final class ObservationBudgetExceededException extends RuntimeException {
        static final long serialVersionUID = 1L;

        ObservationBudgetExceededException(final String message) {
            super(message);
        }
    }
}
