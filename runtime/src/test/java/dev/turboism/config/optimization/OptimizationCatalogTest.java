package dev.turboism.config.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.NativeOptimizationConfig;
import dev.turboism.config.PerformanceSettingsConfig.Field;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The catalogue is the only declaration of a native optimization, so these are the invariants that
 * keep it equal to what the product already shipped: the persisted keys and their scope, the
 * settings-page order, the i18n keys and the {@code -D} override names.
 */
final class OptimizationCatalogTest {

    /** i18n bundles compiled with the runtime; every label and tooltip key must resolve in all of them. */
    private static final List<String> BUNDLES = List.of(
            "messages.properties",
            "messages_en.properties",
            "messages_ja.properties",
            "messages_ko.properties",
            "messages_zh_Hans.properties",
            "messages_zh_Hant.properties");

    /** The four entries whose label is not derivable from their contribution id (Atlas and mesh groups). */
    private static final Map<String, String> NON_DERIVABLE_LABEL_KEYS = Map.of(
            "mesh-triangulation-hash", "settings.mesh-triangulation.hash-degeneracy",
            "triangulation-edge-index", "settings.mesh-triangulation.edge-index",
            "atlas-tile-bbox", "settings.atlas.tile-bbox",
            "atlas-cache-reuse", "settings.atlas.cache-reuse");

    @Test
    void everyDeclaredOptimizationIsTheOneTheProductShips() {
        assertEquals(31, OptimizationCatalog.all().size());
        assertEquals(26, OptimizationCatalog.persisted().size());
        assertEquals(
                5,
                OptimizationCatalog.all().size()
                        - OptimizationCatalog.persisted().size());
        assertEquals(26, OptimizationCatalog.placed().size());
        assertEquals(22, OptimizationCatalog.launcherFieldNames().size());
        assertEquals(22, OptimizationCatalog.booleanLauncherFieldNames().size());
        assertEquals(4, OptimizationCatalog.rootFieldNames().size());
        assertEquals(32, OptimizationCatalog.protocols().size(), "27 reported protocols plus 5 probe protocols");
    }

    /**
     * I1: every persisted entry keeps the {@code config.json} key, the {@code launcher.} vs root
     * scope and the boolean encoding it already had, and stays off by default on every platform.
     */
    @Test
    void persistedEntriesMatchTheirPersistenceField() {
        for (final Optimization optimization : OptimizationCatalog.persisted()) {
            final Field field = Field.forKey(optimization.key());
            assertNotNull(field, optimization.key() + " is persisted without a field");
            assertEquals(field.key(), optimization.key());
            assertEquals(
                    field.launcher() ? Optimization.Access.PERSISTED_LAUNCHER : Optimization.Access.PERSISTED_ROOT,
                    optimization.access(),
                    optimization.key() + " must be written where its field is read");
            assertTrue(field.booleanValue(), optimization.key() + " is persisted as a boolean");
            assertEquals("false", field.defaultValue(false), optimization.key() + " must stay off on Windows");
            assertEquals("false", field.defaultValue(true), optimization.key() + " must stay off under Proton");
        }
        for (final Field field : Field.values()) {
            if (!field.nativeOptimization()) continue;
            assertTrue(
                    OptimizationCatalog.byKey(field.key()).isPresent(),
                    field.key() + " is a native optimization field without a catalogue entry");
        }
    }

    @Test
    void diagnosticOnlyEntriesClaimNoPersistedIdentity() {
        for (final Optimization optimization : OptimizationCatalog.all()) {
            if (optimization.persisted()) continue;
            assertEquals("", optimization.key());
            assertEquals("", optimization.contributionId());
            assertEquals("", optimization.labelKey());
            assertEquals("", optimization.tooltipKey());
            assertTrue(optimization.placement().isEmpty());
            assertFalse(optimization.protocols().isEmpty());
        }
    }

    /**
     * I3: walking the catalogue in placement order reproduces the reviewed settings page exactly.
     * A reordered or retyped row fails here with the row that moved, before the rendered page can.
     */
    @Test
    void placementReproducesTheGoldenLayout() throws Exception {
        final List<String> layout = goldenToggles().stream()
                .filter(row -> OptimizationCatalog.byContributionId(row.substring(0, row.lastIndexOf(' ')))
                        .isPresent())
                .toList();
        final List<String> actual = OptimizationCatalog.placed().stream()
                .map(optimization -> optimization.contributionId() + " "
                        + optimization.placement().orElseThrow().index())
                .toList();
        assertEquals(26, layout.size(), "settings-layout.txt renders every persisted optimization");
        assertEquals(layout, actual, "settings-layout.txt order changed");
    }

