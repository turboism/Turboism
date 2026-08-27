package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact reviewed context-menu hook bindings for one supported Cubism artifact. */
record ObjectContextMenuHostProfile(List<VerifiedObjectContextMenuHookInstaller.Binding> bindings) {

    private static final HostArtifactDigest CUBISM_52 = ReviewedHostArtifacts.CUBISM_5_2_03;
    private static final HostArtifactDigest CUBISM_53 = ReviewedHostArtifacts.CUBISM_5_3_02;
    private static final HostArtifactDigest CUBISM_5303 = ReviewedHostArtifacts.CUBISM_5_3_03;
    private static final String MENU = "com/live2d/ui/menu/k";
    private static final String ITEM = "Lcom/live2d/ui/menu/CMenuItem;";

    ObjectContextMenuHostProfile {
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        if (bindings.size() != 4) {
            throw new IllegalArgumentException("object context-menu profile requires four bindings");
        }
    }

    static Optional<ObjectContextMenuHostProfile> forArtifact(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        if (artifact.equals(CUBISM_52)) {
            return Optional.of(profile("R", "aL", 7, 21, 23, 3, 2));
        }
        if (artifact.equals(CUBISM_53)) {
            return Optional.of(profile("T", "aL", 7, 22, 22, 1, 1));
        }
        if (artifact.equals(CUBISM_5303)) {
            return Optional.of(profile("T", "aM", 10, 22, 22, 3, 1));
        }
        return Optional.empty();
    }

    private static ObjectContextMenuHostProfile profile(
        final String partsOwner,
        final String parameterOwner,
        final int parameterAppends,
        final int partsAppends,
        final int workspaceAppends,
        final int deformerInjectionPoint,
        final int partsInjectionPoint
    ) {
        return new ObjectContextMenuHostProfile(List.of(
            append(
                "deformer", "com/live2d/cubism/view/palette/deformer/b", "a",
                "(Ljava/awt/event/MouseEvent;)V", "b", "(" + ITEM + ")V",
                Location.DEFORMER_TAB, 11, deformerInjectionPoint, 2, 3
            ),
            append(
                "parameter", "com/live2d/cubism/view/palette/parameter/" + parameterOwner, "a",
                "(Ljava/awt/event/MouseEvent;)V", "c", "(" + ITEM + ")V",
                Location.PARAMETER_TAB, parameterAppends, 3, 2
            ),
            append(
                "parts", "com/live2d/cubism/view/palette/parts/" + partsOwner, "a",
                "(Ljava/awt/event/MouseEvent;)V", "b", "(" + ITEM + ")V",
                Location.PART_TAB, partsAppends, partsInjectionPoint, 5
            ),
            append(
                "workspace", "com/live2d/cubism/view/context/U", "b",
                "(Lcom/live2d/cubism/view/context/actionManager/N;)V", "a",
                "(" + ITEM + "Ljava/awt/GridBagConstraints;)V",
                Location.WORKSPACE_OBJECT, workspaceAppends, 1, 1
            )
        ));
    }

    private static VerifiedObjectContextMenuHookInstaller.Binding append(
        final String id,
        final String owner,
        final String method,
        final String descriptor,
        final String appendMethod,
        final String appendDescriptor,
        final Location location,
        final int expectedAppends,
        final int injectionPoint,
        final int... sourceLocals
    ) {
        return VerifiedObjectContextMenuHookInstaller.Binding.appendPoint(
            StaticSelector.method("object-context-menu." + id, owner, method, descriptor, 0),
            StaticSelector.method("object-context-menu." + id + ".append", MENU, appendMethod, appendDescriptor, 0),
            location,
            expectedAppends,
            injectionPoint,
            sourceLocals
        );
    }
}
