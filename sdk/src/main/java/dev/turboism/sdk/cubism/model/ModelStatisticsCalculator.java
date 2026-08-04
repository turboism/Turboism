package dev.turboism.sdk.cubism.model;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

final class ModelStatisticsCalculator {

    private ModelStatisticsCalculator() {
    }

    static ModelStatistics calculate(final CubismModel model) {
        final List<Drawable> drawables = model.drawables().all();
        int vertices = 0;
        int triangles = 0;
        int masked = 0;
        int maxTexture = -1;
        final Set<List<Integer>> maskGroups = new HashSet<>();
        for (Drawable drawable : drawables) {
            vertices = Math.addExact(vertices, drawable.vertexPositions().size() / 2);
            triangles = Math.addExact(triangles, drawable.indices().size() / 3);
            maxTexture = Math.max(maxTexture, drawable.textureIndex());
            final IntSequence masks = drawable.masks();
            if (!masks.isEmpty()) {
                masked++;
                final java.util.ArrayList<Integer> group = new java.util.ArrayList<>(masks.size());
                for (int index = 0; index < masks.size(); index++) group.add(masks.get(index));
                maskGroups.add(List.copyOf(group));
            }
        }
        return new ModelStatistics(
            model.parameters().all().size(),
            model.parts().all().size(),
            drawables.size(),
            drawables.size(),
            model.deformers().all().size(),
            vertices,
            triangles,
            maxTexture + 1,
            masked,
            maskGroups.size(),
            OptionalInt.empty(),
            OptionalInt.empty()
        );
    }
}
