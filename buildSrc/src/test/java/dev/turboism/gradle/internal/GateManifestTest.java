package dev.turboism.gradle.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The manifest grammar, exercised against fixture manifests.
 *
 * <p>The negative cases are the point of the reader: a manifest that half-parses must not shrink a
 * gate, so every malformed line is reported at once instead of the first one found.
 */
class GateManifestTest {

    private static final String PACKAGING = "aggregate packaging group=\"release verification\" "
            + "owner=\".github/workflows/release.yml:154\" harm=\"an unsigned installer reaches a user\" "
            + "description=\"Release delta.\"";

    private static final String VIA_OWNER = "owner=\"via:devCheck\"";

    private static final String OWNER = "owner=\".github/workflows/release.yml:154\"";

    private static final String DEV_CHECK = "aggregate devCheck group=\"verification\" "
            + "owner=\".github/workflows/ci.yml:76\" harm=\"a boundary regression reaches main\" "
            + "description=\"Fast slice gate.\" require-xvfb";

    @TempDir
    Path root;

    @Test
    void readsAggregatesMembersAndRoutes() throws IOException {
        GateManifest manifest = load(Set.of("@all-module-tests"), """
                # A comment, and blank lines, are free.

                %s
                member devCheck checkPluginBoundaries  # a trailing note
                member devCheck @all-module-tests
                member devCheck :runtime:admissionUnitTests
                %s
                member packaging devCheck
                manual checkGraalScriptHostValidation reason="launches a real host" doc="graal-host/build.gradle.kts"
                opt-in verifySelectionBrushJar reason="module check anchor"
                orphan checkGraalHostTargetSelection reason="runs nowhere"
                """.formatted(DEV_CHECK, PACKAGING.replace(OWNER, VIA_OWNER)));

        assertEquals(List.of("devCheck", "packaging"), manifest.aggregateNames());
        GateManifest.Aggregate devCheck = manifest.aggregate("devCheck");
        assertEquals("verification", devCheck.group());
        assertEquals("Fast slice gate.", devCheck.description());
        assertEquals(".github/workflows/ci.yml:76", devCheck.owner());
        assertEquals("a boundary regression reaches main", devCheck.harm());
        assertTrue(devCheck.requireXvfb());
        assertFalse(devCheck.releaseFlags());
        // Declaration order is the registration order, so it must survive loading unchanged.
        assertEquals(
                List.of("checkPluginBoundaries", "@all-module-tests", ":runtime:admissionUnitTests"),
                devCheck.members());
        GateManifest.Aggregate packaging = manifest.aggregate("packaging");
        assertEquals("release verification", packaging.group());
        assertEquals("via:devCheck", packaging.owner());
        assertFalse(packaging.releaseFlags());
        assertEquals(List.of("devCheck"), packaging.members());

        assertEquals(
                List.of("checkGraalScriptHostValidation", "verifySelectionBrushJar", "checkGraalHostTargetSelection"),
                manifest.routes().stream().map(GateManifest.Route::taskName).toList());
        assertEquals(GateManifest.Kind.MANUAL, route(manifest, "checkGraalScriptHostValidation").kind());
        assertEquals(GateManifest.Kind.OPT_IN, route(manifest, "verifySelectionBrushJar").kind());
        assertEquals(GateManifest.Kind.ORPHAN, route(manifest, "checkGraalHostTargetSelection").kind());
        assertEquals(
                "graal-host/build.gradle.kts", route(manifest, "checkGraalScriptHostValidation").documentation());
        assertEquals("", route(manifest, "verifySelectionBrushJar").documentation());
        assertTrue(manifest.isRouted("checkGraalHostTargetSelection"));
        assertFalse(manifest.isRouted("checkPluginBoundaries"));
    }

    @Test
    void readsTheReleaseFlagsOfAnAggregate() throws IOException {
        GateManifest manifest = load(Set.of(), PACKAGING + " release-flags require-xvfb\n");

        assertTrue(manifest.aggregate("packaging").releaseFlags());
        assertTrue(manifest.aggregate("packaging").requireXvfb());
    }

    @Test
    void rejectsMemberOfAnUndeclaredAggregate() {
        GateManifest.GateManifestException failure =
                assertMalformed(Set.of(), PACKAGING + "\nmember devCheck checkPluginBoundaries\n");

        assertTrue(
                failure.getMessage().contains("belongs to undeclared aggregate \"devCheck\""), failure.getMessage());
    }

    @Test
    void rejectsADuplicateAggregate() {
        GateManifest.GateManifestException failure = assertMalformed(Set.of(), PACKAGING + "\n" + PACKAGING + "\n");

        assertTrue(failure.getMessage().contains("is already declared at"), failure.getMessage());
    }

    @Test
    void rejectsAnAggregateWithoutHarm() {
        GateManifest.GateManifestException failure = assertMalformed(
                Set.of(), PACKAGING.replace("harm=\"an unsigned installer reaches a user\" ", "") + "\n");

        assertTrue(failure.getMessage().contains("is missing harm="), failure.getMessage());
    }

    @Test
    void rejectsAnAggregateWithoutOwner() {
        GateManifest.GateManifestException failure = assertMalformed(
                Set.of(), PACKAGING.replace(OWNER + " ", "") + "\n");

        assertTrue(failure.getMessage().contains("is missing owner="), failure.getMessage());
    }

    @Test
    void rejectsAnUnboundToken() {
        GateManifest.GateManifestException failure = assertMalformed(
                Set.of("@all-module-tests"), PACKAGING + "\nmember packaging @all-module-spotless\n");

        assertTrue(failure.getMessage().contains("\"@all-module-spotless\" is not bound"), failure.getMessage());
    }

