package dev.turboism.adapter.cubism.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class PerformanceProbeRollbackTest {

    private static final String ARTIFACT_SHA = ReviewedHostArtifacts.CUBISM_5_3_02.sha256();
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void writesRollbackEvidenceForEveryWovenOwnerAndSelector() throws Exception {
        final List<PerformanceProbeMethodTransformer.Target> targets = List.of(
                new PerformanceProbeMethodTransformer.Target(
                        "fixture/RenderTarget", "render", "(Z)V", PerformanceProbeMetric.RENDER_SCENE),
                new PerformanceProbeMethodTransformer.Target(
                        "fixture/ModelTarget", "update", "()V", PerformanceProbeMetric.UPDATE_MODEL_INSTANCES));

        final PerformanceProbeMethodTransformer transformer =
                new PerformanceProbeMethodTransformer(null, null, targets);
        final byte[] renderInstrumented = transformer.transform(
                null, null, "fixture/RenderTarget", null, null, fixtureClass("fixture/RenderTarget", "render", "(Z)V"));
        final byte[] modelInstrumented = transformer.transform(
                null, null, "fixture/ModelTarget", null, null, fixtureClass("fixture/ModelTarget", "update", "()V"));
        assertTrue(renderInstrumented != null && modelInstrumented != null);
        assertEquals(2, transformer.wovenOwners().size());

        final Path output = temporary.resolve("run/rollback-manifest.json");
        new PerformanceProbeRollbackWriter()
                .write(
                        output,
                        ReviewedHostArtifacts.CUBISM_5_3_02_VERSION,
                        ARTIFACT_SHA,
                        "run-01",
                        "on",
                        "camera",
                        targets,
                        transformer.matchCounts());

        final JsonNode root = JSON.readTree(Files.readAllBytes(output));
        assertEquals(
                "turboism.cubism.performance-probe-rollback",
                root.path("format").asText());
        assertEquals(1, root.path("schemaVersion").asInt());
        assertEquals("5.3.02", root.path("cubismVersion").asText());
        assertEquals(ARTIFACT_SHA, root.path("artifactSha256").asText());
        assertEquals("run-01", root.path("runId").asText());
        assertEquals("on", root.path("variant").asText());
        assertEquals("camera", root.path("scenario").asText());

        assertEquals(2, root.path("owners").size());
        for (JsonNode owner : root.path("owners")) {
            assertTrue(owner.asText().startsWith("fixture."));
        }

        assertEquals(2, root.path("selectors").size());
        for (JsonNode selector : root.path("selectors")) {
            assertEquals(1, selector.path("matches").asInt());
            assertTrue(selector.path("owner").asText().startsWith("fixture."));
            assertTrue(selector.path("metric").asText().matches("[a-z][A-Za-z]*"));
        }
    }

    @Test
    void refusesToPublishAPartialSelectorSet() throws Exception {
        final List<PerformanceProbeMethodTransformer.Target> targets = List.of(
                new PerformanceProbeMethodTransformer.Target(
                        "fixture/RenderTarget", "render", "(Z)V", PerformanceProbeMetric.RENDER_SCENE),
                new PerformanceProbeMethodTransformer.Target(
                        "fixture/ModelTarget", "update", "()V", PerformanceProbeMetric.UPDATE_MODEL_INSTANCES));
        final PerformanceProbeMethodTransformer transformer =
                new PerformanceProbeMethodTransformer(null, null, targets);
        transformer.transform(
                null, null, "fixture/RenderTarget", null, null, fixtureClass("fixture/RenderTarget", "render", "(Z)V"));

        final PerformanceProbeRollbackWriter writer = new PerformanceProbeRollbackWriter();
        final Map<PerformanceProbeMethodTransformer.Target, Integer> partial = Map.of(targets.get(0), 1);
        final Path output = temporary.resolve("partial.json");
        assertThrows(
                IllegalArgumentException.class,
                () -> writer.write(
                        output,
                        ReviewedHostArtifacts.CUBISM_5_3_02_VERSION,
                        ARTIFACT_SHA,
                        "run-01",
                        "on",
                        "camera",
                        targets,
                        partial));
        assertFalse(Files.exists(output));
    }

    private static byte[] fixtureClass(final String internalName, final String methodName, final String descriptor) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        final MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
