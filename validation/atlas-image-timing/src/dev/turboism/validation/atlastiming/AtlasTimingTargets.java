package dev.turboism.validation.atlastiming;

import java.util.List;

/**
 * The exact 5.3.03 timing targets, read off the reviewed official JAR with {@code javap -p -s}.
 *
 * <p>Each entry times one distinct layer of the texture-atlas open/regenerate path:</p>
 * <ul>
 *   <li>{@code UPDATE_TEXTURE} — one call per atlas page generation, including the
 *       cache-manager handoff tail;</li>
 *   <li>{@code SETUP_CACHE_IMAGE} — the pure per-page image generation inside it;</li>
 *   <li>{@code DRAW_MODEL_IMAGE} — the per-ModelImage high-quality draw kernel, the
 *       per-image unit the user sees counted one by one;</li>
 *   <li>{@code EDITOR_BATCH} / {@code EDITOR_INIT} — the atlas-editor rebuild batch and the
 *       page-copy initialisation that owns the per-image edit-layer setup;</li>
 *   <li>{@code SETUP_EDIT_LAYER} — per-ModelImage edit-layer construction, which drives
 *       the editable-mesh triangulation;</li>
 *   <li>{@code UPDATE_MESH} — the triangulation pass itself.</li>
 * </ul>
 *
 * <p>Ordinals are the metric wire ids passed by the woven bytecode; append only, never reorder.</p>
 */
final class AtlasTimingTargets {
    static final String[] METRIC_NAMES = {
        "updateTexture",
        "setupCacheImage",
        "drawModelImage",
        "editorBatch",
        "editorInit",
        "setupEditLayer",
        "updateMesh",
        "getFilteredImage",
        "pageFill",
        "pagePostPass",
        "cacheRegister",
    };

    static final int UPDATE_TEXTURE = 0;
    static final int SETUP_CACHE_IMAGE = 1;
    static final int DRAW_MODEL_IMAGE = 2;
    static final int EDITOR_BATCH = 3;
    static final int EDITOR_INIT = 4;
    static final int SETUP_EDIT_LAYER = 5;
    static final int UPDATE_MESH = 6;
    /** Per-image lazy filtered-image build inside the page composite loop. */
    static final int GET_FILTERED_IMAGE = 7;
    /** Whole-page {@code CWritableImage.fill} inside setupCacheImage. */
    static final int PAGE_FILL = 8;
    /** Whole-page {@code D.a(BufferedImage, int)} post-pass after the draw loop. */
    static final int PAGE_POST_PASS = 9;
    /** {@code CCachedImageManager.a(CImageResource)} registration in updateTexture's tail. */
    static final int CACHE_REGISTER = 10;

    private AtlasTimingTargets() {
    }

    record Target(String ownerInternalName, String methodName, String descriptor, int metricId) {
    }

    static List<Target> cubism5303() {
        return List.of(
            new Target(
                "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas",
                "updateTexture",
                "(ZZLcom/live2d/util/a/a;)V",
                UPDATE_TEXTURE),
            new Target(
                "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas",
                "setupCacheImage$cubism",
                "(ZLcom/live2d/util/a/a;)V",
                SETUP_CACHE_IMAGE),
            new Target(
                "com/live2d/util/f/g",
                "a",
                "(Ljava/awt/image/BufferedImage;Ljava/awt/Graphics;"
                    + "Ljava/awt/image/BufferedImage;IIDZ)V",
                DRAW_MODEL_IMAGE),
            new Target(
                "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel",
                "v",
                "()V",
                EDITOR_BATCH),
            new Target(
                "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel",
                "z",
                "()V",
                EDITOR_INIT),
            new Target(
                "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE__EditLayer_ModelImage",
                "setupEditLayer",
                "()V",
                SETUP_EDIT_LAYER),
            new Target(
                "com/live2d/graphics3d/editableMesh/GEditableMesh2",
                "updateMesh",
                "(Lcom/live2d/util/j/a;Z)V",
                UPDATE_MESH),
            new Target(
                "com/live2d/cubism/doc/model/texture/modelImage/CModelImage",
                "getFilteredImage",
                "()Lcom/live2d/graphics/CImageResource;",
                GET_FILTERED_IMAGE),
            new Target(
                "com/live2d/graphics/CWritableImage",
                "fill",
                "(I)V",
                PAGE_FILL),
            new Target(
                "com/live2d/util/D",
                "a",
                "(Ljava/awt/image/BufferedImage;I)V",
                PAGE_POST_PASS),
            new Target(
                "com/live2d/graphics/cachedImage/CCachedImageManager",
                "a",
                "(Lcom/live2d/graphics/CImageResource;)V",
                CACHE_REGISTER)
        );
    }

    /**
     * The exact 5.2.03 timing targets, read off the reviewed official JAR with
     * {@code javap -p -s}. The atlas-editor and texture-atlas classes are byte-identical
     * to 5303; the two obfuscation-bucket differences are the workaround owner
     * ({@code util/e/g} instead of {@code util/f/g}) and the updateMesh argument type
     * ({@code util/i/a} instead of {@code util/j/a}).
     */
    static List<Target> cubism5203() {
        return List.of(
            new Target(
                "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas",
                "updateTexture",
                "(ZZLcom/live2d/util/a/a;)V",
                UPDATE_TEXTURE),
            new Target(
                "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas",
                "setupCacheImage$cubism",
                "(ZLcom/live2d/util/a/a;)V",
                SETUP_CACHE_IMAGE),
            new Target(
                "com/live2d/util/e/g",
                "a",
                "(Ljava/awt/image/BufferedImage;Ljava/awt/Graphics;"
                    + "Ljava/awt/image/BufferedImage;IIDZ)V",
                DRAW_MODEL_IMAGE),
            new Target(
                "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel",
                "v",
                "()V",
                EDITOR_BATCH),
            new Target(
                "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel",
                "z",
                "()V",
                EDITOR_INIT),
            new Target(
                "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE__EditLayer_ModelImage",
                "setupEditLayer",
                "()V",
                SETUP_EDIT_LAYER),
            new Target(
                "com/live2d/graphics3d/editableMesh/GEditableMesh2",
                "updateMesh",
                "(Lcom/live2d/util/i/a;Z)V",
                UPDATE_MESH),
            new Target(
                "com/live2d/cubism/doc/model/texture/modelImage/CModelImage",
                "getFilteredImage",
                "()Lcom/live2d/graphics/CImageResource;",
                GET_FILTERED_IMAGE),
            new Target(
                "com/live2d/graphics/CWritableImage",
                "fill",
                "(I)V",
                PAGE_FILL),
            new Target(
                "com/live2d/util/D",
                "a",
                "(Ljava/awt/image/BufferedImage;I)V",
                PAGE_POST_PASS),
            new Target(
                "com/live2d/graphics/cachedImage/CCachedImageManager",
                "a",
                "(Lcom/live2d/graphics/CImageResource;)V",
                CACHE_REGISTER)
        );
    }

    /**
     * Union of every reviewed target list. Each host loads only its own owner classes,
     * so installing the union is self-selecting; the dedup keeps the shared
     * {@code CTextureAtlas} entries from being reported twice.
     */
    static List<Target> reviewed() {
        final List<Target> all = new java.util.ArrayList<>(cubism5303());
        for (final Target target : cubism5203()) {
            if (!all.contains(target)) all.add(target);
        }
        return List.copyOf(all);
    }
}
