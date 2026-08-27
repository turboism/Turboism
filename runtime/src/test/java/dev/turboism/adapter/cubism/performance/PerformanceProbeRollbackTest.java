package dev.turboism.adapter.cubism.performance;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceProbeRollbackTest {

    private static final String ARTIFACT_SHA = ReviewedHostArtifacts.CUBISM_5_3_02.sha256();
    private static final String AGENT_SHA = "a".repeat(64);
    private static final String FIXTURE_SHA = "b".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void capturesAndWritesExactRollbackEvidenceFromActualBytes() throws Exception {
        final List<PerformanceProbeMethodTransformer.Target> targets = List.of(
            new PerformanceProbeMethodTransformer.Target(
                "fixture/RenderTarget", "render", "(Z)V", PerformanceProbeMetric.RENDER_SCENE),
            new PerformanceProbeMethodTransformer.Target(
                "fixture/ModelTarget", "update", "()V", PerformanceProbeMetric.UPDATE_MODEL_INSTANCES)
        );
        final byte[] renderOriginal = fixtureClass("fixture/RenderTarget", "render", "(Z)V");
        final byte[] modelOriginal = fixtureClass("fixture/ModelTarget", "update", "()V");

        final PerformanceProbeMethodTransformer transformer =
            new PerformanceProbeMethodTransformer(null, null, targets);
        final byte[] renderInstrumented = transformer.transform(
            null, null, "fixture/RenderTarget", null, null, renderOriginal);
        final byte[] modelInstrumented = transformer.transform(
            null, null, "fixture/ModelTarget", null, null, modelOriginal);
        assertTrue(renderInstrumented != null && modelInstrumented != null);

        final Map<String, String> before = transformer.beforeSha256();
        final Map<String, String> instrumented = transformer.instrumentedSha256();
        assertEquals(2, before.size());
        assertEquals(2, instrumented.size());
        assertTrue(before.containsKey("fixture/RenderTarget"));
        assertTrue(before.containsKey("fixture/ModelTarget"));
        assertFalse(before.get("fixture/RenderTarget").equals(instrumented.get("fixture/RenderTarget")));
        assertFalse(before.get("fixture/ModelTarget").equals(instrumented.get("fixture/ModelTarget")));

        // Cleanup retransformation: the mutating transformer is gone; the
        // non-mutating observer sees the restored (original) bytes once per owner.
        final PerformanceProbeRollbackObserver observer =
            new PerformanceProbeRollbackObserver(null, null, targets);
        observer.beginRestoration();
        assertNull(observer.transform(null, null, "fixture/RenderTarget", null, null, renderOriginal));
        assertNull(observer.transform(null, null, "fixture/ModelTarget", null, null, modelOriginal));
        final Map<String, String> after = observer.observedSha256();
        final Map<String, Integer> restorations = observer.observationCounts();
        assertEquals(2, after.size());
        assertEquals(2, restorations.size());
        assertEquals(1, restorations.get("fixture/RenderTarget"));
        assertEquals(1, restorations.get("fixture/ModelTarget"));

        final Path output = temporary.resolve("run/rollback-manifest.json");
        new PerformanceProbeRollbackWriter().write(
            output,
            ReviewedHostArtifacts.CUBISM_5_3_02_VERSION,
            ARTIFACT_SHA,
            "run-01",
            "on",
            "camera",
            AGENT_SHA,
            FIXTURE_SHA,
            targets,
            owners(before, instrumented, after),
            transformer.matchCounts(),
            dotted(restorations)
        );

        final JsonNode root = JSON.readTree(Files.readAllBytes(output));
        assertEquals("turboism.cubism.performance-probe-rollback", root.path("format").asText());
        assertEquals(1, root.path("schemaVersion").asInt());
        assertEquals("5.3.02", root.path("cubismVersion").asText());
        assertEquals(ARTIFACT_SHA, root.path("artifactSha256").asText());
        assertEquals("run-01", root.path("runId").asText());
        assertEquals("on", root.path("variant").asText());
        assertEquals("camera", root.path("scenario").asText());
        assertEquals(AGENT_SHA, root.path("agentSha256").asText());
        assertEquals(FIXTURE_SHA, root.path("fixtureSha256").asText());

        assertEquals(2, root.path("owners").size());
        for (JsonNode owner : root.path("owners")) {
            assertEquals(1, owner.path("restorationMatches").asInt());
            assertTrue(owner.path("beforeSha256").asText().matches("[0-9a-f]{64}"));
            assertTrue(owner.path("instrumentedSha256").asText().matches("[0-9a-f]{64}"));
            assertTrue(owner.path("afterSha256").asText().matches("[0-9a-f]{64}"));
            assertFalse(owner.path("instrumentedSha256").asText().equals(owner.path("beforeSha256").asText()));
            assertEquals(owner.path("beforeSha256").asText(), owner.path("afterSha256").asText());
        }

        assertEquals(2, root.path("selectors").size());
        for (JsonNode selector : root.path("selectors")) {
            assertEquals(1, selector.path("matches").asInt());
            assertTrue(selector.path("owner").asText().startsWith("fixture."));
            assertTrue(selector.path("metric").asText().matches("[a-z][A-Za-z]*"));
        }

        final Path output5303 = temporary.resolve("run/rollback-manifest-5303.json");
        new PerformanceProbeRollbackWriter().write(
            output5303,
            ReviewedHostArtifacts.CUBISM_5_3_03_VERSION,
            ReviewedHostArtifacts.CUBISM_5_3_03.sha256(),
            "run-5303",
            "on",
            "camera",
            AGENT_SHA,
            FIXTURE_SHA,
            targets,
            owners(before, instrumented, after),
            transformer.matchCounts(),
            dotted(restorations)
        );
        final JsonNode root5303 = JSON.readTree(Files.readAllBytes(output5303));
        assertEquals("5.3.03", root5303.path("cubismVersion").asText());
        assertEquals(
            ReviewedHostArtifacts.CUBISM_5_3_03.sha256(),
            root5303.path("artifactSha256").asText()
        );
    }

    @Test
    void refusesToPublishPartialOrMismatchedEvidence() throws Exception {
        final List<PerformanceProbeMethodTransformer.Target> targets = List.of(
            new PerformanceProbeMethodTransformer.Target(
                "fixture/RenderTarget", "render", "(Z)V", PerformanceProbeMetric.RENDER_SCENE)
        );
        final byte[] original = fixtureClass("fixture/RenderTarget", "render", "(Z)V");
        final PerformanceProbeMethodTransformer transformer =
            new PerformanceProbeMethodTransformer(null, null, targets);
        final byte[] instrumented = transformer.transform(null, null, "fixture/RenderTarget", null, null, original);

        final Map<String, String> before = transformer.beforeSha256();
        final Map<String, String> instrumentedHashes = transformer.instrumentedSha256();
        final PerformanceProbeRollbackObserver observer = new PerformanceProbeRollbackObserver(null, null, targets);
        observer.beginRestoration();
        observer.transform(null, null, "fixture/RenderTarget", null, null, original);

        final PerformanceProbeRollbackWriter writer = new PerformanceProbeRollbackWriter();

        // Restoration mismatch: after differs from before.
        final Map<String, String> corruptedAfter = new LinkedHashMap<>();
        corruptedAfter.put("fixture.RenderTarget", "c".repeat(64));
        final Path mismatch = temporary.resolve("mismatch.json");
        assertThrows(IllegalArgumentException.class, () -> writer.write(
            mismatch, ReviewedHostArtifacts.CUBISM_5_3_02_VERSION, ARTIFACT_SHA,
            "run-01", "on", "camera", AGENT_SHA, FIXTURE_SHA,
            targets, owners(before, instrumentedHashes, corruptedAfter),
            transformer.matchCounts(), dotted(observer.observationCounts())));
        assertFalse(Files.exists(mismatch));

        // Missing restoration observation: no admissible manifest.
        final Path partial = temporary.resolve("partial.json");
        assertThrows(IllegalArgumentException.class, () -> writer.write(
            partial, ReviewedHostArtifacts.CUBISM_5_3_02_VERSION, ARTIFACT_SHA,
            "run-01", "on", "camera", AGENT_SHA, FIXTURE_SHA,
            targets, owners(before, instrumentedHashes, observer.observedSha256()),
            transformer.matchCounts(), Map.of()));
        assertFalse(Files.exists(partial));
        assertTrue(instrumented.length > 0);
    }

    private static Map<String, PerformanceProbeRollbackWriter.OwnerEvidence> owners(
        final Map<String, String> before,
        final Map<String, String> instrumented,
        final Map<String, String> after
    ) {
        final Map<String, PerformanceProbeRollbackWriter.OwnerEvidence> result = new LinkedHashMap<>();
        before.forEach((owner, hash) -> result.put(owner.replace('/', '.'),
            new PerformanceProbeRollbackWriter.OwnerEvidence(
                before.get(owner), instrumented.get(owner), after.get(owner))));
        return result;
    }

    private static Map<String, Integer> dotted(final Map<String, Integer> counts) {
        final Map<String, Integer> result = new LinkedHashMap<>();
        counts.forEach((owner, count) -> result.put(owner.replace('/', '.'), count));
        return result;
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
