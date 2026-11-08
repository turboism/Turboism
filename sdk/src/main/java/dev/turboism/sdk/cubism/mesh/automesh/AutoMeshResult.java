package dev.turboism.sdk.cubism.mesh.automesh;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import java.util.Objects;

/**
 * Outcome of one auto-mesh run.
 *
 * <p>The three cases are deliberately distinct, because the native popup treats them differently:
 * {@link Success} replaces the target geometry, {@link Rejected} keeps the popup open and shows
 * {@link Rejected#reason()} to the user, and {@link Cancelled} is a no-op. Representing a refusal
 * as an exception would turn an expected user-facing message into a stack trace.</p>
 */
@CubismEditor
public sealed interface AutoMeshResult
        permits AutoMeshResult.Success, AutoMeshResult.Rejected, AutoMeshResult.Cancelled {

    /** Geometry the runtime must apply, committed as one Editor operation. */
    record Success(ArtMeshGeometry geometry, String diagnosticSummary) implements AutoMeshResult {

        /** Longest accepted diagnostic summary. */
        public static final int MAX_DIAGNOSTIC_SUMMARY_LENGTH = 512;

        public Success {
            geometry = Objects.requireNonNull(geometry, "geometry");
            diagnosticSummary = diagnosticSummary == null ? "" : diagnosticSummary;
            if (diagnosticSummary.length() > MAX_DIAGNOSTIC_SUMMARY_LENGTH) {
                throw new IllegalArgumentException(
                        "diagnosticSummary must contain at most " + MAX_DIAGNOSTIC_SUMMARY_LENGTH + " characters");
            }
        }
    }

    /**
     * Refusal the runtime shows to the user as a message.
     *
     * <p>{@code reasonCode} is a stable machine token the runtime may translate or match on;
     * {@code reason} is the user-facing text.</p>
     */
    record Rejected(String reasonCode, String reason) implements AutoMeshResult {

        /** Longest accepted reason text. */
        public static final int MAX_REASON_LENGTH = 512;

        public Rejected {
            reasonCode = AutoMeshContracts.requireId(reasonCode, "reasonCode");
            reason = AutoMeshContracts.requireText(reason, "reason", MAX_REASON_LENGTH);
        }
    }

    /** Cancellation notice: the run produced no geometry and the runtime writes nothing. */
    enum Cancelled implements AutoMeshResult {
        INSTANCE
    }
}
