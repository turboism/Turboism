package dev.turboism.plugin.uitheme.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.turboism.plugin.uitheme.domain.ThemeBase;
import dev.turboism.plugin.uitheme.domain.ThemeIcons;
import dev.turboism.plugin.uitheme.domain.ThemePackageData;
import dev.turboism.plugin.uitheme.domain.ThemePackageMetadata;
import dev.turboism.test.storage.InMemoryPluginStorage;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ThemePackageRepositoryTest {

    @Test
    void savingAndLoadingAThemeUsesBoundedAtomicPluginStorage() {
        InMemoryPluginStorage storage = new InMemoryPluginStorage();
        ThemePackageRepository repository = new ThemePackageRepository(storage);

        ThemePackageRepository.SaveResult saved = repository.save(theme("user.aurora"), false);
        Optional<ThemePackageData> loaded = repository.find("user.aurora");

        assertEquals(ThemePackageRepository.SaveOutcome.SAVED, saved.outcome());
        assertEquals(theme("user.aurora"), loaded.orElseThrow());
        assertEquals(1, storage.files.size());
        assertFalse(storage.lastWritePath.relativePath().contains(".."));
    }

    @Test
    void savingAnExistingThemeWithoutReplacementReportsConflictAndPreservesIt() {
        InMemoryPluginStorage storage = new InMemoryPluginStorage();
        ThemePackageRepository repository = new ThemePackageRepository(storage);
        repository.save(theme("user.aurora"), false);

        ThemePackageRepository.SaveResult result = repository.save(theme("user.aurora"), false);

        assertEquals(ThemePackageRepository.SaveOutcome.CONFLICT, result.outcome());
        assertEquals(theme("user.aurora"), repository.find("user.aurora").orElseThrow());
    }

    @Test
    void listsValidArchivesAndDeletesOnePackage() {
        InMemoryPluginStorage storage = new InMemoryPluginStorage();
        ThemePackageRepository repository = new ThemePackageRepository(storage);
        repository.save(theme("user.aurora"), false);
        repository.save(theme("user.dusk"), false);

        assertEquals(
                java.util.List.of("user.aurora", "user.dusk"),
                repository.list().stream().map(data -> data.metadata().id()).toList());
        assertEquals(
                ThemePackageRepository.DeleteOutcome.DELETED,
                repository.delete("user.aurora").outcome());
        assertEquals(Optional.empty(), repository.find("user.aurora"));
    }

    private static ThemePackageData theme(final String id) {
        return new ThemePackageData(
                new ThemePackageMetadata(
                        id, "Aurora", "", "Turboism", "", "1", null, ThemeBase.DARK, ThemeIcons.LIGHT, false),
                Map.of(
                        "accent",
                        "#88C0D0",
                        "background",
                        "#2E3440",
                        "surface",
                        "#3B4252",
                        "input.background",
                        "#434C5E",
                        "foreground",
                        "#ECEFF4",
                        "foreground.muted",
                        "#D8DEE9",
                        "selection.background",
                        "#4C566A",
                        "selection.foreground",
                        "#ECEFF4",
                        "border",
                        "#4C566A",
                        "viewport.background",
                        "#242933"),
                Map.of(),
                "",
                "");
    }
}
