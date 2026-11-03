package dev.turboism.adapter.cubism.performance;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The reviewed clipping-mask and transparent-group selectors the measurement-only
 * probe observes on each admitted Cubism build.
 *
 * <p>Every owner, name and descriptor here was read off the reviewed JARs with
 * {@code javap -c -p}. The mask pass itself lives in
 * {@code com/live2d/graphics3d/rendering/e} on 5.3.x and in
 * {@code .../rendering/d} on 5.0.x-5.2.x (the same private
 * {@code a(AGRenderer, a, CRect)} body, byte-identical across the four 5.0-5.2
 * artifacts and across the three 5.3 artifacts apart from the pool call and the
 * defaulted-argument layout), so {@link #renderSystemOwner(String)} selects the
 * family owner. All six selectors exist on every reviewed version; none is
 * version-gated.</p>
 *
 * <p>The probe is measurement-only: the woven bytes consult JDK functional
 * interfaces and never change control flow, so an absent, mistyped or failing
 * callback simply measures nothing.</p>
 */
public final class ClipMaskProbeTargets {

    /** Frame boundary: {@code CEViewContext.renderScene_exe(a, CRect, CRect)V}. */
    public static final String FRAME_OWNER = "com/live2d/cubism/view/context/CEViewContext";

    public static final String FRAME_METHOD = "renderScene_exe";

    public static final String FRAME_DESCRIPTOR =
            "(Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;Lcom/live2d/type/CRect;)V";

    /** Trimmed mask-set accessor read by the render system once per real mask pass. */
    public static final String MASK_SET_OWNER = "com/live2d/graphics3d/component/GClipping";

    /** {@code GRemovableClipping} overrides the accessor, so both declarations are woven. */
    public static final String REMOVABLE_MASK_SET_OWNER = "com/live2d/graphics3d/component/GRemovableClipping";

    public static final String MASK_SET_METHOD = "getClippingVaildMasks";

    public static final String MASK_SET_DESCRIPTOR = "()Ljava/util/List;";

    /** Render-texture bind observed while a mask or group pass is in flight. */
    public static final String BIND_OWNER = "com/live2d/graphics3d/texture/GRenderTexture";

    public static final String BIND_METHOD = "bind";

    public static final String BIND_DESCRIPTOR = "(Lcom/live2d/graphics3d/a;)V";

    /** Transparent-group renderer: one offscreen ARGB FBO per group per frame. */
    public static final String GROUP_OWNER = "com/live2d/graphics3d/component/GGroupRenderer";

    public static final String GROUP_METHOD = "render";

    public static final String GROUP_DESCRIPTOR = "(Lcom/live2d/graphics3d/a;)V";

    /** Render system on Cubism 5.0.x-5.2.x. */
    public static final String LEGACY_RENDER_SYSTEM_OWNER = "com/live2d/graphics3d/rendering/d";

    /** Render system on Cubism 5.3.x. */
    public static final String CURRENT_RENDER_SYSTEM_OWNER = "com/live2d/graphics3d/rendering/e";

    public static final String MASK_DISPATCH_METHOD = "a";

    public static final String MASK_DISPATCH_DESCRIPTOR =
            "(Lcom/live2d/graphics3d/component/AGRenderer;Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;)V";

    private ClipMaskProbeTargets() {}

    /**
     * @param version exact reviewed Cubism version
     * @return the render system owner carrying the mask pass on that version
     */
    public static String renderSystemOwner(final String version) {
        Objects.requireNonNull(version, "version");
        return version.startsWith("5.3.") ? CURRENT_RENDER_SYSTEM_OWNER : LEGACY_RENDER_SYSTEM_OWNER;
    }

    /**
     * @param version exact reviewed Cubism version
     * @return the seven selectors the probe weaves on that version
     */
    public static List<Slot> forVersion(final String version) {
        return List.of(
                new Slot(FRAME_OWNER, FRAME_METHOD, FRAME_DESCRIPTOR, Kind.FRAME),
                new Slot(
                        renderSystemOwner(version), MASK_DISPATCH_METHOD, MASK_DISPATCH_DESCRIPTOR, Kind.MASK_DISPATCH),
                new Slot(MASK_SET_OWNER, MASK_SET_METHOD, MASK_SET_DESCRIPTOR, Kind.MASK_SET),
                new Slot(REMOVABLE_MASK_SET_OWNER, MASK_SET_METHOD, MASK_SET_DESCRIPTOR, Kind.MASK_SET),
                new Slot(BIND_OWNER, BIND_METHOD, BIND_DESCRIPTOR, Kind.RENDER_TARGET_BIND),
                new Slot(GROUP_OWNER, GROUP_METHOD, GROUP_DESCRIPTOR, Kind.GROUP_RENDER));
    }

    /**
     * @param digest admitted host artifact
     * @return the selectors for that artifact's reviewed version, or empty when unreviewed
     */
    public static Optional<List<Slot>> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return ReviewedHostArtifacts.cubismVersionOf(digest).map(ClipMaskProbeTargets::forVersion);
    }

    /**
     * @return the selectors of every reviewed version, oldest first
     */
    public static List<Slot> all() {
        return ReviewedHostArtifacts.all().stream()
                .flatMap(artifact -> of(artifact).stream())
                .flatMap(List::stream)
                .toList();
    }

    /** One woven method entry, identified exactly. */
    public enum Kind {
        /** Render-scene entry; the per-frame counter reset point. */
        FRAME,
        /** Mask pass dispatch; timed only when the pass reached the mask work. */
        MASK_DISPATCH,
        /** Trimmed mask-set accessor; exactly one call per real mask pass. */
        MASK_SET,
        /** Render-texture bind; classified by the pass that is currently in flight. */
        RENDER_TARGET_BIND,
        /** Transparent-group render entry. */
        GROUP_RENDER
    }

    /**
     * One reviewed method entry the probe observes.
     *
     * @param owner      JVM internal name of the declaring class
     * @param method     exact method name
     * @param descriptor JVM descriptor
     * @param kind       what the woven consult reports
     */
    public record Slot(String owner, String method, String descriptor, Kind kind) {
        public Slot {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(kind, "kind");
        }
    }
}
