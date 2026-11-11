package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshEditContributionSurface;
import dev.turboism.sdk.cubism.mesh.MeshEditParticipant;
import dev.turboism.sdk.plugin.Registration;
import java.util.Objects;

/**
 * The consolidated contribution surface a plugin receives: host-edit participation and the
 * native tool-area mirror-axis control over the session-shared registries.
 *
 * <p>Both halves are owned by the host session — the participant list because the deletion hook
 * dispatches through it, the control because the native row can only be mounted once — so this
 * class holds no state and only states the two verbs as one capability.</p>
 */
public final class RuntimeMeshEditContributionSurface implements MeshEditContributionSurface {

    private final RuntimeMeshEditParticipation participation;
    private final RuntimeMeshEditUiService ui;

    /**
     * @param participation the session-shared participant registry, never {@code null}
     * @param ui the session-shared native tool-area control owner, never {@code null}
     */
    public RuntimeMeshEditContributionSurface(
            final RuntimeMeshEditParticipation participation, final RuntimeMeshEditUiService ui) {
        this.participation = Objects.requireNonNull(participation, "participation");
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    @Override
    public Registration participate(final MeshEditParticipant participant) {
        return participation.participate(participant);
    }

    @Override
    public Registration contributeMirrorAxisAngleControl(final MirrorAxisAngleControl contribution) {
        Objects.requireNonNull(contribution, "contribution");
        return ui.contributeMirrorAxisAngleControl(contribution);
    }
}
