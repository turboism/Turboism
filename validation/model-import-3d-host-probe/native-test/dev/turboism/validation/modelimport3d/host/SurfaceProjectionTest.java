package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;

/** Analytic source-space checks, never evidence of native interpolation accuracy. */
public final class SurfaceProjectionTest {
    private static final SurfaceProjection.Vertex ORIGIN = new SurfaceProjection.Vertex(0, 0, 0);
    private static final SurfaceProjection.Camera CAMERA = new SurfaceProjection.Camera(512, 512, 200, ORIGIN);
    private static int checks;

    public static void main(String[] args) { run(); }

    static void run() {
        checks = 0;
        var source = List.of(new SurfaceProjection.Vertex(1, 2, 3));
        var neutral = sample(source, 0, 0);
        point(neutral.positions().get(0), 712, 112);
        near(neutral.depths().get(0), 3);
        var yaw = sample(source, 90, 0);
        point(yaw.positions().get(0), 1112, 112);
        near(yaw.depths().get(0), -1);
        var pitch = sample(source, 0, 90);
        point(pitch.positions().get(0), 712, 1112);
        near(pitch.depths().get(0), 2);
        // X then Y maps (1,2,3) -> (1,-3,2) -> (2,-3,-1).
        // Reverse composition and summed 2D offsets both give different answers.
        var joint = sample(source, 90, 90);
        point(joint.positions().get(0), 912, 1112);
        near(joint.depths().get(0), -1);
        double additiveX = yaw.positions().get(0).x() + pitch.positions().get(0).x()
            - neutral.positions().get(0).x();
        require(Math.abs(joint.positions().get(0).x() - additiveX) > 100,
            "joint projection must not sum single-axis offsets");

        // Independent closed-form values for a non-cardinal compound rotation.
        var half = sample(source, 30, 30);
        point(half.positions().get(0), 512 + 200 * (0.5 + 1.25 * Math.sqrt(3)),
            512 - 200 * (Math.sqrt(3) - 1.5));
        near(half.depths().get(0), 1.75 + Math.sqrt(3) / 2);

        var pivot = new SurfaceProjection.Vertex(4, 5, 6);
        var offsetCamera = new SurfaceProjection.Camera(512, 512, 200, pivot);
        var fixed = SurfaceProjection.project(List.of(pivot), offsetCamera, new SurfaceProjection.Pose(30, -30));
        point(fixed.positions().get(0), 1312, -488);
        near(fixed.depths().get(0), 6);
        var offset = SurfaceProjection.project(List.of(new SurfaceProjection.Vertex(5, 7, 9)),
            offsetCamera, new SurfaceProjection.Pose(90, 90));
        point(offset.positions().get(0), 1712, 112);
        near(offset.depths().get(0), 5);

        // The admitted native neutral cube exposes source Z=-1, not Z=+1.
        // Rays travel +Z; ordering must agree before any multi-surface compiler uses depths.
        var layers = sample(List.of(new SurfaceProjection.Vertex(0, 0, -1),
            new SurfaceProjection.Vertex(0, 0, 1)), 0, 0);
        require(layers.positions().get(0).equals(layers.positions().get(1)), "overlapping layers");
        require(layers.depths().get(0) < layers.depths().get(1), "negative Z layer is nearer");
        var pair = List.of(new SurfaceProjection.Vertex(-1, 0, 0),
            new SurfaceProjection.Vertex(1, 0, 0));
        var leftTurn = sample(pair, -30, 0);
        var rightTurn = sample(pair, 30, 0);
        require(leftTurn.depths().get(0) < leftTurn.depths().get(1), "left turn depth order");
        require(rightTurn.depths().get(0) > rightTurn.depths().get(1), "right turn swaps depth order");

        // Coincident seam vertices must survive in their original slots at all nine keys.
        var seam = List.of(source.get(0), ORIGIN, source.get(0));
        for (double y : new double[] {-30, 0, 30}) {
            for (double p : new double[] {-30, 0, 30}) {
                var s = sample(seam, y, p);
                require(s.positions().size() == 3 && s.depths().size() == 3, "vertex count");
                require(s.positions().get(0).equals(s.positions().get(2)), "seam slots preserved");
                point(s.positions().get(1), 512, 512);
                require(s.pose().equals(new SurfaceProjection.Pose(y, p)), "complete pose identity");
            }
        }
        try {
            joint.positions().clear();
            throw new AssertionError("mutable projected positions");
        } catch (UnsupportedOperationException expected) { checks++; }

        // Preserve B0's pre-existing yaw result, including float rounding.
        for (float[] v : new float[][] {{.9f, .6f, .4f}, {-.9f, .6f, -.4f}, {0, -.9f, .2f},
                {.8f, .8f, .3f}, {-.8f, .8f, .1f}, {-.8f, -.8f, -.3f}, {.8f, -.8f, -.1f}}) {
            for (int angle : new int[] {-30, 0, 30}) {
                double r = Math.toRadians(angle);
                var old = new Point2((float) (512 + 200 * (v[0] * Math.cos(r) + v[2] * Math.sin(r))),
                    (float) (512 - 200f * v[1]));
                var actual = sample(List.of(new SurfaceProjection.Vertex(v[0], v[1], v[2])), angle, 0);
                // Old Y multiplies in float before double promotion; allow only rounding noise.
                point(actual.positions().get(0), old.x(), old.y());
            }
        }

        // Three sparse keys are not a 0.1px interpolation certificate.
        var unit = List.of(new SurfaceProjection.Vertex(1, 0, 0));
        double midpointX = (sample(unit, 0, 0).positions().get(0).x()
            + sample(unit, 30, 0).positions().get(0).x()) / 2;
        require(Math.abs(midpointX - sample(unit, 15, 0).positions().get(0).x()) > 6,
            "sparse-key interpolation counterexample");
        reject(() -> new SurfaceProjection.Vertex(Double.NaN, 0, 0));
        reject(() -> new SurfaceProjection.Pose(0, Double.POSITIVE_INFINITY));
        reject(() -> new SurfaceProjection.Camera(0, 0, 0, ORIGIN));
        reject(() -> new SurfaceProjection.Camera(0, 0, -1, ORIGIN));
        reject(() -> sample(List.of(new SurfaceProjection.Vertex(Double.MAX_VALUE, 0, 0)), 0, 0));
        densityChecks(source);
        System.out.println("SurfaceProjection checks=" + checks + " PASS; hostExecuted=false");
    }

