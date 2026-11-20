package dev.turboism.adapter.cubism.profile;

import dev.turboism.internal.ReviewedVersions;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Exact reviewed object context-menu bindings for one supported Cubism artifact.
 *
 * <p>The profile is data only: it names the host methods and the cardinalities the transform must
 * prove, and the installer adapts each binding to its transformer. Keeping the host names here
 * rather than in {@code :bootstrap} is what lets the composition layer stay free of host-package
 * literals.</p>
 *
 * @param bindings the four reviewed bindings, in deformer, parameter, parts, workspace order
 */
public record ObjectContextMenuHostProfile(List<ContextMenuBindingSpec> bindings) {

    /** Menu class every append binding writes into. */
    private static final String MENU = "com/live2d/ui/menu/k";

    /** Descriptor of the menu item an append binding adds. */
    private static final String ITEM = "Lcom/live2d/ui/menu/CMenuItem;";

    /** Deformer palette owner that opens the deformer-tab menu. */
    private static final String DEFORMER_OWNER = "com/live2d/cubism/view/palette/deformer/b";

    /** Parameter palette owner prefix; the reviewed generation supplies the class letter. */
    private static final String PARAMETER_OWNER_PREFIX = "com/live2d/cubism/view/palette/parameter/";

    /** Parts palette owner prefix; the reviewed generation supplies the class letter. */
    private static final String PARTS_OWNER_PREFIX = "com/live2d/cubism/view/palette/parts/";

    /** Workspace context owner prefix; the reviewed generation supplies the class letter. */
    private static final String WORKSPACE_OWNER_PREFIX = "com/live2d/cubism/view/context/";

    /** Mouse-event descriptor every reviewed menu-opening method declares. */
    private static final String MOUSE_DESCRIPTOR = "(Ljava/awt/event/MouseEvent;)V";

    /** Descriptor of the plain menu-item append method. */
    private static final String APPEND_DESCRIPTOR = "(" + ITEM + ")V";

    /** Descriptor of the workspace menu-item append method, which also places the item. */
    private static final String WORKSPACE_APPEND_DESCRIPTOR = "(" + ITEM + "Ljava/awt/GridBagConstraints;)V";

    /** Deformer-tab menu-opening method name, identical on every reviewed generation. */
    private static final String DEFORMER_METHOD = "a";

    /** Parts-tab menu-opening method name, identical on every reviewed generation. */
    private static final String PARTS_METHOD = "a";

    /** Workspace menu-opening method name, identical on every reviewed generation. */
    private static final String WORKSPACE_METHOD = "b";

    /** Workspace append method name, identical on every reviewed generation. */
    private static final String WORKSPACE_APPEND_METHOD = "a";

    /** The four bindings a profile declares, one per menu location. */
    private static final int BINDING_COUNT = 4;

    private record GenerationProfile(Set<String> versions, ObjectContextMenuHostProfile profile) {}

    private static final List<GenerationProfile> GENERATION_PROFILES = List.of(
            new GenerationProfile(
                    ReviewedVersions.between("5.0.00", "5.0.02"),
                    profile("S", "a", "b", 6, "am", "a", 7, "O", 15, 22, 3, 2)),
            new GenerationProfile(
                    ReviewedVersions.between("5.0.03", "5.0.04"),
                    profile("S", "a", "b", 6, "ap", "a", 7, "O", 15, 22, 3, 2)),
            new GenerationProfile(
                    ReviewedVersions.between("5.0.05", "5.0.06"),
                    profile("S", "a", "b", 9, "ap", "a", 7, "O", 18, 22, 3, 2)),
            new GenerationProfile(
                    ReviewedVersions.between("5.1.00", "5.1.01"),
                    profile("U", "b", "c", 11, "av", "a", 7, "Q", 19, 22, 3, 2)),
            new GenerationProfile(
                    ReviewedVersions.between("5.1.02", "5.1.03"),
                    profile("U", "b", "c", 11, "az", "a", 7, "Q", 19, 22, 3, 2)),
            new GenerationProfile(
                    ReviewedVersions.between("5.2.00", "5.2.02"),
                    profile("U", "b", "c", 11, "aL", "a", 7, "Q", 19, 22, 3, 2)),
            new GenerationProfile(
                    ReviewedVersions.between("5.2.03", "5.2.03"),
                    profile("U", "b", "c", 11, "aL", "a", 7, "R", 21, 23, 3, 2)),
            new GenerationProfile(
                    ReviewedVersions.between("5.3.00", "5.3.02"),
                    profile("U", "b", "c", 11, "aL", "a", 7, "T", 22, 22, 1, 1)),
            new GenerationProfile(
                    ReviewedVersions.between("5.3.03", "5.3.03"),
                    profile("U", "b", "c", 11, "aM", "a", 10, "T", 22, 22, 3, 1)),
            // Menu-building mouse handler moved from aM.a(MouseEvent) to aM.b(MouseEvent);
            // aM.a became a public delegate in 5.3.04.
            new GenerationProfile(
                    ReviewedVersions.since("5.3.04"), profile("U", "b", "c", 11, "aM", "b", 10, "T", 22, 22, 3, 1)));

