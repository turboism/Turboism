package dev.turboism.ui.resource;

import dev.turboism.adapter.ui.ThemeStatusAdapter;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.ui.resource.CubismIcon;
import dev.turboism.sdk.ui.resource.UiIconAvailability;
import dev.turboism.sdk.ui.resource.UiIconRef;
import dev.turboism.sdk.ui.resource.UiResourceService;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeUiResourceServiceTest {
    private static final UiIconRef ART_MESH = new UiIconRef(CubismIcon.ART_MESH);

    @Test
    void unavailableServicePreservesTheSdkCompatibilityDefault() {
        final RuntimeUiResourceService service = RuntimeUiResourceService.unavailable();

        assertSame(service, RuntimeUiResourceService.unavailable());
        assertEquals(UiIconAvailability.SERVICE_UNAVAILABLE, service.availability(ART_MESH));
        assertTrue(service.resolve(ART_MESH, false).isEmpty());
        assertSame(UiResourceService.unavailable(), UiResourceService.unavailable());
        assertEquals(
            UiIconAvailability.SERVICE_UNAVAILABLE,
            UiResourceService.unavailable().availability(ART_MESH)
        );
    }

    @Test
    void sdkViewIsStableNonCloseableAndOwnerControlsShutdown() {
        final NativeIconVariant key =
            new NativeIconVariant(CubismIcon.ART_MESH, NativeIconVariant.Theme.LIGHT, 100, false);
        final RuntimeUiResourceService owner = new RuntimeUiResourceService(
            new CubismNativeIconResolver(
                Map.of(key, image(16, 0xff123456)),
                UiIconAvailability.RESOURCE_UNAVAILABLE
            )
        );
        final UiResourceService view = owner.sdkView();

        assertFalse(view instanceof AutoCloseable);
        assertSame(view, owner.sdkView());
        assertEquals(UiIconAvailability.AVAILABLE, view.availability(ART_MESH));

        owner.close();

        assertEquals(UiIconAvailability.SERVICE_UNAVAILABLE, view.availability(ART_MESH));
        assertTrue(owner.resolve(ART_MESH, false).isEmpty());
    }

    @Test
    void reflectsExternalResolverDisposalWithoutStaleAvailability() {
        final NativeIconVariant key =
            new NativeIconVariant(CubismIcon.ART_MESH, NativeIconVariant.Theme.LIGHT, 100, false);
        final CubismNativeIconResolver resolver = new CubismNativeIconResolver(
            Map.of(key, image(16, 0xff123456)),
            UiIconAvailability.RESOURCE_UNAVAILABLE
        );
        final RuntimeUiResourceService owner = new RuntimeUiResourceService(resolver);
        final Icon oldHandle = owner.resolve(ART_MESH, false).orElseThrow();

        assertEquals(UiIconAvailability.AVAILABLE, owner.availability(ART_MESH));
        resolver.close();

        assertEquals(UiIconAvailability.SERVICE_UNAVAILABLE, owner.availability(ART_MESH));
        assertTrue(owner.resolve(ART_MESH, false).isEmpty());
        assertFalse(hasPaintedPixel(oldHandle));
        owner.close();
    }

    @Test
    void preservesMissingAndUnreviewedAvailabilityReasons() {
        try (
            RuntimeUiResourceService missing = new RuntimeUiResourceService(
                new CubismNativeIconResolver(Map.of(), UiIconAvailability.RESOURCE_UNAVAILABLE)
            );
            RuntimeUiResourceService unreviewed = new RuntimeUiResourceService(
                new CubismNativeIconResolver(Map.of(), UiIconAvailability.HOST_UNVERIFIED)
            )
        ) {
            assertEquals(UiIconAvailability.RESOURCE_UNAVAILABLE, missing.availability(ART_MESH));
            assertTrue(missing.resolve(ART_MESH, false).isEmpty());
            assertEquals(UiIconAvailability.HOST_UNVERIFIED, unreviewed.availability(ART_MESH));
            assertTrue(unreviewed.resolve(ART_MESH, true).isEmpty());
        }
    }

    @Test
    void selectsReviewedThemeScaleAndDisabledVariantsWithoutQueryIo() {
        final AtomicInteger themeReads = new AtomicInteger();
        final AtomicInteger scaleReads = new AtomicInteger();
        final NativeIconVariant enabled =
            new NativeIconVariant(CubismIcon.ART_MESH, NativeIconVariant.Theme.DARK, 150, false);
        final NativeIconVariant disabled =
            new NativeIconVariant(CubismIcon.ART_MESH, NativeIconVariant.Theme.DARK, 150, true);
        final CubismNativeIconResolver resolver = new CubismNativeIconResolver(
            Map.of(
                enabled, image(24, 0xff123456),
                disabled, image(24, 0xff654321)
            ),
            UiIconAvailability.RESOURCE_UNAVAILABLE
        );
        final ThemeStatusAdapter theme = () -> {
            themeReads.incrementAndGet();
            return ThemeStatusAdapter.AdapterResult.available(
                Optional.of(new ThemeStatusSnapshot("dark", "Dark", true))
            );
        };
        final RuntimeUiResourceService service = RuntimeUiResourceService.connected(
            resolver,
            theme,
            () -> {
                scaleReads.incrementAndGet();
                return 150;
            }
        );

        assertEquals(1, themeReads.get());
        assertEquals(1, scaleReads.get());
        assertEquals(UiIconAvailability.AVAILABLE, service.availability(ART_MESH));
        final Icon normal = service.resolve(ART_MESH, false).orElseThrow();
        final Icon normalAgain = service.resolve(ART_MESH, false).orElseThrow();
        final Icon disabledIcon = service.resolve(ART_MESH, true).orElseThrow();
        assertSame(normal, normalAgain);
        assertEquals(0xff123456, paintedColor(normal));
        assertEquals(0xff654321, paintedColor(disabledIcon));
        assertEquals(1, themeReads.get(), "theme is sampled only during off-EDT composition");
        assertEquals(1, scaleReads.get(), "DPI is sampled only during off-EDT composition");
        service.close();
    }

    @Test
    void explicitPresentationRefreshInvalidatesSelectionButQueriesRemainCached() {
        final AtomicInteger themeReads = new AtomicInteger();
        final AtomicInteger scaleReads = new AtomicInteger();
        final NativeIconVariant light100 =
            new NativeIconVariant(CubismIcon.ART_MESH, NativeIconVariant.Theme.LIGHT, 100, false);
        final NativeIconVariant dark125 =
            new NativeIconVariant(CubismIcon.ART_MESH, NativeIconVariant.Theme.DARK, 125, false);
        final CubismNativeIconResolver resolver = new CubismNativeIconResolver(
            Map.of(light100, image(16, 0xff111111), dark125, image(20, 0xff222222)),
            UiIconAvailability.RESOURCE_UNAVAILABLE
        );
        final ThemeStatusAdapter theme = () -> {
            themeReads.incrementAndGet();
            return ThemeStatusAdapter.AdapterResult.available(
                Optional.of(new ThemeStatusSnapshot("light", "Light", false))
            );
        };
        final RuntimeUiResourceService service = RuntimeUiResourceService.connected(
            resolver,
            theme,
            () -> {
                scaleReads.incrementAndGet();
                return 100;
            }
        );

        assertEquals(0xff111111, paintedColor(service.resolve(ART_MESH, false).orElseThrow()));
        service.refreshPresentation();
        assertEquals(2, themeReads.get());
        assertEquals(2, scaleReads.get());
        assertEquals(0xff111111, paintedColor(service.resolve(ART_MESH, false).orElseThrow()));
        final Icon oldHandle = service.resolve(ART_MESH, false).orElseThrow();

        service.updatePresentationFromHost(new ThemeStatusSnapshot("dark", "Dark", true), 125);
        assertEquals(0xff222222, paintedColor(service.resolve(ART_MESH, false).orElseThrow()));
        final Icon refreshedHandle = service.resolve(ART_MESH, false).orElseThrow();
        assertNotSame(oldHandle, refreshedHandle, "presentation changes require renderer re-resolution");
        assertEquals(2, themeReads.get(), "presentation queries do not re-read the host");
        assertEquals(2, scaleReads.get(), "presentation queries do not re-read the DPI source");
        service.close();
    }

    @Test
    void closeWinsOverAnInFlightRefreshAndReleasesHostBoundReferences() throws Exception {
        final CubismNativeIconResolver resolver = new CubismNativeIconResolver(
            Map.of(),
            UiIconAvailability.RESOURCE_UNAVAILABLE
        );
        final AtomicInteger themeReads = new AtomicInteger();
        final AtomicInteger scaleReads = new AtomicInteger();
        final AtomicBoolean blockRefresh = new AtomicBoolean();
        final CountDownLatch refreshStarted = new CountDownLatch(1);
        final CountDownLatch releaseRefresh = new CountDownLatch(1);
        final AtomicReference<Throwable> refreshFailure = new AtomicReference<>();
        final ThemeStatusAdapter theme = () -> {
            themeReads.incrementAndGet();
            if (blockRefresh.get()) {
                refreshStarted.countDown();
                try {
                    if (!releaseRefresh.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("refresh did not receive its release");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            return ThemeStatusAdapter.AdapterResult.available(
                Optional.of(new ThemeStatusSnapshot("light", "Light", false))
            );
        };
        final RuntimeUiResourceService service = RuntimeUiResourceService.connected(
            resolver,
            theme,
            () -> {
                scaleReads.incrementAndGet();
                return 100;
            }
        );
        blockRefresh.set(true);
        final Thread refresh = new Thread(() -> {
            try {
                service.refreshPresentation();
            } catch (Throwable failure) {
                refreshFailure.set(failure);
            }
        });
        refresh.start();

        assertTrue(refreshStarted.await(5, TimeUnit.SECONDS));
        service.close();
        assertNull(privateField(service, "resolver"));
        assertNull(privateField(service, "presentationSource"));

        releaseRefresh.countDown();
        refresh.join(5_000);
        assertFalse(refresh.isAlive());
        assertNull(refreshFailure.get());
        final int themeReadsAfterInFlightRefresh = themeReads.get();
        final int scaleReadsAfterInFlightRefresh = scaleReads.get();
        service.refreshPresentation();
        assertEquals(themeReadsAfterInFlightRefresh, themeReads.get());
        assertEquals(scaleReadsAfterInFlightRefresh, scaleReads.get());
        assertEquals(UiIconAvailability.SERVICE_UNAVAILABLE, service.availability(ART_MESH));
    }

    @Test
    void invalidThemeAndScaleFallBackToLightAt100Percent() {
        final NativeIconVariant fallback =
            new NativeIconVariant(CubismIcon.ART_MESH, NativeIconVariant.Theme.LIGHT, 100, false);
        final CubismNativeIconResolver resolver = new CubismNativeIconResolver(
            Map.of(fallback, image(16, 0xffabcdef)),
            UiIconAvailability.RESOURCE_UNAVAILABLE
        );
        final RuntimeUiResourceService service = new RuntimeUiResourceService(
            resolver,
            null,
            137
        );

        assertEquals(UiIconAvailability.AVAILABLE, service.availability(ART_MESH));
        assertEquals(0xffabcdef, paintedColor(service.resolve(ART_MESH, false).orElseThrow()));

        service.updatePresentationFromHost(null, 201);
        assertEquals(UiIconAvailability.AVAILABLE, service.availability(ART_MESH));
        service.close();
    }

    @Test
    void hostPresentationSamplingIsRejectedOnTheEdtBeforeAnyHostRead() throws Exception {
        final AtomicInteger themeReads = new AtomicInteger();
        final AtomicInteger scaleReads = new AtomicInteger();
        final ThemeStatusAdapter theme = () -> {
            themeReads.incrementAndGet();
            return ThemeStatusAdapter.AdapterResult.available(
                Optional.of(new ThemeStatusSnapshot("dark", "Dark", true))
            );
        };
        final CubismNativeIconResolver resolver = new CubismNativeIconResolver(
            Map.of(),
            UiIconAvailability.RESOURCE_UNAVAILABLE
        );

        SwingUtilities.invokeAndWait(() -> assertThrows(
            IllegalStateException.class,
            () -> RuntimeUiResourceService.connected(resolver, theme, () -> {
                scaleReads.incrementAndGet();
                return 100;
            })
        ));
        assertEquals(0, themeReads.get());
        assertEquals(0, scaleReads.get());
        resolver.close();
    }

    @Test
    void closeMakesTheServiceUnavailableAndInvalidatesOldHandles() {
        final NativeIconVariant key =
            new NativeIconVariant(CubismIcon.ART_MESH, NativeIconVariant.Theme.LIGHT, 100, false);
        final CubismNativeIconResolver resolver = new CubismNativeIconResolver(
            Map.of(key, image(16, 0xffff0000)),
            UiIconAvailability.RESOURCE_UNAVAILABLE
        );
        final RuntimeUiResourceService service = new RuntimeUiResourceService(resolver);
        final Icon oldHandle = service.resolve(ART_MESH, false).orElseThrow();

        service.close();

        assertEquals(UiIconAvailability.SERVICE_UNAVAILABLE, service.availability(ART_MESH));
        assertTrue(service.resolve(ART_MESH, false).isEmpty());
        assertFalse(hasPaintedPixel(oldHandle));
        service.close();
    }

    private static BufferedImage image(final int size, final int color) {
        final BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(color, true));
        graphics.fillRect(0, 0, size, size);
        graphics.dispose();
        return image;
    }

    private static int paintedColor(final Icon icon) {
        final BufferedImage canvas = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = canvas.createGraphics();
        icon.paintIcon(null, graphics, 0, 0);
        graphics.dispose();
        return canvas.getRGB(8, 8);
    }

    private static boolean hasPaintedPixel(final Icon icon) {
        final BufferedImage canvas = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = canvas.createGraphics();
        icon.paintIcon(null, graphics, 0, 0);
        graphics.dispose();
        for (int x = 0; x < canvas.getWidth(); x++) {
            for (int y = 0; y < canvas.getHeight(); y++) {
                if (canvas.getRGB(x, y) != 0) return true;
            }
        }
        return false;
    }

    private static Object privateField(final Object target, final String name) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