    @Test
    void rejectsABoundTokenNoMemberNames() {
        GateManifest.GateManifestException failure = assertMalformed(Set.of("@all-module-tests"), PACKAGING + "\n");

        assertTrue(
                failure.getMessage().contains("\"@all-module-tests\" is bound but no member names it"),
                failure.getMessage());
    }

    @Test
    void rejectsARouteWithoutAReason() {
        GateManifest.GateManifestException failure = assertMalformed(
                Set.of(), PACKAGING + "\nmanual checkGraalScriptHostValidation doc=\"x.md\"\n");

        assertTrue(failure.getMessage().contains("is missing reason="), failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "aggregate packaging group=\"g\" owner=\".github/workflows/release.yml:1\" harm=\"h\"",
                "aggregate packaging group=\"g\" owner=\".github/workflows/release.yml:1\" description=\"d\"",
                "aggregate packaging group=\"g\" harm=\"h\" description=\"d\"",
                "aggregate packaging owner=\".github/workflows/release.yml:1\" harm=\"h\" description=\"d\"",
            })
    void rejectsEveryMissingAggregateAttribute(String line) {
        GateManifest.GateManifestException failure = assertMalformed(Set.of(), line + "\n");

        assertTrue(failure.getMessage().contains("is missing "), failure.getMessage());
    }

    @Test
    void rejectsOwnerThatNamesNoWorkflowLine() {
        GateManifest.GateManifestException failure =
                assertMalformed(Set.of(), PACKAGING.replace("release.yml:154", "release.yml") + "\n");

        assertTrue(failure.getMessage().contains("must be \"<workflow>:<line>\""), failure.getMessage());
    }

    @Test
    void rejectsViaOwnerThatNamesNoDeclaredAggregate() {
        GateManifest.GateManifestException failure =
                assertMalformed(Set.of(), PACKAGING.replace(OWNER, VIA_OWNER) + "\n");

        assertTrue(failure.getMessage().contains("names no declared aggregate"), failure.getMessage());
    }

    @Test
    void rejectsAnUnknownDirective() {
        GateManifest.GateManifestException failure = assertMalformed(Set.of(), PACKAGING + "\naggregates other\n");

        assertTrue(failure.getMessage().contains("unknown directive \"aggregates\""), failure.getMessage());
    }

    @Test
    void rejectsAnUnknownFlagAndAnUnknownAttribute() {
        GateManifest.GateManifestException flag =
                assertMalformed(Set.of(), PACKAGING + " release-flag\n" + "\n");

        assertTrue(flag.getMessage().contains("unknown flag \"release-flag\""), flag.getMessage());

        GateManifest.GateManifestException attribute = assertMalformed(Set.of(), PACKAGING + " harm2=\"h\"\n");

        assertTrue(attribute.getMessage().contains("unknown attribute \"harm2\""), attribute.getMessage());
    }

    @Test
    void rejectsARoutedTaskThatIsAlsoAMember() {
        GateManifest.GateManifestException failure = assertMalformed(Set.of(), PACKAGING
                + "\nmember packaging checkInstallerVersion"
                + "\norphan checkInstallerVersion reason=\"runs nowhere\"\n");

        assertTrue(failure.getMessage().contains("is also an aggregate member"), failure.getMessage());
    }

    @Test
    void reportsEveryMalformedLineAtOnce() {
        GateManifest.GateManifestException failure = assertMalformed(Set.of(), PACKAGING
                + "\nmember devCheck checkPluginBoundaries"
                + "\n" + PACKAGING
                + "\nmanual checkJavaInstaller doc=\".github/workflows/macos-packaging-verification.yml:47\""
                + "\nnonsense line\n");

        String message = failure.getMessage();
        for (String expected : List.of(
                "is already declared at",
                "belongs to undeclared aggregate \"devCheck\"",
                "is missing reason=",
                "unknown directive \"nonsense\"")) {
            assertTrue(message.contains(expected), () -> "missing \"" + expected + "\": " + message);
        }
    }

    @Test
    void rejectsAnUnreadableManifest() {
        GateManifest.GateManifestException failure = assertThrows(
                GateManifest.GateManifestException.class,
                () -> GateManifest.load(root.resolve("absent/gates.txt"), Set.of()));

        assertTrue(failure.getMessage().contains("Cannot read gate manifest"), failure.getMessage());
    }

    @Test
    void rejectsAnUndeclaredAggregateLookup() throws IOException {
        GateManifest manifest = load(Set.of(), PACKAGING + "\n");

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> manifest.aggregate("devCheck"));

        assertTrue(failure.getMessage().contains("is not declared"), failure.getMessage());
        assertTrue(failure.getMessage().contains("packaging"), failure.getMessage());
    }

    private GateManifest load(Set<String> boundTokens, String text) throws IOException {
        Path file = root.resolve("gates.txt");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return GateManifest.load(file, boundTokens);
    }

    private GateManifest.GateManifestException assertMalformed(Set<String> boundTokens, String text) {
        try {
            load(boundTokens, text);
        } catch (GateManifest.GateManifestException failure) {
            assertTrue(failure.getMessage().contains("is malformed"), failure.getMessage());
            return failure;
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
        throw new AssertionError("malformed manifest accepted: " + text);
    }

    private static GateManifest.Route route(GateManifest manifest, String taskName) {
        return manifest.routes().stream()
                .filter(route -> route.taskName().equals(taskName))
                .findFirst()
                .orElseThrow();
    }
}
