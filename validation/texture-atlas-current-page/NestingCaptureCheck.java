package dev.turboism.validation.texture;

import dev.turboism.agent.shaded.jackson.databind.JsonNode;
import java.awt.BasicStroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/** Independent original-contour checks against actual host output matrices. No SDK/packer dependency. */
final class NestingCaptureCheck {
    private record Item(String id, Area shape) {}
    private record Placed(String id, Area raw, Area padded) {}

    static void validate(Path directory, JsonNode result) throws Exception {
        var items = new ArrayList<Item>();
        int width, height, margin, contours = 0, vertices = 0;
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(directory.resolve("nesting-input.bin"))))) {
            require(in.readUTF().equals("turboism.nesting.input.v1"), "Unexpected contour capture format");
            width = in.readInt(); height = in.readInt(); margin = in.readInt();
            in.readUTF(); in.readDouble();
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String id = in.readUTF(); in.readInt(); in.readInt();
                if (in.readUTF().equals("DRAW_DATA_SHAPES")) contours++;
                for (int flag = 0; flag < 4; flag++) in.readBoolean();
                if (in.readBoolean()) for (int p = 0; p < 6; p++) in.readDouble();
                in.readBoolean();
                Area shape = new Area();
                int rings = in.readInt();
                for (int r = 0; r < rings; r++) {
                    Path2D.Double path = new Path2D.Double();
                    int n = in.readInt(); vertices += n;
                    for (int v = 0; v < n; v++) {
                        double x = in.readDouble(), y = in.readDouble();
                        if (v == 0) path.moveTo(x, y); else path.lineTo(x, y);
                    }
                    path.closePath(); shape.add(new Area(path));
                }
                items.add(new Item(id, shape));
            }
            require(in.read() == -1, "Trailing input capture data");
        }
        var expected = new HashMap<String, double[]>();
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(directory.resolve("nesting-plan.bin"))))) {
            require(in.readUTF().equals("turboism.nesting.plan.v1"), "Unexpected plan capture format");
            double scale = in.readDouble();
            require(Math.abs(result.path("output").path("dataScale").asDouble() - scale) < 1e-6, "Plan/host scale mismatch");
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String id = in.readUTF();
                double x = in.readDouble(), y = in.readDouble(), angle = Math.toRadians(in.readDouble()), s = in.readDouble();
                require(expected.put(id, new double[] {Math.cos(angle) * s, Math.sin(angle) * s,
                        -Math.sin(angle) * s, Math.cos(angle) * s, x, y}) == null, "Duplicate captured placement");
            }
            require(in.readInt() == 0, "Nesting full-fit case overflowed");
            require(in.read() == -1, "Trailing plan capture data");
        }
        require(expected.size() == items.size(), "Nesting plan did not cover every issued item");
        require(contours > 0, "No real host contours");
        JsonNode rows = result.path("output").path("items");
        require(rows.size() == items.size(), "Output/capture count mismatch");
        var placed = new ArrayList<Placed>();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            double[] planned = expected.get(item.id());
            require(planned != null, "Missing planned matrix");
            JsonNode matrix = rows.get(i).path("matrix");
            require(matrix.size() == 6, "Missing host output matrix");
            double[] actual = new double[6];
            for (int p = 0; p < 6; p++) {
                actual[p] = matrix.get(p).asDouble(Double.NaN);
                require(Double.isFinite(actual[p]) && Math.abs(actual[p] - planned[p]) < 1e-5,
                        "Host matrix differs from real nesting plan: " + item.id());
            }
            Area raw = item.shape().createTransformedArea(new AffineTransform(actual));
            var b = raw.getBounds2D();
            require(b.getMinX() >= margin - 1e-5 && b.getMinY() >= margin - 1e-5
                && b.getMaxX() <= width - margin + 1e-5 && b.getMaxY() <= height - margin + 1e-5,
                "Original contour violates page margin: " + item.id());
            Area expanded = new Area(raw);
            if (margin > 0) expanded.add(new Area(new BasicStroke(2 * margin - 1e-3f,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(raw)));
            placed.add(new Placed(item.id(), raw, expanded));
        }
        placed.sort(Comparator.comparingDouble(p -> p.padded().getBounds2D().getMinX()));
        int broadPhasePairs = 0, boundingBoxOverlaps = 0;
        for (int i = 0; i < placed.size(); i++) {
            var a = placed.get(i);
            for (int j = i + 1; j < placed.size(); j++) {
                var b = placed.get(j);
                if (b.padded().getBounds2D().getMinX() >= a.padded().getBounds2D().getMaxX()) break;
                if (a.raw().getBounds2D().intersects(b.raw().getBounds2D())) boundingBoxOverlaps++;
                if (!a.padded().getBounds2D().intersects(b.padded().getBounds2D())) continue;
                broadPhasePairs++;
                Area intersection = new Area(a.padded());
                intersection.intersect(b.padded());
                require(intersection.isEmpty(), "Original contours violate 2*margin gap: " + a.id() + "/" + b.id());
            }
        }
        Files.writeString(directory.resolve("nesting-geometry.json"), "{\n\"items\":" + items.size()
            + ",\n\"realContours\":" + contours + ",\n\"vertices\":" + vertices
            + ",\n\"itemGapPixels\":" + 2 * margin + ",\n\"exactPairChecks\":" + broadPhasePairs
            + ",\n\"boundingBoxOverlaps\":" + boundingBoxOverlaps + ",\n\"contourOverlaps\":0,\n\"hostMatricesMatchPlan\":true\n}\n");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    private NestingCaptureCheck() {}
}
