package dev.turboism.adapter.cubism.mesh;

import java.util.ArrayList;
import java.util.List;

/** Geometric layout generator for circular and regular-polygon mesh point-and-edge structures. */
final class GeometricMeshShape {
    static final float MIN_RADIUS = 1.0f;
    static final float MAX_RADIUS = 4096.0f;
    static final float DEFAULT_RADIUS = 60.0f;
    static final int MIN_SIDES = 3;
    static final int MAX_SIDES = 128;
    static final int DEFAULT_SIDES = 16;
    static final int MIN_CONCENTRIC_RINGS = 0;
    static final int MAX_CONCENTRIC_RINGS = 16;
    static final int DEFAULT_CONCENTRIC_RINGS = 0;

    private GeometricMeshShape() {}

    /** Shape presets selectable from the configuration dropdown; selecting a preset updates the side count. */
    enum Preset {
        CIRCLE(16),
        TRIANGLE(3),
        QUADRILATERAL(4),
        HEXAGON(6),
        OCTAGON(8);

        private final int defaultSides;

        Preset(final int defaultSides) {
            this.defaultSides = defaultSides;
        }

        int defaultSides() {
            return defaultSides;
        }
    }

    /** Generated vertices (flat XY pairs) and undirected edge index pairs (flat [u0, v0, u1, v1, ...]). */
    record LayoutPlan(float[] verticesXY, int[] edgePairs) {
        int vertexCount() {
            return verticesXY.length / 2;
        }

        int edgeCount() {
            return edgePairs.length / 2;
        }
    }

    static int clampSides(final int sides) {
        return Math.max(MIN_SIDES, Math.min(MAX_SIDES, sides));
    }

    static float clampRadius(final float radius) {
        if (!Float.isFinite(radius)) return DEFAULT_RADIUS;
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
    }

    static int clampConcentricRings(final int rings) {
        return Math.max(MIN_CONCENTRIC_RINGS, Math.min(MAX_CONCENTRIC_RINGS, rings));
    }

    /**
     * Generates vertices and edges for the given geometric configuration around {@code (centerX, centerY)}.
     *
     * <p>{@code concentricRings} specifies the number of inner concentric loops ({@code 0} means only
     * the outer perimeter loop; {@code k > 0} generates {@code k} inner concentric loops plus the outer
     * loop, i.e. {@code M = k + 1} total loops equally spaced at radii {@code R * 1/M, R * 2/M, ..., R}).</p>
     */
    static LayoutPlan generate(
            final float centerX,
            final float centerY,
            final float radius,
            final int requestedSides,
            final int concentricRings,
            final boolean createCenter,
            final boolean connectToCenter) {
        if (!Float.isFinite(centerX) || !Float.isFinite(centerY)) {
            throw new IllegalArgumentException("center anchor coordinates must be finite");
        }
        final float clampedRadius = clampRadius(radius);
        final int sides = clampSides(requestedSides);
        final int innerRings = clampConcentricRings(concentricRings);
        final int totalRings = innerRings + 1;
        final boolean hasCenterVertex = createCenter;
        final boolean hasSpokes = createCenter && connectToCenter;

        final int ringVertexOffset = hasCenterVertex ? 1 : 0;
        final int totalVertices = ringVertexOffset + totalRings * sides;
        final float[] vertices = new float[totalVertices * 2];
        if (hasCenterVertex) {
            vertices[0] = centerX;
            vertices[1] = centerY;
        }

        final double step = (Math.PI * 2.0) / sides;
        final double startAngle =
                (sides == 4 || sides == 6 || sides == 8) ? (-Math.PI * 0.5 + Math.PI / sides) : (-Math.PI * 0.5);

        for (int ring = 0; ring < totalRings; ring++) {
            final float ringRadius = clampedRadius * (float) (ring + 1) / (float) totalRings;
            final int baseVertex = ringVertexOffset + ring * sides;
            for (int index = 0; index < sides; index++) {
                final double angle = startAngle + index * step;
                final int v = baseVertex + index;
                vertices[v * 2] = centerX + (float) (ringRadius * Math.cos(angle));
                vertices[v * 2 + 1] = centerY + (float) (ringRadius * Math.sin(angle));
            }
        }

        final List<Integer> edges = new ArrayList<>((totalRings * sides + (hasSpokes ? totalRings * sides : 0)) * 2);
        for (int ring = 0; ring < totalRings; ring++) {
            final int baseVertex = ringVertexOffset + ring * sides;
            for (int index = 0; index < sides; index++) {
                edges.add(baseVertex + index);
                edges.add(baseVertex + ((index + 1) % sides));
            }
        }
        if (hasSpokes) {
            for (int index = 0; index < sides; index++) {
                edges.add(0);
                edges.add(ringVertexOffset + index);
                for (int ring = 1; ring < totalRings; ring++) {
                    final int prevRingVertex = ringVertexOffset + (ring - 1) * sides + index;
                    final int currRingVertex = ringVertexOffset + ring * sides + index;
                    edges.add(prevRingVertex);
                    edges.add(currRingVertex);
                }
            }
        }

        final int[] edgeArray = new int[edges.size()];
        for (int i = 0; i < edges.size(); i++) {
            edgeArray[i] = edges.get(i);
        }
        return new LayoutPlan(vertices, edgeArray);
    }
}
