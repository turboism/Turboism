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

    @Test void readsAllThreeChannelsFromThePackageNotTheServer() {
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

    @Test void localPreviewDoesNotInventAnOfficialNumber() {
        var info = FrameworkBuildInfo.fromProperties(metadata("nightly", "1.2.3-0.nightly.local-SNAPSHOT", ""));
        assertTrue(info.isLocalBuild());
        assertTrue(info.buildNumber().isEmpty());
        assertTrue(info.displayVersion().contains("local"));
        assertEquals("nightly", info.channel().wireName());
    }

    @Test void oldVersionOnlyResourcesRemainReadableWithoutGuessingBuildNumber() {
        Properties p = new Properties(); p.setProperty("version", "1.2.3");
        var info = FrameworkBuildInfo.fromProperties(p);
        assertEquals("1.2.3", info.version());
        assertEquals(FrameworkBuildInfo.Channel.STABLE, info.channel());
        assertTrue(info.buildNumber().isEmpty());
        assertEquals("legacy", info.buildKind());
    }

    @Test void channelNumberAndSourceConflictsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> FrameworkBuildInfo.fromProperties(metadata("stable", "1.2.3-beta.2", "42")));
        assertThrows(IllegalArgumentException.class, () -> FrameworkBuildInfo.fromProperties(metadata("nightly", "1.2.3-0.nightly.43", "42")));
        assertThrows(IllegalArgumentException.class, () -> FrameworkBuildInfo.fromProperties(metadata("beta", "1.2.3-beta.2", "0")));
        Properties p = metadata("beta", "1.2.3-beta.2", "42"); p.setProperty("sourceRevision", "unknown");
        assertThrows(IllegalArgumentException.class, () -> FrameworkBuildInfo.fromProperties(p));
    }

    @Test void readsTheActualGeneratedResource() {
        var info = FrameworkBuildInfo.current();
        assertNotEquals("unknown", info.version());
        String expectedVersion = System.getenv("TURBOISM_BUILD_VERSION");
        String expectedChannel = System.getenv("TURBOISM_BUILD_CHANNEL");
        if (expectedVersion != null && !expectedVersion.isEmpty()) {
            assertTrue(info.version().equals(expectedVersion) || info.version().equals(expectedVersion + "-SNAPSHOT"));
        }
        if (expectedChannel != null && !expectedChannel.isEmpty()) assertEquals(expectedChannel, info.channel().wireName());
        String number = System.getenv("TURBOISM_BUILD_NUMBER");
        if (number == null || number.isEmpty()) assertTrue(info.buildNumber().isEmpty());
        else assertEquals(Long.parseLong(number), info.buildNumber().orElseThrow());
    }
}
