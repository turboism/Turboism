package dev.turboism.plugin.turbomesh.domain;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;

/**
 * Maps a UV sample to the ArtMesh's local position space. Primary lookup is barycentric
 * interpolation inside the existing mesh triangle containing the UV point — that preserves the
 * mesh's exact UV→position semantics. Points outside every triangle (or a degenerate source
 * mesh) fall back to a least-squares affine fit over all existing vertices, matching the
 * previous engine's approach.
 */
public final class UvToPosition {

    private final List<Point2> uvs;
    private final List<Point2> positions;
    private final List<Integer> indices;
    private final UvAffine affine;

    private UvToPosition(
            final List<Point2> uvs, final List<Point2> positions, final List<Integer> indices, final UvAffine affine) {
        this.uvs = uvs;
        this.positions = positions;
        this.indices = indices;
        this.affine = affine;
    }

    /**
     * Builds a UV→position mapper: least-squares affine fit, then per-triangle barycentric
     * correction for UVs that fall outside the affine's reliable region.
     */
    public static UvToPosition of(final List<Point2> uvs, final List<Point2> positions, final List<Integer> indices) {
        return new UvToPosition(uvs, positions, indices, UvAffine.fit(uvs, positions));
    }

    /** Maps {@code (u, v)} into position space. */
    public Point2 map(final double u, final double v) {
        for (int t = 0; t + 2 < indices.size(); t += 3) {
            final Point2 a = uvs.get(indices.get(t));
            final Point2 b = uvs.get(indices.get(t + 1));
            final Point2 c = uvs.get(indices.get(t + 2));
            if (u < Math.min(a.x(), Math.min(b.x(), c.x())) - 1.0e-6
                    || u > Math.max(a.x(), Math.max(b.x(), c.x())) + 1.0e-6
                    || v < Math.min(a.y(), Math.min(b.y(), c.y())) - 1.0e-6
                    || v > Math.max(a.y(), Math.max(b.y(), c.y())) + 1.0e-6) {
                continue;
            }
            final double det = (b.y() - c.y()) * (a.x() - c.x()) + (c.x() - b.x()) * (a.y() - c.y());
            if (Math.abs(det) < 1.0e-12) {
                continue;
            }
            final double w0 = ((b.y() - c.y()) * (u - c.x()) + (c.x() - b.x()) * (v - c.y())) / det;
            final double w1 = ((c.y() - a.y()) * (u - c.x()) + (a.x() - c.x()) * (v - c.y())) / det;
            final double w2 = 1.0 - w0 - w1;
            final double eps = -1.0e-4;
            if (w0 >= eps && w1 >= eps && w2 >= eps) {
                final Point2 pa = positions.get(indices.get(t));
                final Point2 pb = positions.get(indices.get(t + 1));
                final Point2 pc = positions.get(indices.get(t + 2));
                return new Point2((float) (w0 * pa.x() + w1 * pb.x() + w2 * pc.x()), (float)
                        (w0 * pa.y() + w1 * pb.y() + w2 * pc.y()));
            }
        }
        return affine.map(u, v);
    }

    /** Least-squares affine {@code (u, v) -> (x, y)} fit over the given correspondences. */
    public static final class UvAffine {
        private final double a, b, tx, c, d, ty;

        private UvAffine(
                final double a, final double b, final double tx, final double c, final double d, final double ty) {
            this.a = a;
            this.b = b;
            this.tx = tx;
            this.c = c;
            this.d = d;
            this.ty = ty;
        }

        /** Fits {@code pos ≈ [a b tx; c d ty] * [u v 1]} by normal equations. */
        static UvAffine fit(final List<Point2> uv, final List<Point2> pos) {
            if (uv.size() < 3 || uv.size() != pos.size()) {
                return boundsFallback(uv, pos);
            }
            double su = 0, sv = 0, suu = 0, suv = 0, svv = 0;
            double sx = 0, sy = 0, sux = 0, svx = 0, suy = 0, svy = 0;
            for (int i = 0; i < uv.size(); i++) {
                final double u = uv.get(i).x();
                final double v = uv.get(i).y();
                final double x = pos.get(i).x();
                final double y = pos.get(i).y();
                su += u;
                sv += v;
                suu += u * u;
                suv += u * v;
                svv += v * v;
                sx += x;
                sy += y;
                sux += u * x;
                svx += v * x;
                suy += u * y;
                svy += v * y;
            }
            final double n = uv.size();
            final double[][] m = {
                {suu, suv, su, sux},
                {suv, svv, sv, svx},
                {su, sv, n, sx}
            };
            final double[] xRow = solve3x3(m);
            final double[][] m2 = {
                {suu, suv, su, suy},
                {suv, svv, sv, svy},
                {su, sv, n, sy}
            };
            final double[] yRow = solve3x3(m2);
            if (xRow == null || yRow == null) {
                return boundsFallback(uv, pos);
            }
            return new UvAffine(xRow[0], xRow[1], xRow[2], yRow[0], yRow[1], yRow[2]);
        }

        /** Scales the UV bounding box onto the position bounding box when a fit is impossible. */
        private static UvAffine boundsFallback(final List<Point2> uv, final List<Point2> pos) {
            if (uv.isEmpty()) {
                return new UvAffine(1, 0, 0, 0, 1, 0);
            }
            double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE;
            double minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (int i = 0; i < uv.size(); i++) {
                minU = Math.min(minU, uv.get(i).x());
                maxU = Math.max(maxU, uv.get(i).x());
                minV = Math.min(minV, uv.get(i).y());
                maxV = Math.max(maxV, uv.get(i).y());
                minX = Math.min(minX, pos.get(i).x());
                maxX = Math.max(maxX, pos.get(i).x());
                minY = Math.min(minY, pos.get(i).y());
                maxY = Math.max(maxY, pos.get(i).y());
            }
            final double su = maxU - minU;
            final double sv = maxV - minV;
            final double a = su > 1.0e-9 ? (maxX - minX) / su : 0;
            final double d = sv > 1.0e-9 ? (maxY - minY) / sv : 0;
            return new UvAffine(a, 0, minX - a * minU, 0, d, minY - d * minV);
        }

        /** Applies the fitted transform to {@code (u, v)}. */
        public Point2 map(final double u, final double v) {
            return new Point2((float) (a * u + b * v + tx), (float) (c * u + d * v + ty));
        }

        private static double[] solve3x3(final double[][] m) {
            for (int col = 0; col < 3; col++) {
                int pivot = col;
                for (int r = col + 1; r < 3; r++) {
                    if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) {
                        pivot = r;
                    }
                }
                if (Math.abs(m[pivot][col]) < 1.0e-14) {
                    return null;
                }
                final double[] tmp = m[pivot];
                m[pivot] = m[col];
                m[col] = tmp;
                for (int r = col + 1; r < 3; r++) {
                    final double f = m[r][col] / m[col][col];
                    for (int k = col; k < 4; k++) {
                        m[r][k] -= f * m[col][k];
                    }
                }
            }
            final double[] out = new double[3];
            for (int r = 2; r >= 0; r--) {
                double acc = m[r][3];
                for (int k = r + 1; k < 3; k++) {
                    acc -= m[r][k] * out[k];
                }
                out[r] = acc / m[r][r];
            }
            return out;
        }
    }
}
