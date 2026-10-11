package dev.turboism.mapping.verification.selector;

import java.util.Set;

/**
 * Exact additive selector contract for the Editor native offscreen model render
 * read used by SC-003 pixel observation.
 *
 * <p>Evidence (exact public class-file observation, Cubism 5.3.02 JAR
 * {@code 988ef6a8…}): {@code CModelSource.getRenderedImage(int,int,CParameterSet,
 * CColor,Function1)} delegates to {@code R.a}, which creates a temporary model
 * instance added to {@code modelInstances}, copies the supplied parameter set into
 * it, evaluates natively, draws into an offscreen GL surface, reads back a
 * {@code CWritableImage}, and disposes the temporary instance in a finally block.
 * {@code CWritableImage.getJBufferedImage()} exposes the owned raster;
 * {@code CWritableImage.flush()} releases it. The return may be {@code null} when
 * shader support is unavailable, so callers must treat a null result as an honest
 * failure — never substitute a cached or synthetic frame.</p>
 */
public final class EditorRenderPixelsSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.render.read";

    /**
     * Reviewed generations whose Editor-model verification record admits
     * {@link #CAPABILITY_ID}.
     *
     * <p>Every alias below verifies structurally on all twenty reviewed artifacts
     * with the same owners, members and descriptors, so the admitted range is
     * exactly the range the records list the capability for. The 5.0.x/5.1.x
     * records do not carry it — whole-record propagation onto those artifacts is
     * blocked by unrelated families that genuinely do not exist there — so the
     * native render stays refused on those hosts instead of asserting an
     * unrecorded admission.</p>
     */
    public static final Set<String> SUPPORTED_CUBISM_VERSIONS = Set.of(
        "5.2.00", "5.2.01", "5.2.02", "5.2.03",
        "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
    );

    /** Aliases newly authorized for the native render read (Cubism 5.3.02 evidence). */
    public static final Set<String> NEW_ALIASES = Set.of(
        "cubism.editor-model.model-source.get-rendered-image",
        "cubism.editor-model.writable-image.class",
        "cubism.editor-model.writable-image.j-buffered-image",
        "cubism.editor-model.writable-image.flush"
    );

    /**
     * Complete alias set the render read requires before any native call: the four
     * new selectors plus the already-reviewed reads for current instance, instance
     * list membership, parameter set, transparent background color and canvas size.
     */
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.model-source.get-rendered-image",
        "cubism.editor-model.writable-image.class",
        "cubism.editor-model.writable-image.j-buffered-image",
        "cubism.editor-model.writable-image.flush",
        "cubism.editor-model.model-source.current-instance",
        "cubism.editor-model.model-source.model-instances",
        "cubism.editor-model.model.parameter-set",
        "cubism.editor-model.parameter-set.class",
        "cubism.editor-model.color.create",
        "cubism.editor-model.color.class",
        "cubism.editor-model.model-instance.class"
    );

    private EditorRenderPixelsSelectorContract() {
    }
}
