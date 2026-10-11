package dev.turboism.adapter.cubism.interpolation;

/** Parameter-only coefficients for the SHA-pinned 5.3.02 ellipse calculator.
 * Keeps its two double sums, intermediate float rounding and final float blend.
 * Reassociation into one weighted sum would change the official result bits. */
public final class EllipseWeights {
    private final int forward, backward;
    private final double[] forwardCoefficients, backwardCoefficients;
    private final float forwardWeight, backwardWeight;

    private EllipseWeights(int forward, int backward, double[] a, double[] b, float t) {
        this.forward = forward;
        this.backward = backward;
        forwardCoefficients = a;
        backwardCoefficients = b;
        forwardWeight = forward >= 0 ? t : 0f;
        backwardWeight = backward >= 0 ? 1f - t : 0f;
    }

    /**
     * Precomputes the two interval coefficient triples for one knot position.
     *
     * @param knots official knot vector
     * @param interval current interval index
     * @param t in-interval fraction
     * @param radians ellipse angular factor
     * @return the prepared weights
     */
    public static EllipseWeights prepare(float[] knots, int interval, float t, double radians) {
        int forward = interval >= 0 && interval < knots.length - 2 ? interval : -1;
        int backward = interval > 0 && interval < knots.length - 1 ? interval - 1 : -1;
        double[] a = forward < 0
                ? null
                : coefficients(knots, forward, knots[interval] + (knots[interval + 1] - knots[interval]) * t, radians);
        double[] b = backward < 0
                ? null
                : coefficients(knots, backward, knots[interval] + (knots[interval + 1] - knots[interval]) * t, radians);
        return new EllipseWeights(forward, backward, a, b, t);
    }

    private static double[] coefficients(float[] k, int i, float p, double radians) {
        float a = k[i], b = k[i + 1], c = k[i + 2];
        double denominator = Math.sin((a - b) * radians) + Math.sin((b - c) * radians) + Math.sin((c - a) * radians);
        double first =
                (Math.sin((b - c) * radians) + Math.sin((p - b) * radians) + Math.sin((c - p) * radians)) / denominator;
        double third =
                (Math.sin((a - b) * radians) + Math.sin((p - a) * radians) + Math.sin((b - p) * radians)) / denominator;
        return new double[] {first, 1d - first - third, third};
    }

    private static float sum(float[][] source, int offset, int first, double[] weights) {
        return first < 0
                ? 0f
                : (float) (weights[0] * source[first][offset]
                        + weights[1] * source[first + 1][offset]
                        + weights[2] * source[first + 2][offset]);
    }

    /**
     * Applies the prepared weights to one channel offset.
     *
     * @param source form bank row data
     * @param offset channel offset inside each row
     * @return the blended value, bit-identical to the official order of operations
     */
    public float evaluate(float[][] source, int offset) {
        float a = sum(source, offset, forward, forwardCoefficients),
                b = sum(source, offset, backward, backwardCoefficients);
        if (forwardWeight == 0f) return b;
        if (backwardWeight == 0f) return a;
        return forwardWeight * a + backwardWeight * b;
    }
}
