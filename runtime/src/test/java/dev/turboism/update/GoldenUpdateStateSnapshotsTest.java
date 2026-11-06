package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Replays the release-derived {@code installed-build} and {@code update-journal} fixtures through
 * the production readers.
 *
 * <p>These families live beside package-private readers ({@link FrameworkUpdateApplier},
 * {@link FrameworkUpdateHelper}, {@link FrameworkUpdateLaunchLease}), so their replay has to live in
 * {@code dev.turboism.update}. {@code PersistenceGoldenSnapshotsTest} owns the families whose
 * readers are reachable from {@code dev.turboism.config} and asserts that every other family has a
 * replay here; together the two suites cover the whole manifest.</p>
 *
 * <p>Nothing is re-encoded or re-signed. The bytes handed to the readers are exactly the committed
 * fixture bytes, and every expectation asserts that the released document survived the read.</p>
 */
class GoldenUpdateStateSnapshotsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURES =
            Path.of(System.getProperty("turboism.goldenSnapshotRoot", "tests/fixtures/snapshots"));
    private static final Set<String> FAMILIES = Set.of("installed-build", "update-journal");
    private static final String PROBE_SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final Pattern TRAILING_NUMBER = Pattern.compile("^(.*?)([0-9]+)$");

    @TempDir
    Path temporary;

    @TestFactory
    Stream<DynamicTest> allRegisteredUpdateStateSnapshots() throws Exception {
        final JsonNode cases = JSON.readTree(Files.readAllBytes(FIXTURES.resolve("manifest.json")))
                .path("snapshots");
        final List<JsonNode> fixtures = StreamSupport.stream(cases.spliterator(), false)
                .filter(fixture -> FAMILIES.contains(fixture.path("family").asText()))
                .toList();
        for (final String family : FAMILIES) {
            assertTrue(
                    fixtures.stream()
                                    .filter(fixture ->
                                            family.equals(fixture.path("family").asText()))
                                    .count()
                            >= 2,
                    "the " + family + " golden matrix must never silently shrink");
        }
        return fixtures.stream().map(fixture -> dynamicTest(fixture.path("id").asText(), () -> verify(fixture)));
    }

    private void verify(final JsonNode fixture) throws Exception {
        final byte[] original =
                Files.readAllBytes(FIXTURES.resolve(fixture.path("path").asText()));
        switch (fixture.path("expectation").asText()) {
            case "installer-seed-identifies-full-build", "runtime-writer-identity-identifies-full-build" ->
                verifyInstalledBuild(original);
            case "applying-journal-validates-and-refuses-foreign-root" -> verifyUpdateJournal(original);
            case "launch-lease-entry-reads-as-launching" -> verifyLaunchLease(fixture, original);
            default ->
                throw new AssertionError("Unrecognized golden fixture expectation: "
                        + fixture.path("expectation").asText());
        }
    }

    /**
     * The two released writers of {@code installed-build.json} disagree on the field set, but both
     * record the variant and the version. {@link InstalledBuild#inferVariant} and the helper's
     * policy check are the production readers of exactly those two fields.
     */
    private void verifyInstalledBuild(final byte[] original) throws Exception {
        final Path home = Files.createTempDirectory(temporary, "golden-build-");
        final Path file = home.resolve(InstalledBuild.METADATA_FILE_NAME);
        Files.write(file, original);

        assertEquals(
                "full",
                InstalledBuild.inferVariant(home),
                "the released variant field must be read as a full installation");

        final String installed = JSON.readTree(original).path("version").asText();
        assertFalse(installed.isBlank(), "a released installed-build.json always records its version");
        // The helper compares the numeric core of an installed version against a canonical target,
        // so a beta channel installation is still compared through the production parser.
        final String numeric = UpdateVersion.parseInstalled(installed).toString();
        final String channel = channelOf(installed);
        final FrameworkUpdateHelper.Invocation invocation = new FrameworkUpdateHelper.Invocation(
                home, null, null, Optional.empty(), 0L, true, false, Optional.empty(), false, null, false);

        assertNotNull(
                FrameworkUpdateHelper.policyProblem(invocation, probeManifest(numeric, channel)),
                "the recorded version must count as the installed version, so an equal target is not newer");
        assertNull(
                FrameworkUpdateHelper.policyProblem(invocation, probeManifest(nextVersion(numeric), channel)),
                "a newer target must still be accepted for the recorded version");
        assertArrayEquals(original, Files.readAllBytes(file), "reading installed-build.json must not rewrite it");
    }

    /**
     * The journal left by an interrupted release apply must validate exactly as {@code recover}
     * validates it, and must never be applied to another installation.
     */
    private void verifyUpdateJournal(final byte[] original) throws Exception {
        final Path work = Files.createTempDirectory(temporary, "golden-work-");
        final Path journal = work.resolve(FrameworkUpdateApplier.JOURNAL);
        Files.write(journal, original);

        final FrameworkUpdateApplier.JournalSummary summary =
                FrameworkUpdateApplier.inspectJournal(work).orElseThrow();
        assertEquals("APPLYING", summary.state());
        assertEquals("0.68.1-beta.6", summary.version());
        assertEquals(2, summary.files());
        assertArrayEquals(original, Files.readAllBytes(journal), "reading a released journal must not rewrite it");

        final Path otherInstallation = Files.createTempDirectory(temporary, "golden-root-");
        final IOException refusal = assertThrows(
                IOException.class,
                () -> new FrameworkUpdateApplier(otherInstallation, work).recover(),
                "a journal recorded for another installation must be refused, never rolled back here");
        assertTrue(
                refusal.getMessage().contains("belongs to a different installation"),
                "unexpected refusal: " + refusal.getMessage());
        assertArrayEquals(original, Files.readAllBytes(journal), "a refused journal must survive byte for byte");

        final byte[] unsupportedState = new String(original, StandardCharsets.UTF_8)
                .replace("\"APPLYING\"", "\"FUTURE_STATE\"")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(journal, unsupportedState);
        assertThrows(
                IOException.class,
                () -> FrameworkUpdateApplier.inspectJournal(work),
                "an unsupported journal state must be refused instead of guessed at");
    }

    /** The lease entry is read-only state: it must parse as a launch holder and never be rewritten. */
    private void verifyLaunchLease(final JsonNode fixture, final byte[] original) throws Exception {
        final Path work = Files.createTempDirectory(temporary, "golden-lease-");
        final Path entry = work.resolve(fixture.path("documentPath").asText());
        Files.createDirectories(entry.getParent());
        Files.write(entry, original);

        final List<FrameworkUpdateLaunchLease.Entry> entries = FrameworkUpdateLaunchLease.readAll(work);
        assertEquals(1, entries.size(), "the released lease entry must be readable");
        final FrameworkUpdateLaunchLease.Entry lease = entries.get(0);
        assertEquals(FrameworkUpdateLaunchLease.Kind.LAUNCHING, lease.kind());
        assertEquals(4242L, lease.pid());
        assertEquals("2026-11-05T00:00:00Z", lease.started());
        assertEquals("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff", lease.token());
        assertEquals(
                "lease-" + lease.token() + ".json",
                lease.file().getFileName().toString(),
                "a lease entry is identified by its token");
        assertTrue(
                lease.root().startsWith("/tmp/turboism-golden-corpus/"),
                "the fixture must never describe a real installation: " + lease.root());
        assertArrayEquals(original, Files.readAllBytes(entry), "reading a lease entry must not rewrite it");
    }

    private static String channelOf(final String version) {
        return version.contains("-beta.") ? "beta" : "stable";
    }

    private static String nextVersion(final String version) {
        final Matcher matcher = TRAILING_NUMBER.matcher(version);
        assertTrue(matcher.matches(), "not a bumpable version: " + version);
        return matcher.group(1) + (Long.parseLong(matcher.group(2)) + 1L);
    }

    private static UpdateManifest probeManifest(final String version, final String channel) {
        return new UpdateManifest(
                1,
                "test-key-reproducible",
                version,
                channel,
                80L,
                "0123456789abcdef0123456789abcdef01234567",
                "0.42.0",
                "windows-x64",
                List.of(new UpdateManifestEntry("turboism-agent.jar", 1L, PROBE_SHA, "sha256:" + PROBE_SHA)));
    }
}
