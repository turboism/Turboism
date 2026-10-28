package dev.turboism.validation.snsreuse;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Differential probe verifying raw float bits equivalence between fresh and reused
 * Cubism ExtendedInterpolationType.SNS_CURVE calculators within a common invocation scope
 * and across dedicated open-only scopes.
 */
public final class SnsReuseDifferentialProbe {

    public static void main(String[] args) throws Exception {
        String cubismJarPath = args.length > 0 ? args[0] : "/opt/dev/projects/turboism/cubism-ref/5.3.04/Live2D_Cubism.jar";
        System.out.println("================================================================================");
        System.out.println("  SNS Calculator Reuse Audit & Raw Float Bits Differential Probe");
        System.out.println("  Target Jar: " + cubismJarPath);
        System.out.println("  Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("  CWD: " + Path.of(".").toAbsolutePath().normalize());
        System.out.println("================================================================================");

        ProbeDriver driver = new ProbeDriver(cubismJarPath);
        boolean passed = driver.runAllSuites();
        if (!passed) {
            System.err.println("PROBE FAILED: Bit divergence or assertion failure detected!");
            System.exit(1);
        } else {
            System.out.println("PROBE SUCCESS: All test suites passed with 0 raw bit divergences.");
        }
    }

    public static final class ProbeDriver {
        private final Class<?> typeClass;
        private final Class<?> vectorClass;
        private final Object snsType;
        private final Method createCurveMethod;
        private final Method initMethod;
        private final Method evalMethod;
        private final Method getXMethod;
        private final Method getYMethod;
        private final Method setXMethod;
        private final Method setYMethod;

        public ProbeDriver(String jarPath) throws Exception {
            ClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[]{Path.of(jarPath).toUri().toURL()},
                ProbeDriver.class.getClassLoader()
            );

            this.typeClass = Class.forName(
                "com.live2d.cubism.doc.model.interpolator.extendedInterpolation.ExtendedInterpolationType",
                true, loader
            );
            this.snsType = Enum.valueOf((Class<Enum>) typeClass, "SNS_CURVE");
            this.createCurveMethod = typeClass.getMethod("b");

            this.vectorClass = Class.forName("com.live2d.graphics3d.type.GVector2", true, loader);
            this.getXMethod = vectorClass.getMethod("getX");
            this.getYMethod = vectorClass.getMethod("getY");
            this.setXMethod = vectorClass.getMethod("setX", float.class);
            this.setYMethod = vectorClass.getMethod("setY", float.class);

            Object dummyCalc = createCurveMethod.invoke(snsType);
            this.initMethod = dummyCalc.getClass().getMethod("a", List.class, boolean.class, List.class, List.class);
            this.evalMethod = dummyCalc.getClass().getMethod("a", int.class, float.class, vectorClass);
        }

        public Object newCalculator() throws Exception {
            return createCurveMethod.invoke(snsType);
        }

        public Object newVector(float x, float y) throws Exception {
            return vectorClass.getConstructor(float.class, float.class).newInstance(x, y);
        }

        public Object newVector() throws Exception {
            return vectorClass.getConstructor().newInstance();
        }

        public float getX(Object v) throws Exception {
            return (Float) getXMethod.invoke(v);
        }

        public float getY(Object v) throws Exception {
            return (Float) getYMethod.invoke(v);
        }

        public void setVector(Object v, float x, float y) throws Exception {
            setXMethod.invoke(v, x);
            setYMethod.invoke(v, y);
        }

        public void initCalculator(Object calc, List<Object> pts, boolean isOpen, List<Boolean> isCorners, List<Float> knots) throws Exception {
            initMethod.invoke(calc, pts, isOpen, isCorners, knots);
        }

        public Object evalCalculator(Object calc, int pointNo, float t, Object outVec) throws Exception {
            return evalMethod.invoke(calc, pointNo, t, outVec);
        }

        public boolean runAllSuites() throws Exception {
            int totalAssertions = 0;
            int failedAssertions = 0;

            System.out.println("\n--- [Suite 1] Alternating Vertices (Simulation of NativeMeshInterpolationBridge vertex loop) ---");
            SuiteResult res1 = testAlternatingVertices();
            totalAssertions += res1.assertions;
            failedAssertions += res1.failures;

            System.out.println("\n--- [Suite 2] Variable Source Lengths (Testing clear() on length expansion and contraction) ---");
            SuiteResult res2 = testVariableSourceLengths();
            totalAssertions += res2.assertions;
            failedAssertions += res2.failures;

            System.out.println("\n--- [Suite 3] Degenerate & Extreme Coordinates (Coincident points, lines, extreme scales) ---");
            SuiteResult res3 = testDegenerateInputs();
            totalAssertions += res3.assertions;
            failedAssertions += res3.failures;

            System.out.println("\n--- [Suite 4] Evaluate Return Object & Vector Reuse (Identity & dirty ret verification) ---");
            SuiteResult res4 = testOutputVectorReuseAndIdentity();
            totalAssertions += res4.assertions;
            failedAssertions += res4.failures;

            System.out.println("\n--- [Suite 5] Negative Audit: General Shared Cache Vulnerability (Closed->Open Pollution) ---");
            SuiteResult res5 = testClosedCurvePollutionVulnerability();
            totalAssertions += res5.assertions;
            failedAssertions += res5.failures;

            System.out.println("\n--- [Suite 6] Dedicated Open-Only Cross-Call Workspace Verification (Safe under strict invariant) ---");
            SuiteResult res6 = testDedicatedOpenOnlyCrossCallReuse();
            totalAssertions += res6.assertions;
            failedAssertions += res6.failures;

            System.out.println("\n--------------------------------------------------------------------------------");
            System.out.printf("Total Assertions: %d | Total Failures: %d\n", totalAssertions, failedAssertions);
            System.out.println("--------------------------------------------------------------------------------");
            return failedAssertions == 0;
        }

        private static class SuiteResult {
            final int assertions;
            final int failures;
            SuiteResult(int assertions, int failures) {
                this.assertions = assertions;
                this.failures = failures;
            }
        }

        private static void assertBitEquivalence(String tag, float expX, float actX, float expY, float actY, int[] stats) {
            stats[0]++;
            int expBitsX = Float.floatToRawIntBits(expX);
            int actBitsX = Float.floatToRawIntBits(actX);
            int expBitsY = Float.floatToRawIntBits(expY);
            int actBitsY = Float.floatToRawIntBits(actY);

            if (expBitsX != actBitsX || expBitsY != actBitsY) {
                stats[1]++;
                System.err.printf("[DIVERGENCE] %s\n  X: expected=%f (0x%08X), actual=%f (0x%08X)\n  Y: expected=%f (0x%08X), actual=%f (0x%08X)\n",
                    tag, expX, expBitsX, actX, actBitsX, expY, expBitsY, actY, actBitsY);
            }
        }

        /**
         * Suite 1: Simulates NativeMeshInterpolationBridge processing many mesh vertices.
         * We cycle through multiple distinct vertex tracks and evaluate multiple t steps.
         */
        private SuiteResult testAlternatingVertices() throws Exception {
            int[] stats = new int[2];
            int numVertices = 12;
            int numRounds = 4;
            int pointCount = 5;

            float[][][] vertexTracks = new float[numVertices][pointCount][2];
            for (int v = 0; v < numVertices; v++) {
                for (int p = 0; p < pointCount; p++) {
                    vertexTracks[v][p][0] = (float) (Math.sin(v * 0.5 + p * 1.2) * 100.0);
                    vertexTracks[v][p][1] = (float) (Math.cos(v * 0.7 + p * 0.9) * 150.0);
                }
            }

            List<Float> knots = new ArrayList<>();
            for (int p = 0; p < pointCount; p++) knots.add((float) p / (pointCount - 1));

            Object reusedCalc = newCalculator();
            Object reusedOutVec = newVector();

            for (int round = 0; round < numRounds; round++) {
                for (int v = 0; v < numVertices; v++) {
                    List<Object> currentPoints = new ArrayList<>();
                    for (int p = 0; p < pointCount; p++) {
                        currentPoints.add(newVector(vertexTracks[v][p][0], vertexTracks[v][p][1]));
                    }

                    Object freshCalc = newCalculator();
                    initCalculator(freshCalc, currentPoints, true, null, knots);
                    initCalculator(reusedCalc, currentPoints, true, null, knots);

                    for (int interval = 0; interval < pointCount - 1; interval++) {
                        for (float t : new float[]{0.0f, 0.25f, 0.5f, 0.75f, 1.0f}) {
                            Object freshOut = newVector();
                            Object freshRes = evalCalculator(freshCalc, interval, t, freshOut);
                            Object reusedRes = evalCalculator(reusedCalc, interval, t, reusedOutVec);

                            assertBitEquivalence(
                                "AlternatingVertex round=" + round + " v=" + v + " int=" + interval + " t=" + t,
                                getX(freshRes), getX(reusedRes),
                                getY(freshRes), getY(reusedRes),
                                stats
                            );
                        }
                    }
                }
            }
            System.out.printf("  Completed %d bitwise checks. Divergences: %d\n", stats[0], stats[1]);
            return new SuiteResult(stats[0], stats[1]);
        }

        /**
         * Suite 2: Rapidly changing point counts: 2 -> 6 -> 3 -> 8 -> 2 -> 4 -> 12 -> 2.
         * Verifies that clearing the internal node list leaves no residual higher-index entries.
         */
        private SuiteResult testVariableSourceLengths() throws Exception {
            int[] stats = new int[2];
            int[] lengths = new int[]{2, 6, 3, 8, 2, 4, 12, 5, 2, 9, 3};

            Object reusedCalc = newCalculator();
            Object reusedOutVec = newVector();

            for (int step = 0; step < lengths.length; step++) {
                int count = lengths[step];
                List<Object> pts = new ArrayList<>();
                List<Float> knots = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    pts.add(newVector((float) (i * 25.0 + Math.sin(step + i) * 10.0), (float) (i * 30.0 - Math.cos(step * 2 + i) * 15.0)));
                    knots.add((float) i / (count - 1));
                }

                Object freshCalc = newCalculator();
                initCalculator(freshCalc, pts, true, null, knots);
                initCalculator(reusedCalc, pts, true, null, knots);

                for (int interval = 0; interval < count - 1; interval++) {
                    for (float t : new float[]{0.0f, 0.333f, 0.5f, 0.667f, 1.0f}) {
                        Object freshOut = newVector();
                        Object freshRes = evalCalculator(freshCalc, interval, t, freshOut);
                        Object reusedRes = evalCalculator(reusedCalc, interval, t, reusedOutVec);

                        assertBitEquivalence(
                            "VarLen step=" + step + " len=" + count + " int=" + interval + " t=" + t,
                            getX(freshRes), getX(reusedRes),
                            getY(freshRes), getY(reusedRes),
                            stats
                        );
                    }
                }
            }
            System.out.printf("  Completed %d bitwise checks. Divergences: %d\n", stats[0], stats[1]);
            return new SuiteResult(stats[0], stats[1]);
        }

