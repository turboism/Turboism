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

    @Test
    void mergesMultiVersionRangeFromBundledStructuredChangelog(@TempDir final Path home) {
        final FrameworkBuildInfo info = new FrameworkBuildInfo(
                "0.70.3", FrameworkBuildInfo.Channel.STABLE, OptionalLong.of(100L), "deadbeef", "release", false);

        final Map<String, String> range = new BundledReleaseNotes().load(home, info, Optional.of("0.70.1"));

        assertTrue(range.containsKey("en"));
        assertTrue(range.containsKey("zh"));
        assertTrue(range.get("en").contains("0.70.1"));
        assertTrue(range.get("en").contains("Cubism Editor 5.3.05"), "must include 0.70.2 changes");
        assertTrue(range.get("en").contains("Warp Row/Column Deform"), "must include 0.70.3 changes");
        assertTrue(range.get("zh").contains("compare/v0.70.1...v0.70.3"));

        final Map<String, String> singleStep = new BundledReleaseNotes().load(home, info, Optional.of("0.70.2"));
        assertTrue(singleStep.get("en").contains("Changes from **0.70.2** to **0.70.3**."));
        assertTrue(singleStep.get("zh").contains("从 **0.70.2** 到 **0.70.3** 的变更。"));
    }

    @Test
    void loadsStructuredChannelTransitionsForBetaToBetaBetaToStableAndStableToBeta(@TempDir final Path home) {
        final String structured = """
                {
                  "format": "turboism.changelog/v1",
                  "schemaVersion": 1,
                  "repository": "https://github.com/turboism/Turboism",
                  "locales": ["en", "zh", "ja", "ko"],
                  "categories": ["added", "changed", "deprecated", "removed", "fixed", "security", "known_limitations"],
                  "unreleased": {"sections": {}},
                  "releases": [
                    {
                      "version": "0.71.0",
                      "channel": "stable",
                      "date": "2026-11-26",
                      "previousVersion": "0.71.0-beta.5",
                      "sections": {"fixed": [{"en": "Stable polish.", "zh": "正式版打磨。"}]}
                    },
                    {
                      "version": "0.71.0-beta.5",
                      "channel": "beta",
                      "date": "2026-11-25",
                      "previousVersion": "0.71.0-beta.4",
                      "sections": {"added": [{"en": "Beta 5 feature.", "zh": "Beta 5 新功能。"}]}
                    },
                    {
                      "version": "0.71.0-beta.4",
                      "channel": "beta",
                      "date": "2026-11-24",
                      "previousVersion": "0.70.3",
                      "sections": {"added": [{"en": "Beta 4 feature.", "zh": "Beta 4 新功能。"}]}
                    },
                    {
                      "version": "0.70.3",
                      "channel": "stable",
                      "date": "2026-11-23",
                      "previousVersion": null,
                      "sections": {"added": [{"en": "Stable 0.70.3.", "zh": "正式版 0.70.3。"}]}
                    }
                  ]
                }
                """;
        final BundledReleaseNotes loader = new BundledReleaseNotes(resourcePath -> {
            if ("/META-INF/turboism/release-notes/changelog.json".equals(resourcePath)) {
                return new ByteArrayInputStream(structured.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        final FrameworkBuildInfo beta5 = new FrameworkBuildInfo(
                "0.71.0-beta.5", FrameworkBuildInfo.Channel.BETA, OptionalLong.of(205L), "", "beta", false);
        final Map<String, String> b2b = loader.load(home, beta5, Optional.of("0.71.0-beta.4"));
        assertTrue(b2b.get("zh").contains("Beta 5 新功能。"));
        assertTrue(!b2b.get("zh").contains("Beta 4 新功能。"));

        final Map<String, String> s2b = loader.load(home, beta5, Optional.of("0.70.3"));
        assertTrue(s2b.get("zh").contains("Beta 4 新功能。"));
        assertTrue(s2b.get("zh").contains("Beta 5 新功能。"));

        final FrameworkBuildInfo stable = new FrameworkBuildInfo(
                "0.71.0", FrameworkBuildInfo.Channel.STABLE, OptionalLong.of(206L), "", "release", false);
        final Map<String, String> b2s = loader.load(home, stable, Optional.of("0.71.0-beta.4"));
        assertTrue(b2s.get("zh").contains("Beta 5 新功能。"));
        assertTrue(b2s.get("zh").contains("正式版打磨。"));
        assertTrue(!b2s.get("zh").contains("Beta 4 新功能。"));
    }
}