    public ObjectContextMenuHostProfile {
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        if (bindings.size() != BINDING_COUNT) {
            throw new IllegalArgumentException("object context-menu profile requires four bindings");
        }
    }

    /**
     * Resolves the reviewed bindings for a host artifact.
     *
     * @param artifact the located host artifact digest
     * @return the matching profile, or empty for an unreviewed artifact
     */
    public static Optional<ObjectContextMenuHostProfile> forArtifact(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        return ReviewedHostArtifacts.cubismVersionOf(artifact)
                .flatMap(ObjectContextMenuHostProfile::forReviewedVersion);
    }

    /**
     * Resolves the reviewed bindings by declared reviewed generation, for a compatibility host that
     * bound its declared version.
     *
     * @param cubismVersion the host-declared reviewed version
     * @return the matching profile, or empty for any unreviewed version
     */
    public static Optional<ObjectContextMenuHostProfile> forReviewedVersion(final String cubismVersion) {
        Objects.requireNonNull(cubismVersion, "cubismVersion");
        for (final GenerationProfile entry : GENERATION_PROFILES) {
            if (entry.versions().contains(cubismVersion)) {
                return Optional.of(entry.profile());
            }
        }
        return Optional.empty();
    }

    /**
     * Builds the four reviewed bindings of one generation.
     *
     * @param workspaceOwner class letter of the workspace context owner
     * @param deformerAndPartsAppendMethod append method shared by the deformer and parts menus
     * @param parameterAppendMethod append method of the parameter menu
     * @param deformerAppends append points the deformer menu declares
     * @param parameterOwner class letter of the parameter palette owner
     * @param parameterMethod menu-opening method of the parameter palette owner
     * @param parameterAppends append points the parameter menu declares
     * @param partsOwner class letter of the parts palette owner
     * @param partsAppends append points the parts menu declares
     * @param workspaceAppends append points the workspace menu declares
     * @param deformerInjectionPoint append point the deformer item is injected at
     * @param partsInjectionPoint append point the parts item is injected at
     * @return the profile for that generation
     */
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
                        DEFORMER_OWNER,
                        DEFORMER_METHOD,
                        deformerAndPartsAppendMethod,
                        ContextMenuBindingSpec.Location.DEFORMER_TAB,
                        deformerAppends,
                        deformerInjectionPoint,
                        2,
                        3),
                append(
                        PARAMETER_OWNER_PREFIX + parameterOwner,
                        parameterMethod,
                        parameterAppendMethod,
                        ContextMenuBindingSpec.Location.PARAMETER_TAB,
                        parameterAppends,
                        3,
                        2),
                append(
                        PARTS_OWNER_PREFIX + partsOwner,
                        PARTS_METHOD,
                        deformerAndPartsAppendMethod,
                        ContextMenuBindingSpec.Location.PART_TAB,
                        partsAppends,
                        partsInjectionPoint,
                        5),
                new ContextMenuBindingSpec(
                        WORKSPACE_OWNER_PREFIX + workspaceOwner,
                        WORKSPACE_METHOD,
                        "(Lcom/live2d/cubism/view/context/actionManager/N;)V",
                        MENU,
                        WORKSPACE_APPEND_METHOD,
                        WORKSPACE_APPEND_DESCRIPTOR,
                        ContextMenuBindingSpec.Location.WORKSPACE_OBJECT,
                        ContextMenuBindingSpec.Shape.APPEND_POINT,
                        workspaceAppends,
                        1,
                        new int[] {1})));
    }

    private static ContextMenuBindingSpec append(
            final String operationOwner,
            final String operationMethod,
            final String appendMethod,
            final ContextMenuBindingSpec.Location location,
            final int expectedAppends,
            final int injectionPoint,
            final int... sourceLocals) {
        return ContextMenuBindingSpec.appendPoint(
                operationOwner,
                operationMethod,
                MOUSE_DESCRIPTOR,
                MENU,
                appendMethod,
                APPEND_DESCRIPTOR,
                location,
                expectedAppends,
                injectionPoint,
                sourceLocals);
    }
}
