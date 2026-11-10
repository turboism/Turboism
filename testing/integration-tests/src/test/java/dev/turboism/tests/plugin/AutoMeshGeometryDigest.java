package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Stable content digest and structural faults of one ArtMesh geometry, so two runs compare without
 * holding both snapshots.
 *
 * <p>The digest covers positions, uvs and triangle indices in host order. A geometry that differs in
 * one coordinate, one uv or one triangle therefore never shares a digest with another, which is what
 * makes the native-baseline and Undo/Redo comparisons meaningful.</p>
 *
 * <p>Floats render through {@link Float#toString(float)} because it round-trips exactly. A decimal
 * formatter would collapse two distinct host values into one digest.</p>
 */
final class AutoMeshGeometryDigest {

    /** Field separator; a rendered float never contains a control character. */
    private static final char FIELD_SEPARATOR = '\u001f';

    /** Separator between one vertex or triangle and the next. */
    private static final char RECORD_SEPARATOR = '\u001e';

    private static final String ALGORITHM = "SHA-256";
    private static final int RADIX = 16;
    private static final int NIBBLE_BITS = 4;
    private static final int BYTE_MASK = 0xFF;

    private AutoMeshGeometryDigest() {}

    /**
     * Returns the digest of one geometry.
     *
     * @param geometry the geometry to digest; never {@code null}
     * @return lowercase hexadecimal SHA-256 of the canonical rendering
     */
    static String of(final ArtMeshGeometry geometry) {
        return of(geometry.positions(), geometry.uvs(), geometry.triangleIndices());
    }

    /**
     * Returns the digest of one geometry's three parallel arrays.
     *
     * @param positions vertex positions in host order
     * @param uvs texture coordinates in host order
     * @param triangleIndices triangle indices in host order
     * @return lowercase hexadecimal SHA-256 of the canonical rendering
     */
    static String of(final List<Point2> positions, final List<Point2> uvs, final List<Integer> triangleIndices) {
        final StringBuilder text = new StringBuilder();
        text.append(positions.size()).append(FIELD_SEPARATOR).append(triangleIndices.size());
        for (final Point2 point : positions) {
            appendPoint(text, point);
        }
        for (final Point2 point : uvs) {
            appendPoint(text, point);
        }
        for (final int index : triangleIndices) {
            text.append(RECORD_SEPARATOR).append(index);
        }
        return hex(sha256().digest(text.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Counts degenerate triangles: a triangle with repeated indices, or with an absolute area at or
     * below the tolerance.
     *
     * <p>A degenerate triangle renders nothing and breaks the native triangulation contract, so the
     * probe reports it as a geometry fault instead of a mesh.</p>
     *
     * @param geometry the geometry to inspect
     * @param areaTolerance smallest accepted absolute triangle area
     * @return the number of degenerate triangles
     */
    static int degenerateTriangles(final ArtMeshGeometry geometry, final double areaTolerance) {
        final List<Point2> positions = geometry.positions();
        final List<Integer> indices = geometry.triangleIndices();
        final int verticesPerTriangle = 3;
        int degenerate = 0;
        for (int offset = 0; offset + verticesPerTriangle - 1 < indices.size(); offset += verticesPerTriangle) {
            final int a = indices.get(offset);
            final int b = indices.get(offset + 1);
            final int c = indices.get(offset + 2);
            if (a == b || b == c || a == c) {
                degenerate++;
                continue;
            }
            if (!(triangleArea(positions.get(a), positions.get(b), positions.get(c)) > areaTolerance)) {
                degenerate++;
            }
        }
        return degenerate;
    }

    /**
     * Counts vertices no triangle references.
     *
     * <p>An unreferenced vertex is invisible in the Editor but still occupies the mesh, so it is a
     * geometry fault rather than a cosmetic detail.</p>
     *
     * @param geometry the geometry to inspect
     * @return the number of vertices no triangle index names
     */
    static int unreferencedVertices(final ArtMeshGeometry geometry) {
        final boolean[] referenced = new boolean[geometry.positions().size()];
        for (final int index : geometry.triangleIndices()) {
            referenced[index] = true;
        }
        int unreferenced = 0;
        for (final boolean used : referenced) {
            if (!used) {
                unreferenced++;
            }
        }
        return unreferenced;
    }

    private static void appendPoint(final StringBuilder text, final Point2 point) {
        text.append(RECORD_SEPARATOR).append(point.x()).append(FIELD_SEPARATOR).append(point.y());
    }

    private static double triangleArea(final Point2 a, final Point2 b, final Point2 c) {
        final double twiceArea =
                (b.x() - a.x()) * (double) (c.y() - a.y()) - (c.x() - a.x()) * (double) (b.y() - a.y());
        return Math.abs(twiceArea) / 2.0;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("the JDK must provide " + ALGORITHM, absent);
        }
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder text = new StringBuilder(bytes.length * 2);
        for (final byte value : bytes) {
            final int unsigned = value & BYTE_MASK;
            text.append(Character.forDigit(unsigned >>> NIBBLE_BITS, RADIX))
                    .append(Character.forDigit(unsigned & (RADIX - 1), RADIX));
        }
        return text.toString();
    }
}
