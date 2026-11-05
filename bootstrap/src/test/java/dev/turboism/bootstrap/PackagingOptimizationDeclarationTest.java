package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.PerformanceSettingsConfig;
import dev.turboism.config.TriangulationEdgeIndexPreference;
import dev.turboism.config.optimization.OptimizationCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Fails closed when a packaging adapter stops agreeing with the runtime catalogue.
 *
 * <p>Three files restate the launcher's optimization fields - the Windows installer, the Windows
 * launcher and the Java/Python installer merge - and none of them can depend on {@code :runtime},
 * so the catalogue cannot generate them. This gate is the second adapter the design chose over
 * code generation: it reads the shipped text and names the exact diff, so adding a catalogue row
 * without teaching packaging about it fails here instead of silently dropping the user's
 * preference at install time.</p>
 *
 * <p>It also pins the launcher's polarity declaration and the edge-index default, which D6 fixed:
 * a missing {@code meshTriangulationEdgeIndex} key leaves the JVM's attach mechanism alone.</p>
 */
class PackagingOptimizationDeclarationTest {

    /** Launcher fields the catalogue does not own: each has its own value shape and diagnostics. */
    private static final Set<String> NON_OPTIMIZATION_LAUNCHER_FIELDS =
            Set.of("cubismJvm", "graalVmPath", "zgc", "memoryProfile");

    private static final List<String> LAUNCHER_FIELDS = OptimizationCatalog.launcherFieldNames();

    private static final Pattern QUOTED_NAME = Pattern.compile("\"([A-Za-z][A-Za-z0-9]*)\"");

    @Test
    void windowsLauncherEmitsOrDeclaresEveryCatalogueField() throws IOException {
        final String launcher = read("packaging/windows-installer/cubism-launch-common.ps1");

        final Set<String> emitFalse = declaredNames(launcher, "# optimization-emit-false:");
        final Set<String> emitTrue = declaredNames(launcher, "# optimization-emit-true:");
        final Set<String> explicitFalse = declaredNames(launcher, "# optimization-explicit-false:");
        final Set<String> notEmitted = declaredNames(launcher, "# optimization-not-emitted:");

        final Set<String> declared = new LinkedHashSet<>(emitFalse);
        declared.addAll(emitTrue);
        declared.addAll(explicitFalse);
        declared.addAll(notEmitted);
        assertEquals(
                new TreeSet<>(LAUNCHER_FIELDS),
                new TreeSet<>(declared),
                "the launcher's four polarity lines must partition the catalogue's launcher fields");
        assertDisjoint(List.of(emitFalse, emitTrue, explicitFalse, notEmitted));

        for (final String name : emitFalse) {
            assertTrue(
                    launcher.contains("-Dturboism.optimization." + name + "=false"),
                    "declared emit-false but never emitted: " + name);
        }
        for (final String name : emitTrue) {
            assertTrue(
                    launcher.contains("-Dturboism.optimization." + name + "=true"),
                    "declared emit-true but never emitted: " + name);
        }
        for (final String name : explicitFalse) {
            assertTrue(
                    launcher.contains("Read-CubismExplicitFalse -TurboismHome $TurboismHome -Name \"" + name + "\""),
                    "declared explicit-false but never read explicitly: " + name);
        }
        for (final String name : notEmitted) {
            assertFalse(
                    launcher.contains("-Dturboism.optimization." + name + "="),
                    "declared not-emitted but the launcher emits it: " + name);
        }

        // Every optimization property the launcher touches - emitting or stripping a stale flag -
        // has to be a declared field, so a removed catalogue row cannot linger in the launcher. A
        // PowerShell regex escapes its dots, so the text is normalised before it is read.
        final String properties = launcher.replace("\\.", ".");
        final Set<String> touched = new TreeSet<>();
        final Matcher emitted =
                Pattern.compile("turboism\\.optimization\\.([A-Za-z]+)=").matcher(properties);
        while (emitted.find()) touched.add(emitted.group(1));
        final Matcher stripped = Pattern.compile("turboism\\.optimization\\.\\(\\?:([A-Za-z|]+)\\)=")
                .matcher(properties);
        while (stripped.find()) {
            for (final String name : stripped.group(1).split("\\|")) touched.add(name);
        }
        assertEquals(new TreeSet<>(LAUNCHER_FIELDS), touched, "the launcher must only name catalogue fields");
    }

    @Test
    void windowsConfiguratorAllowsEveryCatalogueField() throws IOException {
        final String configurator = read("packaging/windows-installer/configure_turboism.ps1");
        final List<Set<String>> lists = new ArrayList<>();
        int index = 0;
        while ((index = configurator.indexOf("@(", index)) >= 0) {
            final int end = configurator.indexOf(')', index);
            if (end < 0) break;
            final String body = configurator.substring(index + 2, end);
            if (body.contains("\"modelUpdateSkip\"")) {
                final Set<String> names = quotedNames(body);
                names.removeAll(NON_OPTIMIZATION_LAUNCHER_FIELDS);
                lists.add(names);
            }
            index = end;
        }
        assertEquals(4, lists.size(), "the Windows configurator declares four launcher field lists");
        for (final Set<String> names : lists) {
            assertEquals(
                    new TreeSet<>(LAUNCHER_FIELDS),
                    new TreeSet<>(names),
                    "a Windows configurator launcher list disagrees with the catalogue");
        }
    }

