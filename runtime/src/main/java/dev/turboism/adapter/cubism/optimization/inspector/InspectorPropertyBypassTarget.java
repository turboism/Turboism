package dev.turboism.adapter.cubism.optimization.inspector;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;

/**
 * Reviewed host seams for the inspector visual-property bypass.
 *
 * <p>Cubism 5.3 inspector setters for alpha and colour composition unconditionally call
 * {@code CModelSource.updateModelInstances()}, even though those properties only change a
 * drawable's shader/material. The reviewed methods below are the exact setters whose bodies
 * contain that call. The bypass keeps the native source mutation and palette refresh, replaces
 * only the full model-instance rebuild with the same drawable's own {@code setupShader(null)},
 * and falls back to the untouched native call on any mismatch or failure.</p>
 */
public record InspectorPropertyBypassTarget(String version) {

    /** Owner of the full rebuild the setters call after changing a visual property. */
    public static final String SOURCE_OWNER = "com/live2d/cubism/doc/model/CModelSource";

    /** Full model-instance publication method. */
    public static final String UPDATE_METHOD = "updateModelInstances";

    /** Descriptor of {@link #UPDATE_METHOD}. */
    public static final String UPDATE_DESCRIPTOR = "()V";

    /** Descriptor shared by the three alpha-composition setter classes. */
    public static final String ALPHA_DESCRIPTOR = "(Lcom/live2d/cubism/doc/model/drawable/AlphaComposition;)V";

    /** Descriptor shared by the three colour-composition setters. */
    public static final String COLOR_DESCRIPTOR = "(Lcom/live2d/cubism/doc/model/drawable/ColorComposition;)V";

    /** Drawables whose own shader refresh replaces the full rebuild. */
    public static final List<String> DRAWABLE_OWNERS = List.of(
            "com/live2d/cubism/doc/model/drawable/artMesh/CArtMesh",
            "com/live2d/cubism/doc/model/parts/CPart",
            "com/live2d/cubism/doc/model/alias/CAlias");

    /** Inspector setter for an ArtMesh's alpha composition. */
    public static final String ART_MESH_ALPHA_OWNER =
            "com/live2d/cubism/view/palette/inspector/editPalette/ArtMesh_wrapperForInspector$alphaComposition$1";

    /** Inspector setter for a Part's alpha composition. */
    public static final String PARTS_ALPHA_OWNER =
            "com/live2d/cubism/view/palette/inspector/editPalette/Parts_wrapperForInspector$alphaComposition$1";

    /** Inspector setter for an Alias's alpha composition. */
    public static final String ALIAS_ALPHA_OWNER =
            "com/live2d/cubism/view/palette/inspector/editPalette/Alias_wrapperForInspector$DrawingSetting$alphaComposition$1";

    /** ArtMesh inspector wrapper's colour-composition setter. */
    public static final String ART_MESH_COLOR_OWNER =
            "com/live2d/cubism/view/palette/inspector/editPalette/ArtMesh_wrapperForInspector";

    /** Part inspector wrapper's colour-composition setter. */
    public static final String PARTS_COLOR_OWNER =
            "com/live2d/cubism/view/palette/inspector/editPalette/Parts_wrapperForInspector";

    /** Alias inspector drawing-setting colour-composition setter. */
    public static final String ALIAS_COLOR_OWNER =
            "com/live2d/cubism/view/palette/inspector/editPalette/Alias_wrapperForInspector$DrawingSetting";

    /** One reviewed setter method whose single full-rebuild call may be bypassed. */
    public record Seam(String owner, String method, String descriptor) {
        public Seam {
            owner = requireText(owner, "owner");
            method = requireText(method, "method");
            descriptor = requireText(descriptor, "descriptor");
        }

        /** Binary name used by {@link Class#forName(String, boolean, ClassLoader)}. */
        public String binaryName() {
            return owner.replace('/', '.');
        }
    }

    /** Every reviewed setter seam, in a stable order. */
    public static final List<Seam> SEAMS = List.of(
            new Seam(ART_MESH_ALPHA_OWNER, "setContent", ALPHA_DESCRIPTOR),
            new Seam(PARTS_ALPHA_OWNER, "setContent", ALPHA_DESCRIPTOR),
            new Seam(ALIAS_ALPHA_OWNER, "setContent", ALPHA_DESCRIPTOR),
            new Seam(ART_MESH_COLOR_OWNER, "setColorComposition", COLOR_DESCRIPTOR),
            new Seam(PARTS_COLOR_OWNER, "setColorComposition", COLOR_DESCRIPTOR),
            new Seam(ALIAS_COLOR_OWNER, "setColorComposition", COLOR_DESCRIPTOR));

    public InspectorPropertyBypassTarget {
        version = requireText(version, "version").strip();
        if (version.isEmpty()) {
            throw new IllegalArgumentException("version must not be blank");
        }
    }

    /**
     * Whether the official artifact generation exposes every reviewed seam. The hook is
     * deliberately absent on 5.0–5.2, whose inspector wrappers expose a different property
     * surface.
     *
     * @param version reviewed Cubism version from the exact artifact
     * @return true only for reviewed 5.3 generations
     */
    public static boolean supports(final String version) {
        return version != null
                && version.startsWith("5.3.")
                && ReviewedHostArtifacts.reviewedCubismVersions().contains(version);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
