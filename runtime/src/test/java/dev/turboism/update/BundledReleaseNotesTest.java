package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.FrameworkBuildInfo;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundledReleaseNotesTest {

    @Test
    void loadsLocalizedBundledReleaseNotesForPackagedVersion(@TempDir final Path home) {
        final FrameworkBuildInfo info = new FrameworkBuildInfo(
                "0.70.3", FrameworkBuildInfo.Channel.STABLE, OptionalLong.of(100L), "deadbeef", "release", false);

        final Map<String, String> loaded = new BundledReleaseNotes().load(home, info);

        assertTrue(loaded.containsKey("en"));
        assertTrue(loaded.containsKey("zh"));
        assertTrue(loaded.containsKey("ja"));
        assertTrue(loaded.containsKey("ko"));
    }

    @Test
    void fallsBackToBundledChangelogWhenVersionJsonIsMissing(@TempDir final Path home) {
        final BundledReleaseNotes loader = new BundledReleaseNotes(resourcePath -> {
            if ("/META-INF/turboism/release-notes/CHANGELOG.md".equals(resourcePath)) {
                return BundledReleaseNotes.class.getResourceAsStream(resourcePath);
            }
            return null;
        });
        final FrameworkBuildInfo info = new FrameworkBuildInfo(
                "0.70.0", FrameworkBuildInfo.Channel.STABLE, OptionalLong.empty(), "", "release", false);

        final Map<String, String> loaded = loader.load(home, info);

        assertTrue(loaded.containsKey("en"));
        assertTrue(loaded.get("en").contains("### Added"));
    }

    @Test
    void readsCachedDiscoveryNotesForMatchingPreReleaseBuildAndIgnoresStaleCache(@TempDir final Path home) {
        final UpdateStateStore store = new UpdateStateStore(home, UpdateDiscovery.CHANNEL_BETA, message -> {});
        final UpdateDiscovery ready = new UpdateDiscoveryParser(UpdateDiscovery.CHANNEL_BETA)
                .parse(("{\"schemaVersion\":1,\"channel\":\"beta\",\"status\":\"ready\","
                                + "\"release\":{\"version\":\"0.70.4-beta.1\",\"buildNumber\":205,"
                                + "\"publishedAt\":\"2026-11-24T00:00:00Z\","
                                + "\"notesByLanguage\":{\"en\":\"### Added\\n\\n- Beta diagnostic detail\"}}}")
                        .getBytes(StandardCharsets.UTF_8));
        assertTrue(store.save(new UpdateStateStore.State(Optional.empty(), Optional.of(ready), Optional.empty()))
                .saved());

        final BundledReleaseNotes emptyResources =
                new BundledReleaseNotes(ignored -> new ByteArrayInputStream(new byte[0]));
        final FrameworkBuildInfo matching = new FrameworkBuildInfo(
                "0.70.4-beta.1", FrameworkBuildInfo.Channel.BETA, OptionalLong.of(205L), "", "beta", false);
        final FrameworkBuildInfo mismatchedBuild = new FrameworkBuildInfo(
                "0.70.4-beta.1", FrameworkBuildInfo.Channel.BETA, OptionalLong.of(206L), "", "beta", false);

        assertEquals(Map.of("en", "### Added\n\n- Beta diagnostic detail"), emptyResources.load(home, matching));
        assertEquals(Map.of(), emptyResources.load(home, mismatchedBuild));

        final String rawChangelog = """
                ## [0.70.2] - 2026-11-22

                ### Fixed

                - Sample line.
                """;
        assertEquals(
                Optional.of("### Fixed\n\n- Sample line."),
                BundledReleaseNotes.extractChangelogSection(
                        new String(rawChangelog.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8), "0.70.2"));
    }
}