    @Test
    void javaInstallerMergeAllowsEveryCatalogueField() throws IOException {
        final String merge = read("packaging/java-installer/listener-src/dev/turboism/installer/ConfigMerge.java");
        for (final String declaration : List.of(
                "private static final Set<String> LAUNCHER_FIELDS = Set.of(",
                "private static final Set<String> BOOLEAN_LAUNCHER_FIELDS = Set.of(")) {
            final Set<String> names = declaredNames(merge, declaration, ");");
            names.removeAll(NON_OPTIMIZATION_LAUNCHER_FIELDS);
            assertEquals(
                    new TreeSet<>(LAUNCHER_FIELDS),
                    new TreeSet<>(names),
                    "ConfigMerge must allow exactly the catalogue's launcher fields");
        }
    }

    @Test
    void pythonConfigMergeMirrorAllowsEveryCatalogueField() throws IOException {
        final String mirror = read("packaging/windows-installer/test-config-merge.py");
        for (final String declaration : List.of("\nLAUNCHER_FIELDS = {", "\nBOOLEAN_LAUNCHER_FIELDS = {")) {
            final Set<String> names = declaredNames(mirror, declaration, "}");
            names.removeAll(NON_OPTIMIZATION_LAUNCHER_FIELDS);
            assertEquals(
                    new TreeSet<>(LAUNCHER_FIELDS),
                    new TreeSet<>(names),
                    "the Python config-merge mirror disagrees with the catalogue");
        }
    }

    /**
     * S5b: an absent root-level {@code meshTriangulationEdgeIndex} keeps the vendor attach
     * behavior, so the launcher and the agent agree on one default.
     */
    @Test
    void launcherDefaultsTheEdgeIndexPreferenceOffLikeTheAgent() throws IOException {
        final String launcher = read("packaging/windows-installer/cubism-launch-common.ps1");
        final String reader = function(launcher, "Read-CubismMeshTriangulationEdgeIndexPreference");
        assertFalse(reader.contains("$true"), "the launcher must not default meshTriangulationEdgeIndex on: " + reader);
        assertTrue(reader.contains("return $false"), "every absent-key branch must default off");
        assertTrue(
                launcher.contains("if (Read-CubismMeshTriangulationEdgeIndexPreference -TurboismHome $TurboismHome)"),
                "the attach-mechanism option must stay tied to the persisted preference");

        assertFalse(
                TriangulationEdgeIndexPreference.DEFAULT_ENABLED,
                "the agent's edge-index reader keeps the same fail-closed default");
        assertEquals(
                "false",
                PerformanceSettingsConfig.Field.TRIANGULATION_EDGE_INDEX.defaultValue(false),
                "the settings page and the launcher must agree on the edge-index default");
    }

    private static void assertDisjoint(final List<Set<String>> lists) {
        final Set<String> seen = new LinkedHashSet<>();
        for (final Set<String> list : lists) {
            for (final String name : list) {
                assertTrue(seen.add(name), "the polarity lines name " + name + " twice");
            }
        }
    }

    /** @return the comma-separated names a machine-readable polarity comment declares */
    private static Set<String> declaredNames(final String source, final String marker) {
        final int start = source.indexOf(marker);
        assertTrue(start >= 0, "the launcher must declare a '" + marker + "' line");
        final int end = source.indexOf('\n', start);
        final String line =
                end < 0 ? source.substring(start + marker.length()) : source.substring(start + marker.length(), end);
        final Set<String> names = new LinkedHashSet<>();
        for (final String name : line.split(",")) {
            final String trimmed = name.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }
        assertFalse(names.isEmpty(), "the '" + marker + "' line names no field");
        return names;
    }

    /** @return the quoted names between {@code open} and the next {@code close} */
    private static Set<String> declaredNames(final String source, final String open, final String close) {
        final int start = source.indexOf(open);
        assertTrue(start >= 0, "packaging must declare " + open.trim());
        final int bodyStart = start + open.length();
        final int end = source.indexOf(close, bodyStart);
        assertTrue(end > bodyStart, "the " + open.trim() + " declaration is unterminated");
        return quotedNames(source.substring(bodyStart, end));
    }

    /** @return the identifier-shaped names quoted in {@code text} */
    private static Set<String> quotedNames(final String text) {
        final Set<String> names = new LinkedHashSet<>();
        final Matcher matcher = QUOTED_NAME.matcher(text);
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }

    /** @return the body of one PowerShell function, up to the next top-level declaration */
    private static String function(final String script, final String name) {
        final String header = "function " + name + " {";
        final int start = script.indexOf(header);
        assertTrue(start >= 0, "the launcher must define " + name);
        final int end = script.indexOf("\nfunction ", start + header.length());
        return end < 0 ? script.substring(start) : script.substring(start, end);
    }

    private static String read(final String relativePath) throws IOException {
        final Path root = Path.of(System.getProperty("turboism.test.repositoryRoot", "."));
        final Path path = root.resolve(relativePath);
        assertTrue(Files.isRegularFile(path), "missing packaging file: " + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
