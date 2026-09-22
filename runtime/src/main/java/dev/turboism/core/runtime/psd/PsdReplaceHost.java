package dev.turboism.core.runtime.psd;

import dev.turboism.sdk.cubism.id.RawImageId;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime-only replace port implemented by a current-model texture projection, never exposed by the
 * permission-checked SDK wrapper. Paths and admission callbacks are trusted runtime inputs.
 *
 * <p>The projection must run the whole operation inside one host-thread boundary: bind the exact
 * target, resolve the current document, parse the staged file, invoke Cubism's native replace, then
 * re-read current state. It reports facts only; deciding APPLIED/REJECTED/PARTIAL_FAILURE and
 * consuming a revision belongs to {@link RuntimePsdReplaceService}.</p>
 */
public interface PsdReplaceHost {
    /**
     * Attempts one explicit-target native replacement with policy revalidation on the host thread.
     *
     * @param target exact raw image to replace inside the projected model session
     * @param stage runtime-owned staged PSD that must be used as the incoming content
     * @param admission revalidates permission and scope immediately before native entry
     */
    Replacement replaceWithStagedPsd(RawImageId target, Path stage, Runnable admission);

    /**
     * Factual outcome of one replace attempt.
     *
     * @param nativeStatus sanitized native token for the diagnostic
     * @param sessionCurrent whether the projected model session was still the current one afterwards
     * @param nativeReturned whether the native call returned without throwing
     * @param mutationUnknown whether a mutation may have happened without a usable outcome
     * @param editingRejected whether an in-progress host edit blocked the attempt before mutation
     * @param relationsAvailable whether current state was re-read successfully after the attempt
     * @param afterRawImageId raw image observed for the affected model images when available
     * @param detail sanitized explanation
     * @param failure optional bounded failure phase and category
     */
    record Replacement(
        String nativeStatus,
        boolean sessionCurrent,
        boolean nativeReturned,
        boolean mutationUnknown,
        boolean editingRejected,
        boolean relationsAvailable,
        Optional<RawImageId> afterRawImageId,
        String detail,
        Optional<Failure> failure
    ) {
        public Replacement {
            Objects.requireNonNull(nativeStatus, "nativeStatus");
            Objects.requireNonNull(afterRawImageId, "afterRawImageId");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(failure, "failure");
            if (nativeReturned && mutationUnknown) {
                throw new IllegalArgumentException("a returned native call cannot also be unknown");
            }
        }

        public Replacement(final String nativeStatus, final boolean sessionCurrent,
            final boolean nativeReturned, final boolean mutationUnknown, final boolean editingRejected,
            final boolean relationsAvailable, final Optional<RawImageId> afterRawImageId, final String detail) {
            this(nativeStatus, sessionCurrent, nativeReturned, mutationUnknown, editingRejected,
                relationsAvailable, afterRawImageId, detail, Optional.empty());
        }

        /** Unsupported projections must not invoke native operations. */
        public static Replacement unavailable() {
            return new Replacement(
                "UNAVAILABLE", false, false, false, false, false, Optional.empty(),
                "The current model projection cannot perform a native PSD replacement."
            );
        }
    }

    /** Fixed adapter tokens only; runtime sanitizes them again before SDK publication. */
    record Failure(String phase, String category) {
        public Failure {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(category, "category");
        }
    }
}
