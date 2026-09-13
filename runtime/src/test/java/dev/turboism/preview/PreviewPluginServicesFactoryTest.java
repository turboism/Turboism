package dev.turboism.preview;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.userfile.UserFileGrantSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

final class PreviewPluginServicesFactoryTest {

    private static final UserFileRequest REQUEST = new UserFileRequest(
        "req", "title", List.of("cmo3"), UserFileMode.WRITE, UserFileLifetime.ONE_OPERATION);

    @Test
    void userFileGrantSourceStaysUnavailableWithoutTheTaskProperty() {
        final String key = "turboism.preview.userFileFixedGrant";
        final String previous = System.getProperty(key);
        try {
            System.clearProperty(key);
            final var decision = PreviewPluginServicesFactory.userFileGrantSource()
                .request(REQUEST).toCompletableFuture().join();
            assertInstanceOf(UserFileGrantSource.Unavailable.class, decision);
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }

    @Test
    void userFileGrantSourceHonorsTheTaskPinnedFixedGrant() {
        final String key = "turboism.preview.userFileFixedGrant";
        final String previous = System.getProperty(key);
        try {
            System.setProperty(key, Path.of("task-dir", "persisted.cmo3").toString());
            final var decision = PreviewPluginServicesFactory.userFileGrantSource()
                .request(REQUEST).toCompletableFuture().join();
            assertInstanceOf(UserFileGrantSource.Selected.class, decision);
            assertEquals(
                Path.of("task-dir", "persisted.cmo3"),
                ((UserFileGrantSource.Selected) decision).path()
            );
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }

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
