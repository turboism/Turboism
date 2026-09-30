package dev.turboism.sdk.cubism.command;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EditorParameterizedStaticEvidenceTest {
    @Test
    void everyParameterizedCommandHasAnExplicitFailClosedEvidenceRow() throws IOException {
        Path evidence = Path.of(
            "..", "compatibility", "cubism", "editor-commands",
            "top-menu-parameterized-static-contract.tsv"
        );
        java.util.List<String> lines = Files.lines(evidence).toList();
        String[] header = lines.get(0).split("\\t", -1);
        Map<String, Integer> columns = new java.util.HashMap<>();
        for (int i = 0; i < header.length; i++) columns.put(header[i], i);
        int contractStatus = columns.get("contract_status");
        int productionStatus = columns.get("production_status");
        Map<String, String[]> rows = lines.stream()
            .skip(1)
            .map(line -> line.split("\\t", -1))
            .collect(Collectors.toMap(values -> values[0], Function.identity()));

        assertEquals(EditorParameterizedCommand.values().length, rows.size());
        for (EditorParameterizedCommand command : EditorParameterizedCommand.values()) {
            String[] row = rows.get(command.name());
            assertFalse(row == null, command.name());
            assertFalse(row[contractStatus].isBlank(), command.name());
            if (java.util.Set.of("EXTERNAL_APP_SETTING", "GRID_SETTING", "MODEL_SETTING",
                    "RESIZE_MODEL_DOCUMENT").contains(command.name())) {
                assertEquals("ENABLED_5.2.03_5.3.02", row[productionStatus], command.name());
            } else {
                assertEquals("UNAVAILABLE", row[productionStatus], command.name());
            }
            assertFalse(Arrays.stream(row).anyMatch(value -> value.contains("java.awt") || value.contains("javax.swing")));
        }
        for (String id : java.util.List.of("EXTERNAL_APP_SETTING", "GRID_SETTING", "MODEL_SETTING", "MODELING_STATISTICS", "RESIZE_MODEL_DOCUMENT")) {
            assertEquals("TYPED_CONTRACT_VERIFIED", rows.get(id)[contractStatus]);
        }
    }
}
