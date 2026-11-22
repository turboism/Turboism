package dev.turboism.adapter.cubism.meshedit;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Eliminates {@code O(V * S)} linear soft-selection weight searches in {@code DrawEntity_ArtMesh_Selection}.
 */
public final class EditableMeshSelectionOptimizer {

    private static final int CHANNELS_RGBA = 4;

    private static final ClassValue<SelectorAccess> SELECTOR_ACCESS = new ClassValue<>() {
        @Override
        protected SelectorAccess computeValue(final Class<?> type) {
            return SelectorAccess.resolve(type);
        }
    };

    private EditableMeshSelectionOptimizer() {}

    /**
     * Populates a vertex color array in {@code O(V + S)} time preserving {@code PointSelector.firstOrNull}
     * match precedence and premultiplied RGB output.
     *
     * @param artMeshSelection {@code ArtMeshSelection} host instance
     * @param vertexCount total ArtMesh vertices
     * @param alpha base selection alpha
     * @param red base selection red component
     * @param green base selection green component
     * @param blue base selection blue component
     * @param outColors destination RGBA array of length {@code vertexCount * 4}
     * @return {@code true} if populated successfully; {@code false} to run native fallback
     */
    public static boolean fillSoftSelectionColors(
            final Object artMeshSelection,
            final int vertexCount,
            final float alpha,
            final float red,
            final float green,
            final float blue,
            final float[] outColors) {
        if (artMeshSelection == null
                || outColors == null
                || vertexCount < 0
                || outColors.length < vertexCount * CHANNELS_RGBA) {
            return false;
        }
        if (vertexCount == 0) {
            return true;
        }
        try {
            final SelectorAccess sa = SELECTOR_ACCESS.get(artMeshSelection.getClass());
            final Object selector = sa.getPointSelector.invoke(artMeshSelection);
            if (selector == null) {
                return false;
            }
            @SuppressWarnings("unchecked")
            final Iterable<Object> selected = (Iterable<Object>) selector;
            final float[] vertexWeights = new float[vertexCount];
            final boolean[] seen = new boolean[vertexCount];
            for (final Object ref : selected) {
                if (!sa.meshPointRefClass.isInstance(ref)) {
                    continue;
                }
                final int vIdx = (int) sa.meshPointRefIndex.invoke(ref);
                if (vIdx >= 0 && vIdx < vertexCount && !seen[vIdx]) {
                    seen[vIdx] = true;
                    vertexWeights[vIdx] = (float) sa.getWeight.invoke(selector, ref, 0.0f);
                }
            }

            for (int v = 0; v < vertexCount; v++) {
                final float effectiveAlpha = vertexWeights[v] * alpha;
                final int base = v * CHANNELS_RGBA;
                outColors[base] = red * effectiveAlpha;
                outColors[base + 1] = green * effectiveAlpha;
                outColors[base + 2] = blue * effectiveAlpha;
                outColors[base + 3] = effectiveAlpha;
            }
            return true;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    private static final class SelectorAccess {

        final MethodHandle getPointSelector;
        final MethodHandle getWeight;
        final Class<?> meshPointRefClass;
        final MethodHandle meshPointRefIndex;

        private SelectorAccess(
                final MethodHandle getPointSelector,
                final MethodHandle getWeight,
                final Class<?> meshPointRefClass,
                final MethodHandle meshPointRefIndex) {
            this.getPointSelector = getPointSelector;
            this.getWeight = getWeight;
            this.meshPointRefClass = meshPointRefClass;
            this.meshPointRefIndex = meshPointRefIndex;
        }

        static SelectorAccess resolve(final Class<?> artMeshSelectionClass) {
            try {
                final ClassLoader loader = artMeshSelectionClass.getClassLoader();
                final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                final Class<?> selectorClass = Class.forName("com.live2d.doc.selection.PointSelector", false, loader);
                final Class<?> iPointRef = Class.forName("com.live2d.doc.selection.IPointRef", false, loader);
                final Class<?> meshPointRef = Class.forName(
                        "com.live2d.cubism.doc.model.drawable.artMesh.MeshPointRef", false, loader);
                return new SelectorAccess(
                        lookup.findVirtual(artMeshSelectionClass, "getPointSelector", MethodType.methodType(selectorClass)),
                        lookup.findVirtual(
                                selectorClass,
                                "getWeight",
                                MethodType.methodType(float.class, iPointRef, float.class)),
                        meshPointRef,
                        lookup.findVirtual(meshPointRef, "a", MethodType.methodType(int.class)));
            } catch (final ReflectiveOperationException failure) {
                throw new IllegalStateException(
                        "Failed to bind soft-selection accessors for " + artMeshSelectionClass, failure);
            }
        }
    }
}
