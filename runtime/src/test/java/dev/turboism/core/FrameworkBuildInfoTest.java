package dev.turboism.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;
import org.junit.jupiter.api.Test;

final class FrameworkBuildInfoTest {
    private static Properties metadata(String channel, String version, String number) {
        Properties p = new Properties();
        p.setProperty("version", version);
        p.setProperty("channel", channel);
        p.setProperty("buildNumber", number);
        p.setProperty("sourceRevision", "a".repeat(40));
        p.setProperty("buildKind", number.isEmpty() ? "local" : "ci");
        p.setProperty("dirty", "false");
        return p;
    }

    @Test
    void frameworkApiUsesMinimumInclusiveAndMajorOneExclusiveRanges() {
        var api = FrameworkBuildInfo.fromProperties(metadata("nightly", "0.67.0-0.nightly.local-SNAPSHOT", ""))
                .apiVersion();
        assertTrue(
                dev.turboism.core.version.VersionRange.parse("[0.67.0,1.0.0)").contains(api));
        assertFalse(
                dev.turboism.core.version.VersionRange.parse("[0.68.0,1.0.0)").contains(api));
        assertFalse(
                dev.turboism.core.version.VersionRange.parse("[0.1.0,0.2.0)").contains(api));
        assertFalse(dev.turboism.core.version.VersionRange.parse("[0.67.0,1.0.0)")
                .contains(dev.turboism.core.version.PluginVersion.parse("1.0.0")));
    }

    @Test
    void apiVersionStripsEveryReleaseAndLocalSuffix() {
        for (String version : new String[] {
            "0.67.0",
            "0.67.0-0.nightly.32",
            "0.67.0-beta.2",
            "0.67.0-SNAPSHOT",
            "0.67.0-beta.local-SNAPSHOT",
            "0.67.0-0.nightly.local-SNAPSHOT"
        }) {
            Properties p = new Properties();
            p.setProperty("version", version);
            assertEquals(
                    "0.67.0", FrameworkBuildInfo.fromProperties(p).apiVersion().toString());
        }
    }

    @Test
    void rejectsConflictingEmbeddedApiOrImplementationVersion() {
        Properties p = metadata("stable", "0.67.0", "");
        p.setProperty("apiVersion", "0.1.0");
        assertThrows(IllegalArgumentException.class, () -> FrameworkBuildInfo.fromProperties(p));
        p.setProperty("apiVersion", "0.67.0");
        p.setProperty("Implementation-Version", "0.66.0");
        assertThrows(IllegalArgumentException.class, () -> FrameworkBuildInfo.fromProperties(p));
    }

    @Test
    void readsAllThreeChannelsFromThePackageNotTheServer() {
        String[] channels = {"stable", "beta", "nightly"};
        String[] versions = {"1.2.3", "1.2.3-beta.2", "1.2.3-0.nightly.42"};
        for (int i = 0; i < channels.length; i++) {
            var info = FrameworkBuildInfo.fromProperties(metadata(channels[i], versions[i], "42"));
            assertEquals(versions[i], info.version());
            assertEquals(channels[i], info.channel().wireName());
            assertEquals(42, info.buildNumber().orElseThrow());
            assertEquals("a".repeat(40), info.sourceRevision());
            assertTrue(info.displayVersion().contains("Build 42"));
            assertFalse(info.isLocalBuild());
        }
    }

    @Test
    void localPreviewDoesNotInventAnOfficialNumber() {
        var info = FrameworkBuildInfo.fromProperties(metadata("nightly", "1.2.3-0.nightly.local-SNAPSHOT", ""));
        assertTrue(info.isLocalBuild());
        assertTrue(info.buildNumber().isEmpty());
        assertTrue(info.displayVersion().contains("local"));
        assertEquals("nightly", info.channel().wireName());
    }

    @Test
    void oldVersionOnlyResourcesRemainReadableWithoutGuessingBuildNumber() {
        Properties p = new Properties();
        p.setProperty("version", "1.2.3");
        var info = FrameworkBuildInfo.fromProperties(p);
        assertEquals("1.2.3", info.version());
        assertEquals(FrameworkBuildInfo.Channel.STABLE, info.channel());
        assertTrue(info.buildNumber().isEmpty());
        assertEquals("legacy", info.buildKind());
    }

    @Test
    void channelNumberAndSourceConflictsFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FrameworkBuildInfo.fromProperties(metadata("stable", "1.2.3-beta.2", "42")));
        assertThrows(
                IllegalArgumentException.class,
                () -> FrameworkBuildInfo.fromProperties(metadata("nightly", "1.2.3-0.nightly.43", "42")));
        assertThrows(
                IllegalArgumentException.class,
                () -> FrameworkBuildInfo.fromProperties(metadata("beta", "1.2.3-beta.2", "0")));
        Properties p = metadata("beta", "1.2.3-beta.2", "42");
        p.setProperty("sourceRevision", "unknown");
        assertThrows(IllegalArgumentException.class, () -> FrameworkBuildInfo.fromProperties(p));
    }

    @Test
    void readsTheActualGeneratedResource() throws Exception {
        var info = FrameworkBuildInfo.current();
        assertNotEquals("unknown", info.version());
        Properties embedded = new Properties();
        try (var input =
                FrameworkBuildInfo.class.getResourceAsStream("/META-INF/turboism/framework-version.properties")) {
            assertNotNull(input);
            embedded.load(input);
        }
        assertEquals(info.version(), embedded.getProperty("Implementation-Version"));
        assertEquals(info.apiVersion().toString(), embedded.getProperty("apiVersion"));
        String expectedVersion = System.getenv("TURBOISM_BUILD_VERSION");
        String expectedChannel = System.getenv("TURBOISM_BUILD_CHANNEL");
        if (expectedVersion != null && !expectedVersion.isEmpty()) {
            assertTrue(info.version().equals(expectedVersion) || info.version().equals(expectedVersion + "-SNAPSHOT"));
        }
        if (expectedChannel != null && !expectedChannel.isEmpty())
            assertEquals(expectedChannel, info.channel().wireName());
        String number = System.getenv("TURBOISM_BUILD_NUMBER");
        if (number == null || number.isEmpty()) assertTrue(info.buildNumber().isEmpty());
        else assertEquals(Long.parseLong(number), info.buildNumber().orElseThrow());
    }
}
