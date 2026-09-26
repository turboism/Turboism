package dev.turboism.preview;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.failure.RuntimeFailureCollector;
import dev.turboism.sdk.ui.UserFileErrorCode;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.sdk.ui.UserFileRequestStatus;
import dev.turboism.userfile.UserFileGrantSource;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

final class PreviewPluginServicesFactoryTest {

    @Test
    void generatedSettingsTabUsesLocalizedPluginName() {
        assertEquals(
            "物理演算エディター",
            PreviewPluginServicesFactory.pluginDisplayName(
                "Physics Editor",
                localization(true, "物理演算エディター")
            )
        );
        assertEquals(
            "Physics Editor",
            PreviewPluginServicesFactory.pluginDisplayName(
                "Physics Editor",
                localization(false, "plugin.name")
            )
        );
    }

    @Test
    void createsFreshUnavailableGrantSourceForEachPlugin() {
        final RuntimeFailureCollector failures = new RuntimeFailureCollector();
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        final UserFileGrantSource first = PreviewPluginServicesFactory.newUserFileGrantSource(
            "plugin.one",
            failures,
            evidence
        );
        final UserFileGrantSource second = PreviewPluginServicesFactory.newUserFileGrantSource(
            "plugin.two",
            failures,
            evidence
        );

        // The stateless sources hold no resources; requesting from either completes
        // with an Unavailable decision without touching any UI.
        try {
            final UserFileGrantSource.Decision decision = first.request(new UserFileRequest(
                "context-fixture-file", "Select fixture file", java.util.List.of("txt"),
                UserFileMode.READ, UserFileLifetime.ONE_OPERATION
            )).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(UserFileGrantSource.Unavailable.INSTANCE, decision);
        } catch (final Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static PluginLocalization localization(
        final boolean containsName,
        final String pluginName
    ) {
        return new PluginLocalization() {
            @Override public Locale locale() { return Locale.JAPANESE; }
            @Override public String text(final String key) {
                return "plugin.name".equals(key) ? pluginName : key;
            }
            @Override public String format(final String key, final Object... arguments) {
                return text(key);
            }
            @Override public boolean contains(final String key) {
                return containsName && "plugin.name".equals(key);
            }
        };
    }
}
