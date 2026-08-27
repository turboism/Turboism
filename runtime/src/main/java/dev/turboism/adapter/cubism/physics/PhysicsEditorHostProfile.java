package dev.turboism.adapter.cubism.physics;

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
    String rollbackMethod
) {
    private static final HostArtifactDigest CUBISM_52 = ReviewedHostArtifacts.CUBISM_5_2_03;
    private static final HostArtifactDigest CUBISM_53 = ReviewedHostArtifacts.CUBISM_5_3_02;
    private static final HostArtifactDigest CUBISM_5303 = ReviewedHostArtifacts.CUBISM_5_3_03;

    public PhysicsEditorHostProfile {
        panelOwnerInternalName = text(panelOwnerInternalName, "panelOwnerInternalName");
        tableGetter = text(tableGetter, "tableGetter");
        outerField = text(outerField, "outerField");
        sourceSetGetter = text(sourceSetGetter, "sourceSetGetter");
        sourcesGetter = text(sourcesGetter, "sourcesGetter");
        enableGetter = text(enableGetter, "enableGetter");
        enableSetter = text(enableSetter, "enableSetter");
        identityGetter = text(identityGetter, "identityGetter");
        checkpointMethod = text(checkpointMethod, "checkpointMethod");
        commitMethod = text(commitMethod, "commitMethod");
        rollbackMethod = text(rollbackMethod, "rollbackMethod");
    }

    /**
     * Resolves the reviewed selector tuple for a host artifact.
     *
     * <p>Only the three admitted Cubism builds are recognised, 5.2.03, 5.3.02 and 5.3.03, and all
     * map to the identical selector tuple. Any other artifact yields empty, which is the fail-closed signal that
     * the physics editor hook must not be installed against an unreviewed host.
     *
     * @param artifact digest of the host jar actually loaded
     * @return the matching profile, or empty when the artifact is not a reviewed build
     * @throws NullPointerException if {@code artifact} is {@code null}
     */
    public static Optional<PhysicsEditorHostProfile> forArtifact(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        if (!artifact.equals(CUBISM_52)
            && !artifact.equals(CUBISM_53)
            && !artifact.equals(CUBISM_5303)) {
            return Optional.empty();
        }
        return Optional.of(new PhysicsEditorHostProfile(
            "com/live2d/cubism/doc/modeling/ui/viewer/physics/ViewerPhysics_GroupList$GroupListPanel",
            "getTableArea", "this$0", "l", "getSources", "getEnable", "setEnable", "getGuid",
            "b", "n", "d"
        ));
    }

    private static String text(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
