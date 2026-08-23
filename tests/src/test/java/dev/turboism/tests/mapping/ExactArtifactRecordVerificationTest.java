package dev.turboism.tests.mapping;

import dev.turboism.mapping.verification.VerifiedBoundingBoxOverlayButtonResolverFactory;
import dev.turboism.mapping.verification.VerifiedEditorModelResolverFactory;
import dev.turboism.mapping.verification.VerifiedEmbeddedPanelResolverFactory;
import dev.turboism.mapping.verification.VerifiedMainToolbarResolverFactory;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.VerifiedProjectWorkspaceResolverFactory;
import dev.turboism.mapping.verification.VerifiedTopMenuResolverFactory;
import dev.turboism.mapping.verification.VerifiedWorkspaceControlResolverFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Runs the complete production resolver workflow (record load, static selector verification
 * against the exact artifact, access-plan admission, host-classloader attestation) for every
 * record packaged by the production bootstrap, against the exact reviewed Cubism artifacts.
 *
 * <p>The exact artifacts are not committed to this repository. The test activates when
 * {@code TURBOISM_EXACT_ARTIFACTS_DIR} (or the {@code turboism.exactArtifactsDir} system
 * property) points at the reviewed reference {@code cubism-ref} root that contains the
 * {@code Cubism-5.2/jars} and {@code Cubism-5.3.02/jars} directories; it is skipped with a
 * documented reason otherwise. Skipping is not readiness: the authoritative exact-host gate
 * remains the reviewed real-host matrix.</p>
 */
class ExactArtifactRecordVerificationTest {

    private static final Path ARTIFACTS_ROOT = Path.of(
        System.getProperty(
            "turboism.exactArtifactsDir",
            System.getenv().getOrDefault("TURBOISM_EXACT_ARTIFACTS_DIR", "")
        )
    );

    private static final Path PROJECT_ROOT = Path.of(
        System.getProperty("projectRoot", System.getProperty("user.dir"))
    );

    private static final Path RECORDS = PROJECT_ROOT.resolve("docs/migration/verification/static");

    private record Slice(String recordFile, ResolverFactory factory) {
    }

    private interface ResolverFactory {
        VerifiedMemberResolver create(Path record, Path artifact, ClassLoader loader) throws IOException;
    }

    private static final Map<String, List<Slice>> PRODUCTION_RECORDS = Map.of(
        "5.2.03", List.of(
            new Slice("cubism-5.2.03-project-workspace.json",
                (r, a, l) -> new VerifiedProjectWorkspaceResolverFactory().create(r, a, l)),
            new Slice("cubism-5.2.03-editor-model.json",
                (r, a, l) -> new VerifiedEditorModelResolverFactory().create(r, a, l)),
            new Slice("cubism-5.2.03-ui-main-toolbar.json",
                (r, a, l) -> new VerifiedMainToolbarResolverFactory().create(r, a, l)),
            new Slice("cubism-5.2.03-ui-embedded-panel.json",
                (r, a, l) -> new VerifiedEmbeddedPanelResolverFactory().create(r, a, l)),
            new Slice("cubism-5.2.03-ui-top-menu.json",
                (r, a, l) -> new VerifiedTopMenuResolverFactory().create(r, a, l)),
            new Slice("cubism-5.2.03-ui-bounding-box-overlay.json",
                (r, a, l) -> new VerifiedBoundingBoxOverlayButtonResolverFactory().create(r, a, l)),
            new Slice("cubism-5.2.03-workspace-control.json",
                (r, a, l) -> new VerifiedWorkspaceControlResolverFactory().create(r, a, l))
        ),
        "5.3.02", List.of(
            new Slice("cubism-5.3.02-project-workspace.json",
                (r, a, l) -> new VerifiedProjectWorkspaceResolverFactory().create(r, a, l)),
            new Slice("cubism-5.3.02-editor-model.json",
                (r, a, l) -> new VerifiedEditorModelResolverFactory().create(r, a, l)),
            new Slice("cubism-5.3.02-ui-main-toolbar.json",
                (r, a, l) -> new VerifiedMainToolbarResolverFactory().create(r, a, l)),
            new Slice("cubism-5.3.02-ui-embedded-panel.json",
                (r, a, l) -> new VerifiedEmbeddedPanelResolverFactory().create(r, a, l)),
            new Slice("cubism-5.3.02-ui-top-menu.json",
                (r, a, l) -> new VerifiedTopMenuResolverFactory().create(r, a, l)),
            new Slice("cubism-5.3.02-ui-bounding-box-overlay.json",
                (r, a, l) -> new VerifiedBoundingBoxOverlayButtonResolverFactory().create(r, a, l)),
            new Slice("cubism-5.3.02-workspace-control.json",
                (r, a, l) -> new VerifiedWorkspaceControlResolverFactory().create(r, a, l))
        )
    );

    @Test
    void everyPackagedProductionRecordVerifiesAgainstTheExactArtifact() throws Exception {
        Assumptions.assumeTrue(
            Files.isDirectory(ARTIFACTS_ROOT),
            "TURBOISM_EXACT_ARTIFACTS_DIR is not set to the reviewed cubism-ref root; "
                + "exact-artifact record verification skipped (not a readiness gate)."
        );
        for (Map.Entry<String, List<Slice>> profile : PRODUCTION_RECORDS.entrySet()) {
            final String profileId = profile.getKey();
            final Path jars = ARTIFACTS_ROOT.resolve("Cubism-" + profileId).resolve("jars");
            Assumptions.assumeTrue(
                Files.isDirectory(jars),
                "Exact artifact jars directory is missing for profile " + profileId
            );
            final Path artifact = jars.resolve("Live2D_Cubism.jar");
            Assumptions.assumeTrue(Files.isRegularFile(artifact), "Exact artifact is missing for profile " + profileId);
            try (URLClassLoader hostLoader = hostClassLoader(jars)) {
                for (Slice slice : profile.getValue()) {
                    final Path record = RECORDS.resolve(slice.recordFile());
                    final VerifiedMemberResolver resolver = slice.factory()
                        .create(record, artifact, hostLoader);
                    assertNotNull(resolver, "resolver must be created for " + slice.recordFile());
                }
            }
        }
    }

    private static URLClassLoader hostClassLoader(final Path jars) throws IOException {
        try (Stream<Path> paths = Files.list(jars)) {
            final List<Path> jarPaths = paths
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .sorted(Comparator.comparing(path -> {
                    // The reviewed Live2D_Cubism.jar must win owner resolution: the 5.3.02
                    // directory also ships Live2D_Cubism_ANGLE.jar, an alternate binary that
                    // duplicates the same com.live2d classes. The production app classpath
                    // loads from the reviewed artifact, and attestation requires the defining
                    // code source to equal it.
                    return path.getFileName().toString().equals("Live2D_Cubism.jar") ? 0 : 1;
                }))
                .toList();
            final URL[] urls = new URL[jarPaths.size()];
            for (int index = 0; index < jarPaths.size(); index++) {
                urls[index] = jarPaths.get(index).toUri().toURL();
            }
            // Isolated (parent=null) host classloader: the test classpath contains fake host
            // classes (testframework), and parent-first delegation would resolve com.live2d
            // owners to the fake definitions and fail code-source attestation. Production uses
            // the real Cubism app classloader, which has no such shadowing.
            return new URLClassLoader(urls, null);
        }
    }
}