    /** I4: every label and tooltip key resolves in all six bundles, so no row renders a raw key. */
    @Test
    void everyKeyExistsInEveryBundle() throws IOException {
        for (final String bundle : BUNDLES) {
            final Properties messages = bundle(bundle);
            for (final Optimization optimization : OptimizationCatalog.persisted()) {
                assertResolves(messages, bundle, optimization.labelKey(), optimization.contributionId());
                assertResolves(messages, bundle, optimization.tooltipKey(), optimization.contributionId());
            }
        }
    }

    @Test
    void labelsThatAreNotDerivableFromTheContributionIdAreDeclaredExplicitly() {
        NON_DERIVABLE_LABEL_KEYS.forEach((contributionId, labelKey) -> assertEquals(
                labelKey,
                OptimizationCatalog.byContributionId(contributionId)
                        .orElseThrow()
                        .labelKey(),
                contributionId + " keeps its reviewed label key"));
        for (final Optimization optimization : OptimizationCatalog.persisted()) {
            if (NON_DERIVABLE_LABEL_KEYS.containsKey(optimization.contributionId())) continue;
            assertEquals(
                    "settings.optimization." + optimization.contributionId(),
                    optimization.labelKey(),
                    optimization.contributionId() + " keeps the derived label key");
            assertEquals(
                    optimization.labelKey() + ".tooltip",
                    optimization.tooltipKey(),
                    optimization.contributionId() + " keeps the derived tooltip key");
        }
    }

    /**
     * I5: the {@code -D} names are unchanged, including the two legacy aliases earlier launchers
     * documented but never wrote, and the modern name still wins when both are set.
     */
    @Test
    void optimizationOverridesKeepTheirNames(@TempDir final Path home) {
        assertEquals(
                List.of("turboism.optimization.meshInterpolation", "turboism.meshInterpolation.enabled"),
                OptimizationCatalog.MESH_INTERPOLATION.overrideProperties());
        assertEquals(
                List.of("turboism.optimization.shaderUniform", "turboism.shaderUniform.enabled"),
                OptimizationCatalog.SHADER_UNIFORM.overrideProperties());
        for (final Optimization optimization : OptimizationCatalog.persisted()) {
            if (optimization.equals(OptimizationCatalog.MESH_INTERPOLATION)
                    || optimization.equals(OptimizationCatalog.SHADER_UNIFORM)) continue;
            assertEquals(
                    List.of("turboism.optimization." + optimization.key()),
                    optimization.overrideProperties(),
                    optimization.key() + " has exactly one override name");
        }
        assertEquals(
                "turboism.optimization.sortingInfoCache",
                OptimizationCatalog.SORTING_INFO_CACHE.property(),
                "the sorting-info probe keeps its product-style property name");

        final String[] overrides = {
            OptimizationCatalog.MESH_INTERPOLATION.property(),
            "turboism.meshInterpolation.enabled",
            OptimizationCatalog.SHADER_UNIFORM.property(),
            "turboism.shaderUniform.enabled"
        };
        final Map<String, String> previous = new LinkedHashMap<>();
        try {
            for (final String override : overrides) {
                previous.put(override, System.getProperty(override));
                System.clearProperty(override);
            }
            System.setProperty("turboism.meshInterpolation.enabled", "true");
            var legacy = NativeOptimizationConfig.load(home, ignored -> {});
            assertTrue(legacy.choice(OptimizationCatalog.MESH_INTERPOLATION).enabled());
            assertEquals(
                    "jvm-override",
                    legacy.choice(OptimizationCatalog.MESH_INTERPOLATION).source());

            System.setProperty("turboism.optimization.meshInterpolation", "false");
            var modern = NativeOptimizationConfig.load(home, ignored -> {});
            assertFalse(
                    modern.choice(OptimizationCatalog.MESH_INTERPOLATION).enabled(),
                    "the documented property must win over the legacy alias");
        } finally {
            previous.forEach((override, value) -> {
                if (value == null) System.clearProperty(override);
                else System.setProperty(override, value);
            });
        }
    }

    @Test
    void lookupsFailClosedOnAnUnknownIdentity() {
        assertThrows(IllegalArgumentException.class, () -> OptimizationCatalog.requireByKey("notAnOptimization"));
        assertTrue(OptimizationCatalog.byKey("cubismJvm").isEmpty(), "the JVM selection is not an optimization");
        assertTrue(OptimizationCatalog.byProtocol("TURBOISM_NOT_A_PROTOCOL").isEmpty());
        assertTrue(OptimizationCatalog.byContributionId("not-a-row").isEmpty());
        assertEquals(OptimizationCatalog.MODEL_UPDATE_SKIP, OptimizationCatalog.requireByKey("modelUpdateSkip"));
    }

