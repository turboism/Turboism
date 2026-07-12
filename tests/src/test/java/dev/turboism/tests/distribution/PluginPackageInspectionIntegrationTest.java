package dev.turboism.tests.distribution;

import dev.turboism.distribution.LocalPluginPackageInspector;
import dev.turboism.distribution.PackageKind;
import dev.turboism.distribution.PluginPackageInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PluginPackageInspectionIntegrationTest {
    @TempDir Path tempDir;

    @Test void acceptsExactPluginPackageAsReadOnlyPlan() throws Exception {
        byte[] archive = PluginPackageFixtures.valid();
        Path input = tempDir.resolve("sample.tplugin");
        Files.write(input, archive);

        var accepted = assertInstanceOf(PluginPackageInspector.Accepted.class,
            new LocalPluginPackageInspector().inspect(input));
        var plan = accepted.plan();

        assertEquals(PackageKind.PLUGIN, plan.packageKind());
        assertEquals(PluginPackageFixtures.ID, plan.packageIdentity().id());
        assertEquals(PluginPackageFixtures.VERSION, plan.packageIdentity().version());
        assertEquals(PluginPackageFixtures.sha256(archive), plan.packageIdentity().sha256());
        assertEquals("[0.1.0,0.2.0)", plan.descriptor().turboismApi());
        assertEquals(PluginPackageFixtures.ENTRYPOINT, plan.descriptor().entrypoints().get("plugin"));
        assertEquals("payload/plugin.jar", plan.files().get(0).archivePath());
        assertEquals("plugin.jar", plan.files().get(0).installPath());
        assertEquals("INSPECTION_PREFLIGHT_REVALIDATION_REQUIRED", plan.requirement().name());
        assertArrayEquals(archive, Files.readAllBytes(input));
        assertEquals(1, Files.list(tempDir).count());
    }

    @Test void rejectsOuterIdentityMismatch() throws Exception {
        byte[] jar = PluginPackageFixtures.jar(
            PluginPackageFixtures.descriptor(PluginPackageFixtures.ID, "0.2.0", "0.1.0"),
            PluginPackageFixtures.ENTRYPOINT.replace('.', '/') + ".class", "class");
        assertRejected(PluginPackageFixtures.packageWith(jar, PluginPackageFixtures.ID,
            PluginPackageFixtures.VERSION, ""), "PLUGIN_IDENTITY_MISMATCH");
    }

    @Test void rejectsInnerIdentityMismatch() throws Exception {
        byte[] jar = PluginPackageFixtures.jar(
            PluginPackageFixtures.descriptor("dev.turboism.plugin.other",
                PluginPackageFixtures.VERSION, "0.1.0"),
            PluginPackageFixtures.ENTRYPOINT.replace('.', '/') + ".class", "class");
        assertRejected(PluginPackageFixtures.packageWith(jar, PluginPackageFixtures.ID,
            PluginPackageFixtures.VERSION, ""), "PLUGIN_IDENTITY_MISMATCH");
    }

    @Test void acceptsOnlyStrictV1ApiForms() throws Exception {
        for (String invalid : new String[]{"*", "1.x", "^0.1.0", "[0.1.0,)", "(0.1.0,0.2.0]", "01.0.0"}) {
            byte[] jar = PluginPackageFixtures.jar(
                PluginPackageFixtures.descriptor(PluginPackageFixtures.ID,
                    PluginPackageFixtures.VERSION, invalid),
                PluginPackageFixtures.ENTRYPOINT.replace('.', '/') + ".class", "class");
            assertRejected(PluginPackageFixtures.packageWith(jar, PluginPackageFixtures.ID,
                PluginPackageFixtures.VERSION, ""), "PLUGIN_META_BAD_VERSION_RANGE");
        }
    }

    @Test void publicPlanCannotBeExternallyForged() {
        assertFalse(java.util.Arrays.stream(dev.turboism.distribution.PluginInstallPlan.class
            .getDeclaredConstructors()).anyMatch(constructor ->
                java.lang.reflect.Modifier.isPublic(constructor.getModifiers())));
    }

    @Test void rejectsMissingEntrypointClass() throws Exception {
        byte[] jar = PluginPackageFixtures.jar(
            PluginPackageFixtures.descriptor(PluginPackageFixtures.ID,
                PluginPackageFixtures.VERSION, "0.1.0"));
        assertRejected(PluginPackageFixtures.packageWith(jar, PluginPackageFixtures.ID,
            PluginPackageFixtures.VERSION, ""), "PLUGIN_ENTRYPOINT_MISSING");
    }

    @Test void rejectsInvalidOuterContract() throws Exception {
        byte[] jar = PluginPackageFixtures.jar(
            PluginPackageFixtures.descriptor(PluginPackageFixtures.ID,
                PluginPackageFixtures.VERSION, "0.1.0"),
            PluginPackageFixtures.ENTRYPOINT.replace('.', '/') + ".class", "class");
        assertRejected(PluginPackageFixtures.packageWith(jar, PluginPackageFixtures.ID,
            "01.0.0", ""), "MANIFEST_FIELD_INVALID");
    }

    private void assertRejected(byte[] archive, String code) throws Exception {
        Path input = tempDir.resolve(code + ".tplugin");
        Files.write(input, archive);
        var rejected = assertInstanceOf(PluginPackageInspector.Rejected.class,
            new LocalPluginPackageInspector().inspect(input));
        assertEquals(code, rejected.problems().get(0).code());
    }
}
