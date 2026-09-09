package dev.turboism.ui.resource;

import dev.turboism.sdk.ui.resource.CubismIcon;
import dev.turboism.sdk.ui.resource.UiIconAvailability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CubismNativeIconResolverTest {
    @TempDir Path temporary;

    @Test
    void catalogHasExactlyTheReviewedEightyVariantsAndTwoArtifacts() {
        assertEquals(80, CubismNativeIconCatalog.resources().size());
        for (var entry : CubismNativeIconCatalog.resources().entrySet()) {
            final NativeIconVariant key = entry.getKey();
            assertEquals(16 * key.scalePercent() / 100, key.physicalSize());
            assertTrue(entry.getValue().matches("[0-9a-f]{64}"));
            assertTrue(key.resourcePath().startsWith("res/image_"));
            assertFalse(key.resourcePath().contains(".."));
        }
        for (CubismIcon icon : CubismIcon.values()) {
            assertEquals(20L, CubismNativeIconCatalog.resources().keySet().stream()
                .filter(key -> key.icon() == icon).count());
        }
        for (var theme : NativeIconVariant.Theme.values()) {
            for (boolean disabled : new boolean[] {false, true}) {
                for (int scale : new int[] {100, 125, 150, 175, 200}) {
                    final var part = new NativeIconVariant(CubismIcon.PART, theme, scale, disabled);
                    assertTrue(CubismNativeIconCatalog.resources().containsKey(part));
                    assertTrue(part.resourcePath().contains("/Folder-Colored_16x16_"));
                }
            }
        }
        assertSame(dev.turboism.mapping.verification.ReviewedHostArtifacts.CUBISM_5_2_03,
            CubismNativeIconCatalog.artifact("5.2.03").orElseThrow());
        assertSame(dev.turboism.mapping.verification.ReviewedHostArtifacts.CUBISM_5_3_02,
            CubismNativeIconCatalog.artifact("5.3.02").orElseThrow());
        assertTrue(CubismNativeIconCatalog.artifact("5.3.03").isEmpty());
        assertTrue(CubismNativeIconCatalog.artifact("5.3").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> CubismNativeIconCatalog.resources().clear());
    }

    @Test
    void unknownVersionsAndChangedArtifactsFailClosed() throws Exception {
        final Path fake = temporary.resolve("not-cubism.jar");
        Files.writeString(fake, "not a reviewed host artifact");
        final NativeIconVariant key = normal();
        try (var unknown = CubismNativeIconResolver.preloadArtifact("5.3.03", fake);
             var changed = CubismNativeIconResolver.preloadArtifact("5.3.02", fake)) {
            assertEquals(UiIconAvailability.HOST_UNVERIFIED, unknown.availability(key));
            assertEquals(UiIconAvailability.HOST_UNVERIFIED, changed.availability(key));
            assertTrue(changed.resolve(key).isEmpty());
        }
    }

    @Test
    void sameSizeIsNotAHashAttestationAndMissingFilesAreUnavailable() throws Exception {
        final Path sameSize = temporary.resolve("same-size.jar");
        try (var file = new java.io.RandomAccessFile(sameSize.toFile(), "rw")) {
            file.setLength(CubismNativeIconCatalog.artifact("5.2.03").orElseThrow().size());
        }
        try (var provider = CubismNativeIconResolver.preloadArtifact("5.2.03", sameSize);
             var missing = CubismNativeIconResolver.preloadArtifact("5.2.03", temporary.resolve("missing.jar"))) {
            assertEquals(UiIconAvailability.HOST_UNVERIFIED, provider.availability(normal()));
            assertTrue(provider.resolve(normal()).isEmpty());
            assertEquals(UiIconAvailability.HOST_UNVERIFIED, missing.availability(normal()));
        }
    }

    @Test
    void decodingChecksDigestSignatureAndDimensionsBeforeAcceptingPixels() throws Exception {
        final byte[] png = png(16);
        assertNotNull(CubismNativeIconResolver.decodePng(png, sha(png), 16));
        assertThrows(java.io.IOException.class,
            () -> CubismNativeIconResolver.decodePng(png, "0".repeat(64), 16));
        assertThrows(java.io.IOException.class,
            () -> CubismNativeIconResolver.decodePng(png, sha(png), 32));
        final byte[] malformed = new byte[32];
        assertThrows(java.io.IOException.class,
            () -> CubismNativeIconResolver.decodePng(malformed, sha(malformed), 16));
        final byte[] oversized = new byte[4097];
        assertThrows(java.io.IOException.class,
            () -> CubismNativeIconResolver.decodePng(oversized, sha(oversized), 16));
    }

    @Test
    void cachedIconsAreLogicalSixteenPixelsAndOldHandlesReleaseImagesOnClose() {
        final NativeIconVariant key = normal();
        final BufferedImage pixels = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        pixels.setRGB(0, 0, 0xffff0000);
        final var resolver = new CubismNativeIconResolver(Map.of(key, pixels), UiIconAvailability.RESOURCE_UNAVAILABLE);
        final var icon = resolver.resolve(key).orElseThrow();
        assertSame(icon, resolver.resolve(key).orElseThrow());
        assertEquals(16, icon.getIconWidth());
        assertEquals(16, icon.getIconHeight());
        assertEquals(UiIconAvailability.AVAILABLE, resolver.availability(key));
        final var missing = new NativeIconVariant(CubismIcon.ART_MESH, NativeIconVariant.Theme.DARK, 200, true);
        assertEquals(UiIconAvailability.RESOURCE_UNAVAILABLE, resolver.availability(missing));
        resolver.close();
        resolver.close();
        assertEquals(UiIconAvailability.SERVICE_UNAVAILABLE, resolver.availability(key));
        assertTrue(resolver.resolve(key).isEmpty());
        final BufferedImage canvas = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        final var graphics = canvas.createGraphics();
        try {
            icon.paintIcon(null, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }
        assertEquals(0, canvas.getRGB(0, 0));
    }

    @Test
    void ioIsForbiddenOnEdtAndVariantInputsAreClosed() throws Exception {
        SwingUtilities.invokeAndWait(() -> assertThrows(IllegalStateException.class,
            () -> CubismNativeIconResolver.preloadArtifact("5.2.03", temporary.resolve("missing"))));
        assertThrows(IllegalArgumentException.class,
            () -> new NativeIconVariant(CubismIcon.ART_MESH, NativeIconVariant.Theme.LIGHT, 101, false));
        assertThrows(NullPointerException.class,
            () -> new NativeIconVariant(null, NativeIconVariant.Theme.LIGHT, 100, false));
    }

    private static boolean fixtureInitialized;
    private static final class UninitializedOwner {
        static { fixtureInitialized = true; }
    }

    @Test
    void publicFactoryRejectsUnverifiedCodeSourceAndNeverUsesContextLoaderOrInitializesOwner() {
        final ClassLoader trusted = getClass().getClassLoader();
        final var selector = dev.turboism.mapping.verification.StaticSelector.classSelector(
            "fixture.owner", UninitializedOwner.class.getName().replace('.', '/'));
        final var resolver = dev.turboism.mapping.verification.TestVerifiedResolvers.create(
            "fixture.icons", java.util.Set.of("fixture.icons"), java.util.List.of(selector), trusted);
        final ClassLoader previous = Thread.currentThread().getContextClassLoader();
        final ClassLoader forbidden = new ClassLoader(null) {
            @Override protected Class<?> loadClass(final String name, final boolean resolve) {
                throw new AssertionError("context classloader must not be consulted");
            }
        };
        Thread.currentThread().setContextClassLoader(forbidden);
        try (var wrongSource = CubismNativeIconResolver.preload(resolver, "fixture.owner");
             var missingAlias = CubismNativeIconResolver.preload(resolver, "not-admitted")) {
            assertEquals(UiIconAvailability.HOST_UNVERIFIED, wrongSource.availability(normal()));
            assertEquals(UiIconAvailability.HOST_UNVERIFIED, missingAlias.availability(normal()));
            assertFalse(fixtureInitialized);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void delegatedOwnerCannotSatisfyDefiningLoaderAttestation() {
        final ClassLoader delegated = new ClassLoader(getClass().getClassLoader()) { };
        final var selector = dev.turboism.mapping.verification.StaticSelector.classSelector(
            "fixture.owner", UninitializedOwner.class.getName().replace('.', '/'));
        final var resolver = dev.turboism.mapping.verification.TestVerifiedResolvers.create(
            "fixture.icons", java.util.Set.of("fixture.icons"), java.util.List.of(selector), delegated);
        try (var provider = CubismNativeIconResolver.preload(resolver, "fixture.owner")) {
            assertEquals(UiIconAvailability.HOST_UNVERIFIED, provider.availability(normal()));
        }
    }

    private static NativeIconVariant normal() {
        return new NativeIconVariant(CubismIcon.ART_MESH, NativeIconVariant.Theme.LIGHT, 100, false);
    }

    private static byte[] png(final int size) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB), "png", output);
        return output.toByteArray();
    }

    private static String sha(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