    private static void densityChecks(List<SurfaceProjection.Vertex> source) {
        var grid = SurfaceKeyPlan.linear(source, CAMERA, 30, 30, 0.05, 10000);
        require(grid.yawKeys().size() > 3 && grid.pitchKeys().size() > 3, "dense compound grid");
        require(grid.yawKeys().contains(0d) && grid.pitchKeys().contains(0d), "neutral keys retained");
        require(grid.yawKeys().get(0) == -30 && grid.yawKeys().get(grid.yawKeys().size() - 1) == 30,
            "domain endpoints retained");
        require(grid.errorBoundPixels() <= 0.05, "analytic linear interpolation budget");
        var single = SurfaceKeyPlan.linear(source, CAMERA, 30, 0, 0.05, 10000);
        require(single.pitchKeys().equals(List.of(0d)), "unvaried axis has only neutral key");
        var neutral = SurfaceKeyPlan.linear(source, CAMERA, 0, 0, 0.05, 1);
        require(neutral.errorBoundPixels() == 0 && neutral.yawKeys().equals(List.of(0d)), "neutral-only budget");
        var pivotOnly = SurfaceKeyPlan.linear(List.of(ORIGIN), CAMERA, 30, 30, 0.05, 9);
        require(pivotOnly.errorBoundPixels() == 0 && pivotOnly.yawKeys().size() == 3, "zero-radius case");
        reject(() -> SurfaceKeyPlan.linear(source, CAMERA, 30, 30, 0.05, 9));
        reject(() -> SurfaceKeyPlan.linear(source, CAMERA, 30, 30, Double.NaN, 10000));
        reject(() -> SurfaceKeyPlan.linear(source, CAMERA, -30, 30, 0.05, 10000));
        reject(() -> SurfaceKeyPlan.linear(List.of(), CAMERA, 30, 30, 0.05, 10000));
        reject(() -> SurfaceKeyPlan.linear(source, CAMERA, 30, 30, Double.MIN_VALUE, 10000));
        // Independent bilinear evaluation, fixed seed; this is not Cubism evaluation.
        var random = new java.util.Random(4012026);
        for (int i = 0; i < 1000; i++) {
            int a = random.nextInt(grid.yawKeys().size() - 1), b = random.nextInt(grid.pitchKeys().size() - 1);
            double u = i % 2 == 0 ? 0.5 : random.nextDouble();
            double v = i % 2 == 0 ? 0.5 : random.nextDouble();
            double y0 = grid.yawKeys().get(a), y1 = grid.yawKeys().get(a + 1);
            double p0 = grid.pitchKeys().get(b), p1 = grid.pitchKeys().get(b + 1);
            Point2 c00 = sample(source, y0, p0).positions().get(0);
            Point2 c10 = sample(source, y1, p0).positions().get(0);
            Point2 c01 = sample(source, y0, p1).positions().get(0);
            Point2 c11 = sample(source, y1, p1).positions().get(0);
            double x = (1-u)*(1-v)*c00.x() + u*(1-v)*c10.x() + (1-u)*v*c01.x() + u*v*c11.x();
            double y = (1-u)*(1-v)*c00.y() + u*(1-v)*c10.y() + (1-u)*v*c01.y() + u*v*c11.y();
            Point2 truth = sample(source, y0 + u*(y1-y0), p0 + v*(p1-p0)).positions().get(0);
            require(Math.hypot(x-truth.x(), y-truth.y()) <= grid.errorBoundPixels() + 0.0002,
                "joint interpolation exceeded analytic bound plus float-rounding margin");
        }
    }

    private static SurfaceProjection.Sample sample(List<SurfaceProjection.Vertex> source, double yaw, double pitch) {
        return SurfaceProjection.project(source, CAMERA, new SurfaceProjection.Pose(yaw, pitch));
    }

    private static void point(Point2 actual, double x, double y) {
        require(Math.hypot(actual.x() - x, actual.y() - y) < 0.0001, "projected point: " + actual);
    }

    private static void near(double actual, double expected) {
        require(Math.abs(actual - expected) < 1e-10, "depth: " + actual + " expected " + expected);
    }

    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("invalid projection accepted");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
        checks++;
    }
}
