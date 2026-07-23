package dev.turboism.sdk.appearance;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppearanceContractTest {

    @Test
    void paletteNormalizesAndRejectsMalformedColors() {
        AppearancePalette palette = palette("#aabbcc");
        assertEquals("#AABBCC", palette.accent());
        assertThrows(IllegalArgumentException.class, () -> palette("red"));
    }

    @Test
    void unavailableServiceFailsClosedWithTypedResults() throws Exception {
        AppearanceService service = AppearanceService.unavailable();
        AppearanceRequest request = new AppearanceRequest(
            "theme.demo",
            AppearanceBase.DARK,
            palette("#112233"),
            0
        );

        assertEquals(
            AppearanceStatus.Availability.UNAVAILABLE,
            service.current().toCompletableFuture().get(1, TimeUnit.SECONDS).availability()
        );
        assertEquals(
            AppearanceApplyResult.Outcome.UNAVAILABLE,
            service.apply(request).toCompletableFuture().get(1, TimeUnit.SECONDS).outcome()
        );
        assertEquals(
            AppearanceRestoreResult.Outcome.UNAVAILABLE,
            service.restoreOwnedAppearance().toCompletableFuture().get(1, TimeUnit.SECONDS).outcome()
        );
    }

    private static AppearancePalette palette(final String accent) {
        return new AppearancePalette(
            accent,
            "#111111",
            "#222222",
            "#333333",
            "#EEEEEE",
            "#888888",
            "#444444",
            "#FFFFFF",
            "#555555",
            "#000000"
        );
    }
}
