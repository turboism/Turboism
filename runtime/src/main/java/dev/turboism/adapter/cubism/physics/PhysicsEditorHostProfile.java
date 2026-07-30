package dev.turboism.adapter.cubism.physics;

import dev.turboism.mapping.verification.HostArtifactDigest;

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
    private static final HostArtifactDigest CUBISM_52 = new HostArtifactDigest(
        40_805_584L,
        "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd"
    );
    private static final HostArtifactDigest CUBISM_53 = new HostArtifactDigest(
        41_922_739L,
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21"
    );

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

    public static Optional<PhysicsEditorHostProfile> forArtifact(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        if (!artifact.equals(CUBISM_52) && !artifact.equals(CUBISM_53)) return Optional.empty();
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
