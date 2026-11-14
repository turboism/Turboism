package dev.turboism.adapter.cubism.physics;

import dev.turboism.internal.Requirements;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.Objects;
import java.util.Optional;

/** Exact reviewed selector tuple for the supported Physics Settings group-list host. */
public record PhysicsEditorHostProfile(
        String panelOwnerInternalName,
        String tableGetter,
        String outerField,
        String sourceSetGetter,
        String sourcesGetter,
        String enableGetter,
        String enableSetter,
        String identityGetter,
        String checkpointMethod,
        String commitMethod,
        String rollbackMethod) {
    public PhysicsEditorHostProfile {
        panelOwnerInternalName = Requirements.text(panelOwnerInternalName, "panelOwnerInternalName");
        tableGetter = Requirements.text(tableGetter, "tableGetter");
        outerField = Requirements.text(outerField, "outerField");
        sourceSetGetter = Requirements.text(sourceSetGetter, "sourceSetGetter");
        sourcesGetter = Requirements.text(sourcesGetter, "sourcesGetter");
        enableGetter = Requirements.text(enableGetter, "enableGetter");
        enableSetter = Requirements.text(enableSetter, "enableSetter");
        identityGetter = Requirements.text(identityGetter, "identityGetter");
        checkpointMethod = Requirements.text(checkpointMethod, "checkpointMethod");
        commitMethod = Requirements.text(commitMethod, "commitMethod");
        rollbackMethod = Requirements.text(rollbackMethod, "rollbackMethod");
    }

    /**
     * Resolves the reviewed selector tuple for a host artifact.
     *
     * <p>Every admitted Cubism build (5.0.00–5.3.04) maps to the identical selector tuple — the
     * whole member chain verifies on each reviewed artifact. Any other artifact yields empty, which
     * is the fail-closed signal that the physics editor hook must not be installed against an
     * unreviewed host.
     *
     * @param artifact digest of the host jar actually loaded
     * @return the matching profile, or empty when the artifact is not a reviewed build
     * @throws NullPointerException if {@code artifact} is {@code null}
     */
    public static Optional<PhysicsEditorHostProfile> forArtifact(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        if (ReviewedHostArtifacts.cubismVersionOf(artifact).isEmpty()) {
            return Optional.empty();
        }
        return reviewed();
    }

    /**
     * Resolves the reviewed selector tuple by declared reviewed generation for
     * a compatibility host that bound its declared version: the artifact
     * digest is not identical but the slice contract verified the declared
     * generation's whole record against it.
     *
     * @param cubismVersion host-declared reviewed version
     * @return the matching profile, or empty for any unreviewed version
     */
    public static Optional<PhysicsEditorHostProfile> forReviewedVersion(final String cubismVersion) {
        Objects.requireNonNull(cubismVersion, "cubismVersion");
        if (!ReviewedHostArtifacts.admitsFullRuntime(cubismVersion)) {
            return Optional.empty();
        }
        return reviewed();
    }

    private static Optional<PhysicsEditorHostProfile> reviewed() {
        return Optional.of(new PhysicsEditorHostProfile(
                "com/live2d/cubism/doc/modeling/ui/viewer/physics/ViewerPhysics_GroupList$GroupListPanel",
                "getTableArea",
                "this$0",
                "l",
                "getSources",
                "getEnable",
                "setEnable",
                "getGuid",
                "b",
                "n",
                "d"));
    }
}
