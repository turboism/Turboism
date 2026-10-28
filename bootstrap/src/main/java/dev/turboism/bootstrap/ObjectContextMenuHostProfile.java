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

    private static final String MENU = "com/live2d/ui/menu/k";
    private static final String ITEM = "Lcom/live2d/ui/menu/CMenuItem;";

    ObjectContextMenuHostProfile {
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        if (bindings.size() != 4) {
            throw new IllegalArgumentException("object context-menu profile requires four bindings");
        }
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ObjectContextMenuHostProfile other)) return false;
        if (bindings.size() != other.bindings.size()) return false;
        for (int i = 0; i < bindings.size(); i++) {
            final VerifiedObjectContextMenuHookInstaller.Binding a = bindings.get(i);
            final VerifiedObjectContextMenuHookInstaller.Binding b = other.bindings.get(i);
            if (!Objects.equals(a.operation(), b.operation())
                    || !Objects.equals(a.append(), b.append())
                    || a.location() != b.location()
                    || a.shape() != b.shape()
                    || a.expectedAppendPoints() != b.expectedAppendPoints()
                    || a.injectionPoint() != b.injectionPoint()
                    || !java.util.Arrays.equals(a.sourceLocals(), b.sourceLocals())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (final VerifiedObjectContextMenuHookInstaller.Binding b : bindings) {
            result = 31 * result
                    + Objects.hash(
                            b.operation(),
                            b.append(),
                            b.location(),
                            b.shape(),
                            b.expectedAppendPoints(),
                            b.injectionPoint(),
                            java.util.Arrays.hashCode(b.sourceLocals()));
        }
        return result;
    }

    static Optional<ObjectContextMenuHostProfile> forArtifact(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        return ReviewedHostArtifacts.cubismVersionOf(artifact)
                .flatMap(ObjectContextMenuHostProfile::forReviewedVersion);
    }

    /**
     * Resolves the reviewed context-menu profile by declared reviewed
     * generation for a compatibility host that bound its declared version.
     */
    static Optional<ObjectContextMenuHostProfile> forReviewedVersion(final String cubismVersion) {
        Objects.requireNonNull(cubismVersion, "cubismVersion");
        if (!ReviewedHostArtifacts.reviewedCubismVersions().contains(cubismVersion)) {
            return Optional.empty();
        }
        return switch (cubismVersion) {
            case "5.0.00", "5.0.01", "5.0.02" ->
                Optional.of(profile("S", "a", "b", 6, "am", "a", 7, "O", 15, 22, 3, 2));
            case "5.0.03", "5.0.04" -> Optional.of(profile("S", "a", "b", 6, "ap", "a", 7, "O", 15, 22, 3, 2));
            case "5.0.05", "5.0.06" -> Optional.of(profile("S", "a", "b", 9, "ap", "a", 7, "O", 18, 22, 3, 2));
            case "5.1.00", "5.1.01" -> Optional.of(profile("U", "b", "c", 11, "av", "a", 7, "Q", 19, 22, 3, 2));
            case "5.1.02", "5.1.03" -> Optional.of(profile("U", "b", "c", 11, "az", "a", 7, "Q", 19, 22, 3, 2));
            case "5.2.00", "5.2.01", "5.2.02" ->
                Optional.of(profile("U", "b", "c", 11, "aL", "a", 7, "Q", 19, 22, 3, 2));
            case "5.2.03" -> Optional.of(profile("U", "b", "c", 11, "aL", "a", 7, "R", 21, 23, 3, 2));
            case "5.3.00", "5.3.01", "5.3.02" ->
                Optional.of(profile("U", "b", "c", 11, "aL", "a", 7, "T", 22, 22, 1, 1));
            case "5.3.03" -> Optional.of(profile("U", "b", "c", 11, "aM", "a", 10, "T", 22, 22, 3, 1));
            // Same tuple as 5.3.03 except the menu-building mouse handler moved from
            // aM.a(MouseEvent) to aM.b(MouseEvent); aM.a is a public delegate on 5.3.04.
            case "5.3.04" -> Optional.of(profile("U", "b", "c", 11, "aM", "b", 10, "T", 22, 22, 3, 1));
            default -> Optional.empty();
        };
    }

    private static ObjectContextMenuHostProfile profile(
            final String workspaceOwner,
            final String deformerAndPartsAppendMethod,
            final String parameterAppendMethod,
            final int deformerAppends,
            final String parameterOwner,
            final String parameterMethod,
            final int parameterAppends,
            final String partsOwner,
            final int partsAppends,
            final int workspaceAppends,
            final int deformerInjectionPoint,
            final int partsInjectionPoint) {
        return new ObjectContextMenuHostProfile(List.of(
                append(
                        "deformer",
                        "com/live2d/cubism/view/palette/deformer/b",
                        "a",
                        "(Ljava/awt/event/MouseEvent;)V",
                        deformerAndPartsAppendMethod,
                        "(" + ITEM + ")V",
                        Location.DEFORMER_TAB,
                        deformerAppends,
                        deformerInjectionPoint,
                        2,
                        3),
                append(
                        "parameter",
                        "com/live2d/cubism/view/palette/parameter/" + parameterOwner,
                        parameterMethod,
                        "(Ljava/awt/event/MouseEvent;)V",
                        parameterAppendMethod,
                        "(" + ITEM + ")V",
                        Location.PARAMETER_TAB,
                        parameterAppends,
                        3,
                        2),
                append(
                        "parts",
                        "com/live2d/cubism/view/palette/parts/" + partsOwner,
                        "a",
                        "(Ljava/awt/event/MouseEvent;)V",
                        deformerAndPartsAppendMethod,
                        "(" + ITEM + ")V",
                        Location.PART_TAB,
                        partsAppends,
                        partsInjectionPoint,
                        5),
                append(
                        "workspace",
                        "com/live2d/cubism/view/context/" + workspaceOwner,
                        "b",
                        "(Lcom/live2d/cubism/view/context/actionManager/N;)V",
                        "a",
                        "(" + ITEM + "Ljava/awt/GridBagConstraints;)V",
                        Location.WORKSPACE_OBJECT,
                        workspaceAppends,
                        1,
                        1)));
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
            final int... sourceLocals) {
        return VerifiedObjectContextMenuHookInstaller.Binding.appendPoint(
                StaticSelector.method("object-context-menu." + id, owner, method, descriptor, 0),
                StaticSelector.method("object-context-menu." + id + ".append", MENU, appendMethod, appendDescriptor, 0),
                location,
                expectedAppends,
                injectionPoint,
                sourceLocals);
    }
}
