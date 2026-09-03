package dev.turboism.plugin.turboismwithfx;

import dev.turboism.sdk.plugin.PluginPaths;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class FxRuntimeResolverTest {

    @TempDir
    Path home;

    @Test
    void blankOverrideSelectsManagedRuntimeAndFailsClosedWhenMissing() {
        final FxRuntimeResolver resolver = new FxRuntimeResolver(
            paths(),
            () -> FxRuntimePlatform.detect("Linux", "amd64")
        );

        final FxRuntimeResolver.Resolution.Unavailable unavailable = assertInstanceOf(
            FxRuntimeResolver.Resolution.Unavailable.class,
            resolver.resolve("")
        );
        assertEquals(FxRuntimeResolver.Problem.RUNTIME_MISSING, unavailable.problem());
        assertEquals(
            home.resolve("runtimes/fx").toAbsolutePath().normalize(),
            resolver.managedRoot()
        );
    }

    @Test
    void managedRuntimeRejectsSymlinkedAncestors() throws Exception {
        final Path outside = home.resolve("outside-runtime");
        final Path outsidePlatform = outside.resolve("fx/0.0.5/linux-x86_64");
        Files.createDirectories(outsidePlatform);
        Files.writeString(outsidePlatform.resolve("fx"), "fixture");
        Files.createSymbolicLink(home.resolve("runtimes"), outside);
        final FxRuntimeResolver resolver = new FxRuntimeResolver(
            paths(),
            () -> FxRuntimePlatform.detect("Linux", "amd64")
        );

        final FxRuntimeResolver.Resolution.Unavailable unavailable = assertInstanceOf(
            FxRuntimeResolver.Resolution.Unavailable.class,
            resolver.resolve("")
        );
        assertEquals(FxRuntimeResolver.Problem.RUNTIME_INVALID, unavailable.problem());
    }

    @Test
    void advancedOverrideUsesAnExistingAbsoluteRegularFile() throws Exception {
        final FxRuntimeResolver resolver = new FxRuntimeResolver(
            paths(),
            Optional::empty
        );
        final Path executable = Files.writeString(home.resolve("fx-custom"), "fixture");

        final FxRuntimeResolver.Resolution.Available available = assertInstanceOf(
            FxRuntimeResolver.Resolution.Available.class,
            resolver.resolve("  " + executable + "  ")
        );
        assertEquals(executable.toString(), available.executable());
        assertEquals(FxRuntimeResolver.Source.CUSTOM, available.source());
    }

    @Test
    void advancedOverrideNeverFallsBackToPathLookup() {
        final FxRuntimeResolver resolver = new FxRuntimeResolver(
            paths(),
            Optional::empty
        );

        final FxRuntimeResolver.Resolution.Unavailable unavailable = assertInstanceOf(
            FxRuntimeResolver.Resolution.Unavailable.class,
            resolver.resolve("fx")
        );
        assertEquals(FxRuntimeResolver.Problem.RUNTIME_INVALID, unavailable.problem());
    }

    @Test
    void windowsProductPayloadIsSupportedAndFailsClosedWhenMissing() {
        final FxRuntimeResolver resolver = new FxRuntimeResolver(
            paths(),
            () -> FxRuntimePlatform.detect("Windows 11", "amd64")
        );

        final FxRuntimeResolver.Resolution.Unavailable unavailable = assertInstanceOf(
            FxRuntimeResolver.Resolution.Unavailable.class,
            resolver.resolve(null)
        );
        assertEquals(FxRuntimeResolver.Problem.RUNTIME_MISSING, unavailable.problem());
        assertEquals("windows-x86_64", unavailable.platformId());
    }

    @Test
    void exactWindowsProductPayloadResolvesAsManaged() throws Exception {
        final Path fixture = Path.of(System.getProperty("turboism.windowsFxFixture"));
        Assumptions.assumeTrue(Files.isRegularFile(fixture),
            "Windows managed fx product payload fixture is absent");
        final FxRuntimeManifest.Entry entry = FxRuntimeManifest.allEntries().get(
            "windows-x86_64"
        );
        assertNotNull(entry);
        final Path executable = home.resolve(
            "runtimes/fx/0.0.5/windows-x86_64/fx.exe"
        );
        Files.createDirectories(executable.getParent());
        Files.copy(fixture, executable);
        final FxRuntimeResolver resolver = new FxRuntimeResolver(
            paths(),
            () -> FxRuntimePlatform.detect("Windows 11", "amd64")
        );

        final FxRuntimeResolver.Resolution.Available available = assertInstanceOf(
            FxRuntimeResolver.Resolution.Available.class,
            resolver.resolve(null)
        );
        assertEquals(executable.toString(), available.executable());
        assertEquals(FxRuntimeResolver.Source.MANAGED, available.source());
        assertEquals("windows-x86_64", available.platformId());
        assertEquals(entry.executableSize(), available.managedRuntime().size());
        assertEquals(entry.executableSha256(), available.managedRuntime().sha256());
    }

    private PluginPaths paths() {
        final String plugin = "dev.turboism.plugin.turboism-with-fx";
        return new PluginPaths() {
            @Override public Path configDir() { return home.resolve("config").resolve(plugin); }
            @Override public Path dataDir() { return home.resolve("data").resolve(plugin); }
            @Override public Path logsDir() { return home.resolve("logs").resolve(plugin); }
            @Override public Path stateDir() { return home.resolve("state").resolve(plugin); }
            @Override public Path cacheDir() { return home.resolve("cache").resolve(plugin); }
        };
    }
}
