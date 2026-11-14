package dev.turboism.sdk.cubism.service.query;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ServiceInterfaceShapeTest {

    private static final List<String> QUERY_SERVICE_SOURCES =
            List.of("SelectionQueryService", "ModelHierarchyQueryService");

    private static final Set<String> MUTATING_PREFIXES =
            Set.of("set", "add", "remove", "mutate", "write", "delete", "create", "update", "commit", "rollback");

    @Test
    void queryServiceMethodsUseReadOnlyNames_whenInspectingSourceContract() throws IOException {
        for (String sourceName : QUERY_SERVICE_SOURCES) {
            String source = queryServiceSource(sourceName);

            for (String prefix : MUTATING_PREFIXES) {
                assertTrue(
                        !Pattern.compile("\\b" + prefix + "[A-Z][A-Za-z0-9_]*\\s*\\(")
                                .matcher(source)
                                .find(),
                        () -> sourceName + " must not expose a mutating method prefix: " + prefix);
            }
        }
    }

    private static String queryServiceSource(String sourceName) throws IOException {
        return Files.readString(Path.of("src/main/java/dev/turboism/sdk/cubism/service/query", sourceName + ".java"));
    }
}
