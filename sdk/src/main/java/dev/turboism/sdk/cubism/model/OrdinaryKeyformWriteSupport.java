package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only observations of stored ordinary writer infrastructure, not a write authorization.
 * Form restrictions, current edit state, CAS, payload limits and transaction admission must
 * still be checked at the actual write boundary. No setter or transaction is used to probe.
 * Unknown preserves compatibility with runtimes that do not implement this observation.
 */
@CubismEditor
public record OrdinaryKeyformWriteSupport(
        String hostVersion,
        Check reviewedHost,
        Check writerSelectors,
        Check historySelectors,
        Check transactionCoordinator,
        Check writePermission,
        Map<Operation, Check> operationSelectors) {
    /** Closed operation identities. Missing entries mean UNKNOWN, not unavailable or admitted. */
    public enum Operation {
        ART_MESH_POSITIONS,
        ART_MESH_DRAW_ORDERS,
        ART_MESH_VALUES,
        ART_MESH_VALUES_WITH_OPACITY,
        WARP_CONTROL_POINTS,
        ROTATION_FORMS
    }

    /** Compatibility constructor for providers without per-operation selector observations. */
    public OrdinaryKeyformWriteSupport(
            String hostVersion,
            Check reviewedHost,
            Check writerSelectors,
            Check historySelectors,
            Check transactionCoordinator,
            Check writePermission) {
        this(
                hostVersion,
                reviewedHost,
                writerSelectors,
                historySelectors,
                transactionCoordinator,
                writePermission,
                Map.of());
    }

    /** Presence of an observed writer prerequisite; not authorization to write. */
    public enum Check {
        UNKNOWN,
        PRESENT,
        ABSENT
    }

    public OrdinaryKeyformWriteSupport {
        Objects.requireNonNull(hostVersion);
        Objects.requireNonNull(reviewedHost);
        Objects.requireNonNull(writerSelectors);
        Objects.requireNonNull(historySelectors);
        Objects.requireNonNull(transactionCoordinator);
        Objects.requireNonNull(writePermission);
        operationSelectors = Map.copyOf(operationSelectors);
    }

    /** Returns UNKNOWN observations without claiming writer availability. */
    public static OrdinaryKeyformWriteSupport unknown() {
        return new OrdinaryKeyformWriteSupport(
                "", Check.UNKNOWN, Check.UNKNOWN, Check.UNKNOWN, Check.UNKNOWN, Check.UNKNOWN);
    }

    /** A selector observation only; all other write guards still apply. */
    public Check selectorsFor(Operation operation) {
        return operationSelectors.getOrDefault(Objects.requireNonNull(operation), Check.UNKNOWN);
    }

    /** Returns a copy with the supplied permission observation; other guards remain unchanged. */
    public OrdinaryKeyformWriteSupport withWritePermission(Check permission) {
        return new OrdinaryKeyformWriteSupport(
                hostVersion,
                reviewedHost,
                writerSelectors,
                historySelectors,
                transactionCoordinator,
                permission,
                operationSelectors);
    }
}
