package dev.turboism.plugin.projectinspector;

import dev.turboism.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectInspectorConsumerContractTest {

    @Test
    void productionConsumerRemainsLockedToPredecessorServices() throws Exception {
        final String source = Files.readString(Path.of(
            "src/main/java/dev/turboism/plugin/projectinspector/ProjectInspectorPlugin.java"
        ));

        assertFalse(source.contains("context.localization()"));
        assertFalse(source.contains("context.hostReads()"));
        assertFalse(source.contains("dev.turboism.sdk.i18n"));
        assertFalse(source.contains("dev.turboism.sdk.hostread"));
        assertTrue(source.contains("context.cubismRead()"));
    }

    @Test
    void ownsNoNewPhaseOneServiceTypes() {
        final List<Class<?>> fieldTypes = new ArrayList<>();
        for (Field field : ProjectInspectorPlugin.class.getDeclaredFields()) {
            fieldTypes.add(field.getType());
        }

        assertTrue(fieldTypes.contains(PluginContext.class));
        assertFalse(fieldTypes.stream().anyMatch(type ->
            type.getName().startsWith("dev.turboism.sdk.i18n.")
                || type.getName().startsWith("dev.turboism.sdk.hostread.")
        ));
    }

    @Test
    void catalogsContainEveryReferenceConsumerKey() throws Exception {
        final List<String> required = List.of(
            "action.refresh",
            "field.active_document",
            "field.documents",
            "field.host_read",
            "field.last_refresh",
            "field.layout_workspace",
            "status.available",
            "status.no_project_open",
            "status.reading",
            "status.unavailable",
            "status.unavailable_with_type",
            "value.none",
            "value.starting",
            "window.title"
        );
        final Path root = Path.of("src/main/resources/META-INF/turboism/i18n");
        for (String file : List.of(
            "messages.properties",
            "messages_en.properties",
            "messages_zh_Hans.properties",
            "messages_zh_Hant.properties",
            "messages_ja.properties",
            "messages_ko.properties"
        )) {
            final String catalog = Files.readString(root.resolve(file));
            for (String key : required) {
                assertTrue(catalog.lines().anyMatch(line -> line.startsWith(key + "=")),
                    file + " missing " + key);
            }
        }
    }
}
