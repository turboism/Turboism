package dev.turboism.mapping.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Generic host-compatibility probe: resolves one Editor artifact (plus an
 * optional Core artifact) and prints a versioned JSON verdict for installers,
 * caches and diagnostics.
 *
 * <p>Unlike {@link ReviewedHostArtifactCli} — which keeps its exact-sample
 * semantics — this entry point reports the full admission picture: the
 * host's own declared identity, the exact-artifact verdict as a separate
 * dimension, the per-slice contract outcome with bounded reasons, and the
 * statically eligible capability set. Classloader attestation and runtime Hook
 * installation still run before plugins can use those capabilities.</p>
 *
 * <p>The probe is an offline precheck: the runtime classloader and hook
 * contract checks can still degrade further. Exit codes are 0 for an
 * admitted host ({@code VERIFIED} or {@code COMPATIBLE}), 1 when identity or
 * base runtime admission failed, and 2 for invalid input.</p>
 */
public final class CubismHostProbeCli {

    /** JSON schema version; bump on any incompatible field change. */
    public static final int SCHEMA_VERSION = 2;

    /**
     * @param artifact path to {@code Live2D_Cubism.jar}
     * @param coreArtifact optional path to {@code Live2D_CubismCore.jar}
     * @return the versioned JSON verdict document
     * @throws IOException if the artifact cannot be read
     */
    public static ObjectNode probe(final Path artifact, final Path coreArtifact) throws IOException {
        final CompatibilityResolution resolution = CubismHostCompatibilityResolver.resolve(artifact, coreArtifact);
        return render(resolution);
    }

    /** Renders a resolution as the schema-versioned JSON document. */
    static ObjectNode render(final CompatibilityResolution resolution) {
        final ObjectMapper mapper = new ObjectMapper();
        final ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("evidenceStage", "STATIC_PREFLIGHT");
        root.put("runtimeHooksVerified", false);
        root.put("probe", "cubism-host-compatibility");
        root.put("status", resolution.mode().name());
        root.put("reason", resolution.detail());
        root.put("runtimeAdmitted", resolution.runtimeAdmitted());
        // Identity provenance, separate from each hook's runtime target proof.
        root.put("declaredGenerationBound", resolution.declaredGenerationBound());

        resolution.identity().ifPresent(identity -> {
            final ObjectNode node = root.putObject("identity");
            node.put("product", identity.product());
            node.put("version", identity.version());
            identity.date().ifPresent(date -> node.put("date", date));
            node.put("build", identity.build());
            node.put("releaseReviewed", ReviewedCubismReleases.isReviewed(identity.version(), identity.build()));
            node.put("declarationClass", identity.declarationClass());
            final ObjectNode artifact = node.putObject("artifact");
            artifact.put("size", identity.artifact().size());
            artifact.put("sha256", identity.artifact().sha256());
            // Exact artifact identity is a separate dimension from version
            // admission: a non-review-pinned artifact still reports its true
            // declared version while artifactReviewed stays false.
            node.put(
                    "artifactReviewed",
                    ReviewedHostArtifacts.cubismVersionOf(identity.artifact()).isPresent());
        });
        if (resolution.identity().isEmpty()) {
            root.putObject("identity");
            root.put("probeStatus", resolution.identityProbe().status().name());
            root.put("probeDetail", resolution.identityProbe().detail());
        }

        final ArrayNode slices = root.putArray("slices");
        for (final Map.Entry<String, CompatibilityResolution.SliceResolution> entry :
                resolution.slices().entrySet()) {
            final CompatibilityResolution.SliceResolution slice = entry.getValue();
            final ObjectNode node = slices.addObject();
            node.put("sliceId", slice.sliceId());
            node.put("status", slice.status().name());
            node.put("reason", slice.detail());
            slice.matchedCandidates().forEach(node.putArray("matchedCandidates")::add);
            slice.contract().ifPresent(contract -> {
                final ObjectNode bound = node.putObject("contract");
                bound.put("sourceVersion", contract.sourceVersion());
                bound.put("recordFileName", contract.recordFileName());
                bound.put("recordSha256", contract.recordSha256());
                bound.put("verificationId", contract.verificationId());
                bound.put("compatible", contract.compatible());
                bound.put("declaredGenerationBound", contract.declaredGenerationBound());
                contract.capabilities().forEach(bound.putArray("capabilities")::add);
                if (!contract.droppedCapabilities().isEmpty()) {
                    final ObjectNode dropped = bound.putObject("droppedCapabilities");
                    contract.droppedCapabilities().forEach(dropped::put);
                }
            });
        }

        final ArrayNode capabilities = root.putArray("admittedCapabilities");
        resolution.admittedCapabilityIds().stream().sorted().forEach(capabilities::add);
        return root;
    }

    /**
     * Usage: {@code CubismHostProbeCli <Live2D_Cubism.jar> [Live2D_CubismCore.jar]}.
     * Prints the JSON verdict on stdout.
     */
    public static void main(final String[] arguments) {
        if (arguments.length < 1 || arguments.length > 2) {
            System.err.println("usage: CubismHostProbeCli <Live2D_Cubism.jar> [Live2D_CubismCore.jar]");
            System.exit(2);
        }
        try {
            final Path core = arguments.length == 2 ? Path.of(arguments[1]) : null;
            final ObjectNode report = probe(Path.of(arguments[0]), core);
            System.out.println(report.toPrettyString());
            System.exit(report.path("runtimeAdmitted").asBoolean(false) ? 0 : 1);
        } catch (Exception failure) {
            System.err.println(failure.getClass().getSimpleName() + ": " + failure.getMessage());
            System.exit(2);
        }
    }

    private CubismHostProbeCli() {}
}