        /**
         * Suite 3: Degenerate scenarios.
         */
        private SuiteResult testDegenerateInputs() throws Exception {
            int[] stats = new int[2];

            List<float[][]> testCases = new ArrayList<>();
            testCases.add(new float[][]{{10f, 20f}, {10f, 20f}, {10f, 20f}, {10f, 20f}});
            testCases.add(new float[][]{{0f, 0f}, {0f, 0f}, {50f, 50f}, {50f, 50f}, {100f, 100f}});
            testCases.add(new float[][]{{-100f, 5f}, {0f, 5f}, {100f, 5f}, {200f, 5f}});
            testCases.add(new float[][]{{7f, -500f}, {7f, 0f}, {7f, 500f}});
            testCases.add(new float[][]{{1e6f, -1e6f}, {2e6f, 0f}, {3e6f, 1e6f}});
            testCases.add(new float[][]{{1e-5f, 1e-5f}, {2e-5f, 1.5e-5f}, {3e-5f, 2.5e-5f}});
            testCases.add(new float[][]{{123.456f, -789.012f}, {-456.789f, 234.567f}});

            Object reusedCalc = newCalculator();
            Object reusedOutVec = newVector();

            for (int c = 0; c < testCases.size(); c++) {
                float[][] coords = testCases.get(c);
                int count = coords.length;
                List<Object> pts = new ArrayList<>();
                List<Float> knots = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    pts.add(newVector(coords[i][0], coords[i][1]));
                    knots.add((float) i / (count - 1));
                }

                Object freshCalc = newCalculator();
                initCalculator(freshCalc, pts, true, null, knots);
                initCalculator(reusedCalc, pts, true, null, knots);

                for (int interval = 0; interval < count - 1; interval++) {
                    for (float t : new float[]{0.0f, 0.0001f, 0.5f, 0.9999f, 1.0f}) {
                        Object freshOut = newVector();
                        Object freshRes = evalCalculator(freshCalc, interval, t, freshOut);
                        Object reusedRes = evalCalculator(reusedCalc, interval, t, reusedOutVec);

                        assertBitEquivalence(
                            "Degenerate case=" + c + " int=" + interval + " t=" + t,
                            getX(freshRes), getX(reusedRes),
                            getY(freshRes), getY(reusedRes),
                            stats
                        );
                    }
                }
            }
            System.out.printf("  Completed %d bitwise checks. Divergences: %d\n", stats[0], stats[1]);
            return new SuiteResult(stats[0], stats[1]);
        }

        /**
         * Suite 4: Tests evaluate return object reference identity and outVec reuse.
         * Explicitly verifies:
         *  1. When eval succeeds, result == outVec (reference equality).
         *  2. Pre-existing dirty values in outVec (NaN, Inf) are completely overwritten.
         *  3. Caller reading result matches reading outVec.
         */
        private SuiteResult testOutputVectorReuseAndIdentity() throws Exception {
            int[] stats = new int[2];

            List<Object> pts = new ArrayList<>();
            pts.add(newVector(10.0f, 20.0f));
            pts.add(newVector(50.0f, -40.0f));
            pts.add(newVector(120.0f, 80.0f));
            pts.add(newVector(200.0f, 10.0f));
            List<Float> knots = List.of(0.0f, 0.333f, 0.667f, 1.0f);

            Object freshCalc = newCalculator();
            initCalculator(freshCalc, pts, true, null, knots);

            Object reusedCalc = newCalculator();
            initCalculator(reusedCalc, pts, true, null, knots);

            Object reusedOut = newVector();

            float[][] dirtyPreloads = new float[][]{
                {Float.NaN, Float.NaN},
                {Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY},
                {123456.78f, -987654.32f},
                {0.0f, 0.0f}
            };

            for (float[] dirty : dirtyPreloads) {
                for (int interval = 0; interval < 3; interval++) {
                    for (float t : new float[]{0.0f, 0.1f, 0.5f, 0.9f, 1.0f}) {
                        Object freshOut = newVector();
                        Object freshRes = evalCalculator(freshCalc, interval, t, freshOut);

                        setVector(reusedOut, dirty[0], dirty[1]);
                        Object reusedRes = evalCalculator(reusedCalc, interval, t, reusedOut);

                        // 1. Identity assertion: result MUST be identical reference to outVec
                        stats[0]++;
                        if (reusedRes != reusedOut) {
                            stats[1]++;
                            System.err.printf("[IDENTITY_FAIL] eval did not return outVec instance! returned=%s outVec=%s\n", reusedRes, reusedOut);
                        }

                        // 2. Semantic consistency between reading result and reading outVec
                        stats[0]++;
                        if (getX(reusedRes) != getX(reusedOut) || getY(reusedRes) != getY(reusedOut)) {
                            stats[1]++;
                            System.err.println("[READ_INCONSISTENCY] Reading result differs from reading outVec!");
                        }

                        // 3. Raw bit equivalence with fresh calculation
                        assertBitEquivalence(
                            "OutputReuse dirty=(" + dirty[0] + "," + dirty[1] + ") int=" + interval + " t=" + t,
                            getX(freshRes), getX(reusedRes),
                            getY(freshRes), getY(reusedRes),
                            stats
                        );
                    }
                }
            }
            System.out.printf("  Completed %d checks. Divergences/Failures: %d\n", stats[0], stats[1]);
            return new SuiteResult(stats[0], stats[1]);
        }

        /**
         * Suite 5 (Negative Audit):
         * Verifies the field pollution vulnerability in com.live2d.graphics.curve.g:
         * If a calculator is previously used in a GENERAL shared cache where isOpen = false (closed curve),
         * fields 'firstCornerIndex' (b) and 'isPureClosed' (c) are MUTATED.
         * When subsequently reused with isOpen = true, those fields are NOT reset!
         * This test proves why GENERAL unconstrained cross-call sharing is UNSAFE.
         */
        private SuiteResult testClosedCurvePollutionVulnerability() throws Exception {
            List<Object> ptsClosed = new ArrayList<>();
            ptsClosed.add(newVector(0.0f, 0.0f));
            ptsClosed.add(newVector(100.0f, 0.0f));
            ptsClosed.add(newVector(100.0f, 100.0f));
            ptsClosed.add(newVector(0.0f, 100.0f));
            List<Boolean> corners = List.of(false, false, true, false); // corner at index 2
            List<Float> knotsClosed = List.of(0.0f, 0.25f, 0.5f, 0.75f);

            Object pollutedCalc = newCalculator();
            initCalculator(pollutedCalc, ptsClosed, false, corners, knotsClosed);

            List<Object> ptsOpen = new ArrayList<>();
            ptsOpen.add(newVector(10.0f, 20.0f));
            ptsOpen.add(newVector(30.0f, 40.0f));
            ptsOpen.add(newVector(60.0f, 80.0f));
            List<Float> knotsOpen = List.of(0.0f, 0.5f, 1.0f);

            Object freshCalc = newCalculator();
            initCalculator(freshCalc, ptsOpen, true, null, knotsOpen);
            initCalculator(pollutedCalc, ptsOpen, true, null, knotsOpen);

            Object freshOut = newVector();
            Object pollutedOut = newVector();

            Object freshRes = evalCalculator(freshCalc, 0, 0.5f, freshOut);
            Object pollutedRes = evalCalculator(pollutedCalc, 0, 0.5f, pollutedOut);

            int freshBitsX = Float.floatToRawIntBits(getX(freshRes));
            int pollBitsX = Float.floatToRawIntBits(getX(pollutedRes));

            System.out.printf("  Fresh (clean) result at interval=0, t=0.5: x=%f (0x%08X), y=%f\n",
                getX(freshRes), freshBitsX, getY(freshRes));
            System.out.printf("  Polluted result after closed-curve call:   x=%f (0x%08X), y=%f\n",
                getX(pollutedRes), pollBitsX, getY(pollutedRes));

            if (freshBitsX != pollBitsX) {
                System.out.println("  >>> CONFIRMED VULNERABILITY: General shared calculator produced corrupt result! <<<");
                System.out.println("  Reason: SNSCurveWithCorner fields 'b' (firstCornerIndex) and 'c' (isPureClosed) were not reset when isOpen=true.");
                return new SuiteResult(1, 0);
            } else {
                System.err.println("  Unexpected: pollution did not diverge on this specific input.");
                return new SuiteResult(1, 1);
            }
        }

        /**
         * Suite 6 (Dedicated Open-Only Cross-Call Verification):
         * Verifies that if a calculator is DEDICATED strictly to open curves (isOpen=true, isCorners=null),
         * cross-call reuse across multiple simulated common/frame invocations is 100% bit-exact!
         * This strictly distinguishes "closed->open pollution" from "dedicated open-only caching".
         */
        private SuiteResult testDedicatedOpenOnlyCrossCallReuse() throws Exception {
            int[] stats = new int[2];
            int simulatedInvocations = 10;
            int verticesPerInvocation = 8;
            int pointCount = 4;

            Object dedicatedCalc = newCalculator();
            Object dedicatedOut = newVector();

            for (int inv = 0; inv < simulatedInvocations; inv++) {
                List<Float> knots = List.of(0.0f, 0.333f, 0.667f, 1.0f);
                for (int v = 0; v < verticesPerInvocation; v++) {
                    List<Object> pts = new ArrayList<>();
                    for (int p = 0; p < pointCount; p++) {
                        pts.add(newVector(
                            (float) (inv * 100.0 + v * 10.0 + p * 50.0),
                            (float) (Math.sin(inv + v + p) * 200.0)
                        ));
                    }

                    Object freshCalc = newCalculator();
                    initCalculator(freshCalc, pts, true, null, knots);
                    initCalculator(dedicatedCalc, pts, true, null, knots);

                    for (int interval = 0; interval < pointCount - 1; interval++) {
                        for (float t : new float[]{0.0f, 0.3f, 0.7f, 1.0f}) {
                            Object freshOut = newVector();
                            Object freshRes = evalCalculator(freshCalc, interval, t, freshOut);
                            Object dedicatedRes = evalCalculator(dedicatedCalc, interval, t, dedicatedOut);

                            assertBitEquivalence(
                                "DedicatedOpenCrossCall inv=" + inv + " v=" + v + " int=" + interval + " t=" + t,
                                getX(freshRes), getX(dedicatedRes),
                                getY(freshRes), getY(dedicatedRes),
                                stats
                            );
                        }
                    }
                }
            }
            System.out.printf("  Completed %d dedicated open cross-call bitwise checks. Divergences: %d\n", stats[0], stats[1]);
            return new SuiteResult(stats[0], stats[1]);
        }
    }
}