    /** A catalogue that cannot be described consistently refuses at first touch, naming both sides. */
    @Test
    void integrityChecksNameTheOffendingRows() {
        final Optimization base = OptimizationCatalog.MODEL_UPDATE_SKIP;
        final Optimization other = OptimizationCatalog.INCREMENTAL_UPDATE;

        assertRefused("declare the config key modelUpdateSkip", withContributionId(base, "other-row"), base);

        assertRefused(
                "declare the contribution id model-update-skip",
                withContributionId(other, base.contributionId()),
                base);

        assertRefused("declare the protocol TURBOISM_MODEL_UPDATE_SKIP", withProtocols(other, base.protocols()), base);

        assertRefused("reuses the settings row heavy-model#62", withPlacement(other, base.placement()), base);

        assertRefused(
                "is diagnostic-only but declares a persisted identity",
                withAccess(base, Optimization.Access.DIAGNOSTIC_ONLY));

        assertRefused("is persisted but declares no config key", withKey(base, ""));

        assertRefused("declares no diagnostics protocol", withProtocols(base, List.of()));

        assertRefused("declares no -D override property", withOverrides(base, List.of()));

        assertRefused(
                "must be overridable as turboism.optimization.modelUpdateSkip",
                withOverrides(base, List.of("turboism.validation.somethingElse")));

        assertRefused(
                "is placed in the unknown section nowhere",
                withPlacement(base, Optional.of(new Optimization.Placement("nowhere", 62))));

        assertRefused("is persisted but declares no settings placement", withPlacement(base, Optional.empty()));

        assertRefused("is persisted but declares no contribution id", withContributionId(base, ""));
    }

    private static void assertRefused(final String expectedMessage, final Optimization... entries) {
        final IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> OptimizationCatalog.validate(List.of(entries)));
        assertTrue(
                refused.getMessage().contains(expectedMessage),
                "expected \"" + expectedMessage + "\" in: " + refused.getMessage());
    }

    private static Optimization withKey(final Optimization base, final String key) {
        return rebuild(
                base,
                key,
                base.contributionId(),
                base.access(),
                base.overrideProperties(),
                base.protocols(),
                base.placement());
    }

    private static Optimization withContributionId(final Optimization base, final String contributionId) {
        return rebuild(
                base,
                base.key(),
                contributionId,
                base.access(),
                base.overrideProperties(),
                base.protocols(),
                base.placement());
    }

    private static Optimization withAccess(final Optimization base, final Optimization.Access access) {
        return rebuild(
                base,
                base.key(),
                base.contributionId(),
                access,
                base.overrideProperties(),
                base.protocols(),
                base.placement());
    }

    private static Optimization withProtocols(final Optimization base, final List<String> protocols) {
        return rebuild(
                base,
                base.key(),
                base.contributionId(),
                base.access(),
                base.overrideProperties(),
                protocols,
                base.placement());
    }

    private static Optimization withOverrides(final Optimization base, final List<String> overrides) {
        return rebuild(
                base, base.key(), base.contributionId(), base.access(), overrides, base.protocols(), base.placement());
    }

    private static Optimization withPlacement(
            final Optimization base, final Optional<Optimization.Placement> placement) {
        return rebuild(
                base,
                base.key(),
                base.contributionId(),
                base.access(),
                base.overrideProperties(),
                base.protocols(),
                placement);
    }

    private static Optimization rebuild(
            final Optimization base,
            final String key,
            final String contributionId,
            final Optimization.Access access,
            final List<String> overrideProperties,
            final List<String> protocols,
            final Optional<Optimization.Placement> placement) {
        return new Optimization(
                key,
                contributionId,
                access,
                overrideProperties,
                protocols,
                base.hookIds(),
                placement,
                base.labelKey(),
                base.tooltipKey());
    }

    /** @return every rendered toggle as {@code <contribution id> <index>}, in page order */
    private static List<String> goldenToggles() throws IOException {
        final List<String> toggles = new ArrayList<>();
        try (InputStream stream = OptimizationCatalogTest.class
                .getClassLoader()
                .getResourceAsStream("dev/turboism/shell/settings-layout.txt")) {
            assertNotNull(stream, "settings-layout.txt is missing from test resources");
            for (final String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .toList()) {
                if (line.startsWith("#") || line.isBlank()) continue;
                final String[] parts = line.split(" ");
                if (parts.length != 4 || !"toggle".equals(parts[2])) continue;
                toggles.add(parts[3] + " " + parts[1]);
            }
        }
        return toggles;
    }

    private static Properties bundle(final String name) throws IOException {
        try (InputStream stream =
                OptimizationCatalogTest.class.getClassLoader().getResourceAsStream("META-INF/turboism/i18n/" + name)) {
            assertNotNull(stream, name + " is missing from the runtime classpath");
            final Properties messages = new Properties();
            messages.load(stream);
            return messages;
        }
    }

    private static void assertResolves(
            final Properties messages, final String bundle, final String key, final String contributionId) {
        final String text = messages.getProperty(key);
        assertNotNull(text, bundle + " has no entry for " + contributionId + ": " + key);
        assertFalse(text.isBlank(), bundle + " leaves " + contributionId + " empty: " + key);
    }
}
