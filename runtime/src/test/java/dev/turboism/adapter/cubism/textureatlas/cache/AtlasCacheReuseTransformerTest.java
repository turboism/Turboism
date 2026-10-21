package dev.turboism.adapter.cubism.textureatlas.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Behaviour tests for the cache-reuse transformer.
 *
 * <p>The fixture {@code CTextureAtlas} compiles into the test source set under the reviewed
 * class name, so the test exercises the real selector without touching any official
 * artifact.</p>
 */
final class AtlasCacheReuseTransformerTest {

    private static final AtlasCacheReuseTarget TARGET =
            AtlasCacheReuseTarget.reviewed("com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas");

    @Test
    void patchesTheReviewedShape() throws Exception {
        final byte[] fixture = fixtureBytes(TARGET.internalName() + ".class");
        final AtlasCacheReuseTransformer transformer =
                new AtlasCacheReuseTransformer(TARGET, java.util.Set.of(AtlasCacheReuseTransformer.sha256(fixture)));

        final byte[] patched = transformer.transform(null, getLoader(), TARGET.internalName(), null, null, fixture);

        assertNotNull(patched, "the reviewed shape must be patched");
        assertEquals(AtlasCacheReuseTransformer.Outcome.PATCHED, transformer.outcome());
    }

    @Test
    void refusesClassesWhoseBytesAreNotTheReviewedDigest() throws Exception {
        final AtlasCacheReuseTransformer transformer = new AtlasCacheReuseTransformer();
        final byte[] fixture = fixtureBytes(TARGET.internalName() + ".class");
        assertNull(transformer.transform(null, getLoader(), TARGET.internalName(), null, null, fixture));
        assertEquals(AtlasCacheReuseTransformer.Outcome.HASH_MISMATCH, transformer.outcome());
    }

    @Test
    void refusesAClassWithTheDigestButNotTheShape() throws Exception {
        final byte[] alien = fixtureBytes("com/live2d/type/CModelImageGuid.class");
        final String alienSha = AtlasCacheReuseTransformer.sha256(alien);
        final AtlasCacheReuseTransformer transformer =
                new AtlasCacheReuseTransformer(TARGET, java.util.Set.of(alienSha));
        assertNull(transformer.transform(null, getLoader(), TARGET.internalName(), null, null, alien));
        assertEquals(AtlasCacheReuseTransformer.Outcome.SHAPE_REJECTED, transformer.outcome());
    }

    @Test
    void admitsTheExtraDigestOnlyWhenThePropertyIsSet() throws Exception {
        final byte[] fixture = fixtureBytes(TARGET.internalName() + ".class");
        final String fixtureSha = AtlasCacheReuseTransformer.sha256(fixture);
        final String property = AtlasCacheReuseTransformer.ADMIT_CLASS_SHA256_PROPERTY;
        final String saved = System.getProperty(property);
        try {
            System.setProperty(property, fixtureSha);
            final AtlasCacheReuseTransformer transformer = new AtlasCacheReuseTransformer();
            assertNotNull(
                    transformer.transform(null, getLoader(), TARGET.internalName(), null, null, fixture),
                    "the admitted extra digest must patch like the reviewed one");
            assertEquals(AtlasCacheReuseTransformer.Outcome.PATCHED, transformer.outcome());
        } finally {
            if (saved == null) System.clearProperty(property);
            else System.setProperty(property, saved);
        }
    }

    @Test
    void ignoresMalformedExtraDigests() throws Exception {
        final byte[] fixture = fixtureBytes(TARGET.internalName() + ".class");
        final String property = AtlasCacheReuseTransformer.ADMIT_CLASS_SHA256_PROPERTY;
        final String saved = System.getProperty(property);
        try {
            System.setProperty(property, "not-hex");
            final AtlasCacheReuseTransformer transformer = new AtlasCacheReuseTransformer();
            assertNull(transformer.transform(null, getLoader(), TARGET.internalName(), null, null, fixture));
            assertEquals(AtlasCacheReuseTransformer.Outcome.HASH_MISMATCH, transformer.outcome());
        } finally {
            if (saved == null) System.clearProperty(property);
            else System.setProperty(property, saved);
        }
    }

    @Test
    void keepsDiagnosticsOffHostConsoleButPreservesOptInEvidence(@TempDir final Path temp) throws Exception {
        final byte[] fixture = fixtureBytes(TARGET.internalName() + ".class");
        final AtlasCacheReuseTransformer transformer =
                new AtlasCacheReuseTransformer(TARGET, java.util.Set.of(AtlasCacheReuseTransformer.sha256(fixture)));
        final String property = "turboism.atlasCacheReuse.evidenceFile";
        final String saved = System.getProperty(property);
        final PrintStream originalErr = System.err;
        final PrintStream originalOut = System.out;
        final ByteArrayOutputStream console = new ByteArrayOutputStream();
        final Path evidence = temp.resolve("atlas.log");
        try (PrintStream captured = new PrintStream(console, true, StandardCharsets.UTF_8)) {
            System.setErr(captured);
            System.setOut(captured);
            System.clearProperty(property);
            assertNotNull(transformer.transform(null, getLoader(), TARGET.internalName(), null, null, fixture));
            assertEquals("", console.toString(StandardCharsets.UTF_8));

            System.setProperty(property, evidence.toString());
            assertNotNull(transformer.transform(null, getLoader(), TARGET.internalName(), null, null, fixture));
            org.junit.jupiter.api.Assertions.assertTrue(
                    Files.readString(evidence).contains("SEEN"));
            org.junit.jupiter.api.Assertions.assertTrue(
                    Files.readString(evidence).contains("PATCHED"));
            assertEquals("", console.toString(StandardCharsets.UTF_8));

            // Rejected hosts must still fall back safely without writing to the native log.
            new AtlasCacheReuseTransformer().transform(null, getLoader(), TARGET.internalName(), null, null, fixture);
            org.junit.jupiter.api.Assertions.assertTrue(
                    Files.readString(evidence).contains("HASH_MISMATCH"));
            assertEquals("", console.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(originalErr);
            System.setOut(originalOut);
            if (saved == null) System.clearProperty(property);
            else System.setProperty(property, saved);
        }
    }

    private static ClassLoader getLoader() {
        return AtlasCacheReuseTransformerTest.class.getClassLoader();
    }

    private static byte[] fixtureBytes(final String resource) throws Exception {
        try (var in = getLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "missing fixture resource " + resource);
            return in.readAllBytes();
        }
    }
}
