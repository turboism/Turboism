package dev.turboism.validation.texture;

import dev.turboism.agent.shaded.jackson.databind.ObjectMapper;
import dev.turboism.agent.shaded.jackson.databind.node.ObjectNode;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exercises the independent contour verifier before admitting AABB-overlapping host output. */
public final class NestingCaptureCheckTest {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("nesting-capture-contract-");
        try {
            ObjectNode valid = write(dir, 33);
            NestingCaptureCheck.validate(dir, valid); // Small rectangle inside open U, 6px minimum gap.
            ObjectNode matrixMismatch = valid.deepCopy();
            ((ObjectNode) matrixMismatch.get("output").get("items").get(1)).putArray("matrix")
                .add(1).add(0).add(0).add(1).add(34).add(33);
            rejects(() -> NestingCaptureCheck.validate(dir, matrixMismatch));
            ObjectNode overlap = write(dir, 10);
            rejects(() -> NestingCaptureCheck.validate(dir, overlap));
            Files.delete(dir.resolve("nesting-input.bin"));
            rejects(() -> NestingCaptureCheck.validate(dir, valid));
            System.out.println("PASS: nesting capture validates concave nesting and rejects overlap, matrix mismatch and missing capture");
        } finally {
            try (var paths = Files.list(dir)) { for (Path p : paths.toList()) Files.delete(p); }
            Files.delete(dir);
        }
    }

    private static ObjectNode write(Path dir, double smallX) throws Exception {
        double[][][] shapes = {
            {{0, 0}, {100, 0}, {100, 100}, {80, 100}, {80, 20}, {20, 20}, {20, 100}, {0, 100}},
            {{0, 0}, {40, 0}, {40, 50}, {0, 50}}
        };
        try (var out = new DataOutputStream(Files.newOutputStream(dir.resolve("nesting-input.bin")))) {
            out.writeUTF("turboism.nesting.input.v1"); out.writeInt(110); out.writeInt(110); out.writeInt(3);
            out.writeUTF("FREE"); out.writeDouble(1); out.writeInt(2);
            for (int i = 0; i < 2; i++) {
                out.writeUTF("item-" + i); out.writeInt(100); out.writeInt(100); out.writeUTF("DRAW_DATA_SHAPES");
                for (int f = 0; f < 6; f++) out.writeBoolean(f == 0);
                out.writeInt(1); out.writeInt(shapes[i].length);
                for (var p : shapes[i]) { out.writeDouble(p[0]); out.writeDouble(p[1]); }
            }
        }
        try (var out = new DataOutputStream(Files.newOutputStream(dir.resolve("nesting-plan.bin")))) {
            out.writeUTF("turboism.nesting.plan.v1"); out.writeDouble(1); out.writeInt(2);
            for (int i = 0; i < 2; i++) {
                out.writeUTF("item-" + i); out.writeDouble(i == 0 ? 3 : smallX); out.writeDouble(i == 0 ? 3 : 33);
                out.writeDouble(0); out.writeDouble(1);
            }
            out.writeInt(0);
        }
        ObjectNode result = new ObjectMapper().createObjectNode();
        ObjectNode output = result.putObject("output"); output.put("dataScale", 1);
        var rows = output.putArray("items");
        rows.addObject().putArray("matrix").add(1).add(0).add(0).add(1).add(3).add(3);
        rows.addObject().putArray("matrix").add(1).add(0).add(0).add(1).add(smallX).add(33);
        return result;
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action) throws Exception {
        try { action.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Invalid nesting evidence accepted");
    }
}
