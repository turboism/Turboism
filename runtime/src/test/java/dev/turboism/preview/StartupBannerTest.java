package dev.turboism.preview;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StartupBannerTest {

    @Test
    void rendersTheCompleteAuthoritativeStartupSummary() {
        final String banner = StartupBanner.render(new StartupBanner.Details(
            "0.43.2",
            "25.0.4",
            "25.2.4 (managed)",
            "5.3.03",
            25,
            "3 discovered; host available"
        ));

        assertTrue(banner.startsWith(" _____ _   _ ____  ____   ___ ___ ____  __  __"));
        assertTrue(banner.contains("For you, a bouquet."));
        assertTrue(banner.contains("Cubism Extensibility Framework"));
        assertTrue(banner.contains("Version   : 0.43.2"));
        assertTrue(banner.contains("Java      : 25.0.4"));
        assertTrue(banner.contains("GraalVM   : 25.2.4 (managed)"));
        assertTrue(banner.contains("Cubism    : 5.3.03"));
        assertTrue(banner.contains("Plugins   : 25"));
        assertTrue(banner.contains("GraalJS   : 3 discovered; host available"));
        assertTrue(banner.endsWith("[Turboism] Runtime initialized."));
    }

    @Test
    void publishesOnlyOncePerRuntimeProcess() {
        final StartupBanner banner = new StartupBanner();
        final List<String> published = new ArrayList<>();
        final StartupBanner.Details details = new StartupBanner.Details(
            "0.43.2", "17", "unavailable (standard JVM)", "5.2.03", 4,
            "0 discovered; host unavailable"
        );

        banner.publish(published::add, details);
        banner.publish(published::add, details);

        assertEquals(1, published.size());
    }

    @Test
    void readsThePackagedFrameworkVersion() {
        assertTrue(StartupBanner.frameworkVersion().matches("\\d+\\.\\d+\\.\\d+"));
    }
}
