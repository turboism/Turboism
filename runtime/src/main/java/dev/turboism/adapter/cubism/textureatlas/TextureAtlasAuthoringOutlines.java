package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutline;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Map;

/** Material-local union of all source meshes that can sample a model image. */
final class TextureAtlasAuthoringOutlines {
    private TextureAtlasAuthoringOutlines() {}

    /** Missing selectors, unsupported drawable kinds and degenerate geometry use full image bounds. */
    static TextureAtlasOutline read(VerifiedMemberResolver resolver, Object manager, Object image) {
        try {
            Object handler = resolver.invoke("cubism.editor-model.texture-manager.handler", manager);
            Object uses = resolver.invoke("cubism.texture-atlas.texture-manager-handler.drawable-uses", handler);
            Object guid = resolver.invoke("cubism.texture-atlas.image.guid", image);
            if (!(uses instanceof Map<?, ?> map) || !(map.get(guid) instanceof Iterable<?> sources)) return null;
            Object raw = resolver.invoke("cubism.texture-atlas.image.local-to-canvas", image);
            if (!(raw instanceof AffineTransform transform)) return null;
            AffineTransform inverse = transform.createInverse();
            var shapes = new ArrayList<Shape>();
            for (Object source : sources) {
                if (!resolver.isInstance("cubism.editor-model.art-mesh-source.class", source)) return null;
                Object positions = resolver.invoke("cubism.editor-model.art-mesh-source.positions", source);
                Object indices = resolver.invoke("cubism.editor-model.art-mesh-source.indices", source);
                if (!(positions instanceof float[] xy)
                        || !(indices instanceof int[] triangles)
                        || xy.length % 2 != 0
                        || triangles.length % 3 != 0) return null;
                for (int i = 0; i < triangles.length; i += 3) {
                    Path2D.Double triangle = new Path2D.Double();
                    for (int j = 0; j < 3; j++) {
                        int at = Math.multiplyExact(triangles[i + j], 2);
                        if (at < 0 || at + 1 >= xy.length || !Float.isFinite(xy[at]) || !Float.isFinite(xy[at + 1]))
                            return null;
                        if (j == 0) triangle.moveTo(xy[at], xy[at + 1]);
                        else triangle.lineTo(xy[at], xy[at + 1]);
                    }
                    triangle.closePath();
                    shapes.add(inverse.createTransformedShape(triangle));
                }
            }
            var extraction = TextureAtlasOutlineExtractor.extract(shapes);
            return extraction == null ? null : extraction.outline();
        } catch (RuntimeException | NoninvertibleTransformException unavailable) {
            return null;
        }
    }
}
