package dev.turboism.plugin.boundingboxwarpmirror.mirror;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.mirror.WarpMirrorBlocker;
import dev.turboism.sdk.cubism.mirror.WarpMirrorBlockerCode;
import dev.turboism.sdk.cubism.mirror.WarpMirrorDirection;
import dev.turboism.sdk.cubism.mirror.WarpMirrorOutcome;
import dev.turboism.sdk.cubism.mirror.WarpMirrorRequest;
import dev.turboism.sdk.cubism.mirror.WarpMirrorResult;
import dev.turboism.sdk.cubism.mirror.WarpMirrorService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies one dialog-confirmed mirror choice to each selected Warp Deformer in order.
 * Every target is an independent {@link WarpMirrorService#apply} call — one native
 * transaction and one Undo entry per target — and a failed target does not stop later
 * targets, matching the legacy per-target dispatch.
 */
public final class MirrorOperationRunner {

    /** One target that did not apply cleanly. */
    public record Failure(
        String targetId,
        WarpMirrorOutcome outcome,
        List<WarpMirrorBlockerCode> blockerCodes
    ) { }

    /** Aggregated outcome of one confirmed dialog run. */
    public record Summary(
        int applied,
        int noChange,
        List<Failure> failures
    ) {
        public Summary {
            failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        }

        /** @return true when at least one target failed or rolled back */
        public boolean hasFailures() {
            return !failures.isEmpty();
        }

        /** @return true when a rollback could not be verified and must be surfaced loudly */
        public boolean recoveryFailed() {
            return failures.stream().anyMatch(f -> f.outcome() == WarpMirrorOutcome.RECOVERY_FAILED);
        }
    }

    /**
     * Applies {@code direction} to each target in order, aggregating per-target outcomes.
     * Targets are independent operations: a blocked or failed target is recorded and
     * later targets still run.
     *
     * @param service the mirror service to invoke once per target
     * @param targets ordered Warp Deformer targets (parents first)
     * @param direction the confirmed mirror direction
     * @param preserveDescendants whether descendant canvas geometry must be preserved
     * @return the aggregated summary
     */
    public Summary apply(
        final WarpMirrorService service,
        final List<DeformerId> targets,
        final WarpMirrorDirection direction,
        final boolean preserveDescendants
    ) {
        Objects.requireNonNull(service, "service");
        int applied = 0;
        int noChange = 0;
        final ArrayList<Failure> failures = new ArrayList<>();
        for (DeformerId target : targets) {
            final WarpMirrorResult result = service.apply(
                new WarpMirrorRequest(target, direction, preserveDescendants));
            switch (result.outcome()) {
                case APPLIED -> applied++;
                case NO_CHANGE -> noChange++;
                case BLOCKED, RECOVERY_FAILED -> failures.add(new Failure(
                    target.value(),
                    result.outcome(),
                    result.blockers().stream().map(WarpMirrorBlocker::code).toList()
                ));
            }
        }
        return new Summary(applied, noChange, List.copyOf(failures));
    }
}
