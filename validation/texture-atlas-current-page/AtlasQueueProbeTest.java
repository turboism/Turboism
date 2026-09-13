package dev.turboism.validation.texture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;

/** Offline evidence-contract tests. Never invokes premain, Swing, Wine or Cubism. */
public final class AtlasQueueProbeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static int assertions;
    public static void main(String[] ignored) throws Exception {
        for (int count : new int[]{100, 500, 1000, 2500}) {
            AtlasQueueProbe.validateCase(count, "native");
            AtlasQueueProbe.validateCase(count, "new");
            assertions += 2;
        }
        rejects(() -> AtlasQueueProbe.validateCase(1001, "native"));
        rejects(() -> AtlasQueueProbe.validateCase(2499, "new"));
        rejects(() -> AtlasQueueProbe.validateCase(100, "both"));
        rejects(() -> AtlasQueueProbe.validateCase(99, "new"));
        var directory = java.nio.file.Files.createTempDirectory("atlas-one-shot-test-");
        try {
            AtlasQueueProbe.markLayoutStarted(directory, "native");
            assertions++;
            try {
                AtlasQueueProbe.markLayoutStarted(directory, "native");
                throw new AssertionError("Duplicate invocation marker accepted");
            } catch (java.nio.file.FileAlreadyExistsException expected) { assertions++; }
        } finally {
            java.nio.file.Files.deleteIfExists(directory.resolve("layout-started.txt"));
            java.nio.file.Files.delete(directory);
        }
        ObjectNode good = result();
        AtlasQueueProbe.validateResult(good, 100, "new");
        assertions++;
        try {
            System.setProperty("turboism.validation.atlas.parallel", "true");
            rejects(() -> AtlasQueueProbe.validateResult(good, 100, "new"));
            ObjectNode parallel = result().put("plannerParallel", true);
            AtlasQueueProbe.validateResult(parallel, 100, "new"); assertions++;
            System.setProperty("turboism.validation.atlas.parallel", "yes");
            rejects(() -> AtlasQueueProbe.requestedParallel());
        } finally {
            System.clearProperty("turboism.validation.atlas.parallel");
        }
        mutated(node -> node.put("branch", "native"));
        mutated(node -> node.put("sequence", 2));
        mutated(node -> node.put("returned", false));
        mutated(node -> node.remove("returned"));
        mutated(node -> node.put("methodMs", Double.NaN));
        mutated(node -> node.put("methodMs", 0));
        mutated(node -> node.put("inputHash", "missing"));
        mutated(node -> node.put("plannerParallel", true));
        mutated(node -> node.remove("plannerParallel"));
        mutated(node -> ((ObjectNode)node.get("input")).put("count", 51));
        mutated(node -> ((ObjectNode)node.get("input")).put("requestedScale", 1));
        mutated(node -> ((ObjectNode)node.get("input")).put("rotate", false));
        mutated(node -> ((ObjectNode)node.get("input")).put("modelImage", true));
        mutated(node -> ((ObjectNode)node.get("output")).put("finite", false));
        mutated(node -> ((ObjectNode)node.get("output")).remove("inside"));
        mutated(node -> ((ObjectNode)node.get("output")).put("layerMatch", false));
        mutated(node -> ((ObjectNode)node.get("output")).put("overlaps", 1));
        mutated(node -> ((ObjectNode)node.get("output")).putArray("overflow").add(0));
        mutated(node -> ((ObjectNode)node.get("output")).putArray("items"));
        mutated(node -> ((ObjectNode)node.get("output")).put("dataScale", 0));
        mutated(node -> ((ObjectNode)node.get("output")).put("dataScale", 1.1));
        mutated(node -> ((ObjectNode)node.get("output")).put("dataScale", Double.POSITIVE_INFINITY));
        good.put("branch", "native").remove("plannerParallel");
        AtlasQueueProbe.validateResult(good, 100, "native");
        assertions++;
        ObjectNode ui = result();
        ui.put("uiActionToProgressClosedMs", 200).put("uiActionToProgressShownMs", 20)
            .put("uiMethodReturnToProgressClosedMs", 10).put("uiInputProbeMs", 3)
            .put("uiOutputValidationDeferred", true).put("uiProgressClass", "jp.noids.framework.e.a.f");
        AtlasQueueProbe.validateUiTiming(ui); assertions++;
        for (String key : new String[]{"uiActionToProgressClosedMs", "uiActionToProgressShownMs",
                "uiMethodReturnToProgressClosedMs", "uiInputProbeMs", "uiOutputValidationDeferred", "uiProgressClass"}) {
            ObjectNode missing = ui.deepCopy(); missing.remove(key);
            rejects(() -> AtlasQueueProbe.validateUiTiming(missing));
        }
        ObjectNode tooShort = ui.deepCopy(); tooShort.put("uiActionToProgressClosedMs", 1);
        rejects(() -> AtlasQueueProbe.validateUiTiming(tooShort));
        ObjectNode negative = ui.deepCopy(); negative.put("uiInputProbeMs", -1);
        rejects(() -> AtlasQueueProbe.validateUiTiming(negative));
        System.out.println("PASS: Atlas queue probe " + assertions + " offline contract assertions");
    }
    private static ObjectNode result() {
        ObjectNode value = JSON.createObjectNode();
        value.put("sequence", 1).put("returned", true).put("branch", "handled")
            .put("methodMs", 12.3).put("plannerParallel", false)
            .put("inputHash", "a".repeat(64)).put("outputHash", "b".repeat(64));
        ObjectNode input = value.putObject("input");
        input.put("count", 100).put("requestedScale", 0).put("rotate", true).put("modelImage", false);
        var items = input.putArray("items");
        for (int i = 0; i < 100; i++) items.addObject().put("id", i);
        ObjectNode output = value.putObject("output");
        output.put("count", 100).put("finite", true).put("inside", true).put("layerMatch", true)
            .put("overlaps", 0).put("dataScale", 1);
        output.set("items", items.deepCopy());
        output.putArray("overflow");
        return value;
    }
    private static void mutated(Consumer<ObjectNode> mutation) {
        ObjectNode node = result();
        mutation.accept(node);
        rejects(() -> AtlasQueueProbe.validateResult(node, 100, "new"));
    }
    private static void rejects(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Invalid case/evidence was accepted");
    }
}
