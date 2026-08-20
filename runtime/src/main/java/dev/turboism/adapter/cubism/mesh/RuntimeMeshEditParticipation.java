package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshDeletion;
import dev.turboism.sdk.cubism.mesh.MeshEditContribution;
import dev.turboism.sdk.cubism.mesh.MeshEditParticipant;
import dev.turboism.sdk.cubism.mesh.MeshEditParticipation;
import dev.turboism.sdk.plugin.Registration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry and dispatch for plugins taking part in host-initiated mesh edits.
 *
 * <p>A participant returns a description of what it wants deleted; this class never executes
 * plugin-supplied code beyond that single call, and a participant that throws is dropped rather
 * than allowed to affect the host's own edit.</p>
 */
public final class RuntimeMeshEditParticipation implements MeshEditParticipation {

    private static final long CALLBACK_BUDGET_NANOS = 10_000_000L;
    private final List<MeshEditParticipant> participants = new CopyOnWriteArrayList<>();

    @Override
    public Registration participate(final MeshEditParticipant participant) {
        Objects.requireNonNull(participant, "participant");
        participants.add(participant);
        return () -> participants.remove(participant);
    }

    public boolean hasParticipants() {
        return !participants.isEmpty();
    }

    /**
     * Collects every participant's contribution. Failures are isolated: one participant throwing
     * costs only its own contribution, never the host edit or the other participants.
     */
    public MeshEditContribution collect(final MeshDeletion deletion) {
        if (participants.isEmpty()) return MeshEditContribution.none();
        final List<dev.turboism.sdk.cubism.mesh.MeshPointRef> points = new ArrayList<>();
        final List<dev.turboism.sdk.cubism.mesh.MeshEdgeRef> edges = new ArrayList<>();
        for (MeshEditParticipant participant : participants) {
            final MeshEditContribution contribution;
            final NativeMeshMirrorBridge.ProvenanceMark provenance =
                NativeMeshMirrorBridge.markDefaultProvenance();
            final long startedAt = System.nanoTime();
            try {
                contribution = participant.onDeleting(deletion);
            } catch (Throwable failure) {
                NativeMeshMirrorBridge.restoreDefaultProvenance(provenance);
                NativeMeshMirrorBridge.diagnostic(
                    "PARTICIPANT_FAILED reason=" + failure.getClass().getName()
                );
                continue;
            } finally {
                final long elapsed = System.nanoTime() - startedAt;
                if (elapsed > CALLBACK_BUDGET_NANOS) {
                    NativeMeshMirrorBridge.diagnostic(
                        "PARTICIPANT_BUDGET_EXCEEDED elapsedNanos=" + elapsed
                    );
                }
            }
            if (contribution == null || contribution.isEmpty()) continue;
            NativeMeshMirrorBridge.rememberCollectedContribution(contribution);
            points.addAll(contribution.points());
            edges.addAll(contribution.edges());
        }
        return points.isEmpty() && edges.isEmpty()
            ? MeshEditContribution.none()
            : new MeshEditContribution(points, edges);
    }

    public void resetSession() {
        participants.clear();
    }
}
