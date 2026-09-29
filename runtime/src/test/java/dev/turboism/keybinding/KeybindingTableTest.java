package dev.turboism.keybinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.internal.core.KeybindingService;
import java.util.List;
import java.util.Map;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.Test;

class KeybindingTableTest {

    private static final String PLUGIN = "dev.example.plugin";
    private static final String ACTION = "example.run";
    private static final String KEY = PLUGIN + '\0' + ACTION;

    private static KeybindingTable.PluginRow pluginRow(final String key, final String declared) {
        final int split = key.indexOf('\0');
        return new KeybindingTable.PluginRow(
                key, key.substring(0, split), key.substring(split + 1), "Example", "Run", declared);
    }

    private static KeybindingTable.NativeRow nativeRow(final String id, final String stroke) {
        return new KeybindingTable.NativeRow(id, id, stroke, KeybindingTable.State.UNSET, "", false, 0);
    }

    private static KeyStroke stroke(final String text) {
        return KeyStrokeCodec.parse(text);
    }

    private static KeybindingService.Row row(final KeybindingTable table, final String rowId) {
        return table.describe().stream()
                .filter(candidate -> candidate.id().equals(rowId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void declaredPluginDefaultIsEffectiveWithoutUserBinding() {
        KeybindingTable table = new KeybindingTable();
        table.setPluginRows(Map.of(KEY, pluginRow(KEY, "Ctrl+T")));

        assertEquals(
                stroke("Ctrl+T"),
                table.view().pluginByStroke().keySet().stream().findFirst().orElseThrow());
        KeybindingService.Row row = row(table, KeybindingTable.pluginRowId(KEY));
        assertEquals("Ctrl+T", row.declaredStroke());
        assertEquals("Ctrl+T", row.effectiveStroke());
        assertFalse(row.disabled());
        assertFalse(row.conflict());
    }

    @Test
    void userBindingOverridesDeclaredDefault() {
        KeybindingTable table = new KeybindingTable();
        table.setPluginRows(Map.of(KEY, pluginRow(KEY, "Ctrl+T")));
        table.setPluginBinding(KEY, KeybindingTable.State.BOUND, "F6");

        assertTrue(table.view().pluginByStroke().containsKey(stroke("F6")));
        assertFalse(table.view().pluginByStroke().containsKey(stroke("Ctrl+T")));
        assertEquals("F6", row(table, KeybindingTable.pluginRowId(KEY)).effectiveStroke());
    }

    @Test
    void disabledPluginRowStopsDispatch() {
        KeybindingTable table = new KeybindingTable();
        table.setPluginRows(Map.of(KEY, pluginRow(KEY, "Ctrl+T")));
        table.setPluginBinding(KEY, KeybindingTable.State.DISABLED, "");

        assertTrue(table.view().pluginByStroke().isEmpty());
        KeybindingService.Row row = row(table, KeybindingTable.pluginRowId(KEY));
        assertTrue(row.disabled());
        assertEquals("", row.effectiveStroke());
    }

    @Test
    void unsetNativeRowPassesThrough() {
        KeybindingTable table = new KeybindingTable();
        table.putNativeRow(nativeRow("save", "Ctrl+S"));

        assertTrue(table.view().nativeByStroke().isEmpty());
        assertTrue(table.view().suppressed().isEmpty());
        KeybindingService.Row row = row(table, KeybindingTable.nativeRowId("save"));
        assertEquals("Ctrl+S", row.nativeStroke());
        assertEquals("Ctrl+S", row.effectiveStroke());
    }

    @Test
    void boundNativeRowTranslatesAndSuppressesOriginal() {
        KeybindingTable table = new KeybindingTable();
        table.putNativeRow(nativeRow("save", "Ctrl+S"));
        table.setNativeBinding("save", KeybindingTable.State.BOUND, "F2");

        assertEquals("save", table.view().nativeByStroke().get(stroke("F2")).id());
        assertTrue(table.view().suppressed().contains(stroke("Ctrl+S")));
        assertEquals("F2", row(table, KeybindingTable.nativeRowId("save")).effectiveStroke());
    }

    @Test
    void disabledNativeRowSuppressesNativeStroke() {
        KeybindingTable table = new KeybindingTable();
        table.putNativeRow(nativeRow("save", "Ctrl+S"));
        table.setNativeBinding("save", KeybindingTable.State.DISABLED, "");

        assertTrue(table.view().nativeByStroke().isEmpty());
        assertTrue(table.view().suppressed().contains(stroke("Ctrl+S")));
        assertTrue(row(table, KeybindingTable.nativeRowId("save")).disabled());
    }

    @Test
    void pluginBindingOutranksNativePassthrough() {
        KeybindingTable table = new KeybindingTable();
        table.setPluginRows(Map.of(KEY, pluginRow(KEY, "")));
        table.putNativeRow(nativeRow("save", "Ctrl+S"));
        table.setPluginBinding(KEY, KeybindingTable.State.BOUND, "Ctrl+S");

        assertEquals(KEY, table.view().pluginByStroke().get(stroke("Ctrl+S")).key());
        assertTrue(table.view().conflicted().contains("save"));
        assertFalse(table.view().conflicted().contains(KEY));
        assertTrue(row(table, KeybindingTable.nativeRowId("save")).conflict());
    }

    @Test
    void pluginDeclaredDefaultOutranksNativeRebind() {
        KeybindingTable table = new KeybindingTable();
        table.setPluginRows(Map.of(KEY, pluginRow(KEY, "F2")));
        table.putNativeRow(nativeRow("save", "Ctrl+S"));
        table.setNativeBinding("save", KeybindingTable.State.BOUND, "F2");

        assertEquals(KEY, table.view().pluginByStroke().get(stroke("F2")).key());
        assertTrue(table.view().nativeByStroke().isEmpty());
        assertTrue(table.view().conflicted().contains("save"));
    }

    @Test
    void userPluginBindingReplacesDeclaredDefaultClaim() {
        // a user-bound plugin binding replaces the declared stroke entirely: the declared
        // F2 no longer claims the key, so the native rebind there wins unopposed
        KeybindingTable table = new KeybindingTable();
        table.setPluginRows(Map.of(KEY, pluginRow(KEY, "F2")));
        table.putNativeRow(nativeRow("save", "Ctrl+S"));
        table.setNativeBinding("save", KeybindingTable.State.BOUND, "F2");
        table.setPluginBinding(KEY, KeybindingTable.State.BOUND, "F3");

        assertEquals(KEY, table.view().pluginByStroke().get(stroke("F3")).key());
        assertEquals("save", table.view().nativeByStroke().get(stroke("F2")).id());
        assertTrue(table.view().conflicted().isEmpty());
    }

    @Test
    void plainStrokeBindingsSwallowTypedCharacters() {
        KeybindingTable table = new KeybindingTable();
        table.setPluginRows(Map.of(KEY, pluginRow(KEY, "")));
        table.setPluginBinding(KEY, KeybindingTable.State.BOUND, "X");
        assertTrue(table.view().swallowedTyped().contains('x'));

        table.setPluginBinding(KEY, KeybindingTable.State.BOUND, "Ctrl+X");
        assertFalse(table.view().swallowedTyped().contains('x'));
    }

    @Test
    void reboundNativePlainStrokeSwallowsTypedCharacter() {
        KeybindingTable table = new KeybindingTable();
        table.putNativeRow(nativeRow("save", "Ctrl+S"));
        table.setNativeBinding("save", KeybindingTable.State.BOUND, "G");
        assertTrue(table.view().swallowedTyped().contains('g'));
    }

    @Test
    void rowIdsRoundTrip() {
        assertEquals("plugin:dev.example.plugin/example.run", KeybindingTable.pluginRowId(KEY));
        assertEquals(KEY, KeybindingTable.pluginKey("plugin:dev.example.plugin/example.run"));
        assertEquals("native:save", KeybindingTable.nativeRowId("save"));
        assertEquals("save", KeybindingTable.nativeId("native:save"));
        assertEquals("odd/id", KeybindingTable.nativeId(KeybindingTable.nativeRowId("odd/id")));
    }

    @Test
    void onlyCustomNativeRowsAreRemovable() {
        KeybindingTable table = new KeybindingTable();
        table.putNativeRow(nativeRow("save", "Ctrl+S"));
        table.putNativeRow(new KeybindingTable.NativeRow(
                "custom.1", "Custom", "Ctrl+Alt+1", KeybindingTable.State.UNSET, "", true, 1));

        table.removeNativeRow("custom.1");
        assertFalse(table.nativeRows().containsKey("custom.1"));
        assertThrows(IllegalStateException.class, () -> table.removeNativeRow("save"));
    }

    @Test
    void restoredPluginBindingAppliesOnceActionRegisters() {
        KeybindingTable table = new KeybindingTable();
        List<String> rejected = table.restorePluginBindings(Map.of(KEY, "BOUND:F7"));
        assertTrue(rejected.isEmpty());
        assertTrue(table.view().pluginByStroke().isEmpty());

        table.setPluginRows(Map.of(KEY, pluginRow(KEY, "Ctrl+T")));
        assertTrue(table.view().pluginByStroke().containsKey(stroke("F7")));
        assertFalse(table.view().pluginByStroke().containsKey(stroke("Ctrl+T")));
    }

    @Test
    void restoredInvalidPluginBindingIsReported() {
        KeybindingTable table = new KeybindingTable();
        List<String> rejected = table.restorePluginBindings(Map.of(KEY, "garbage"));
        assertEquals(List.of(KEY), rejected);
    }

    @Test
    void pluginBindingStatesRoundTripForPersistence() {
        KeybindingTable table = new KeybindingTable();
        table.setPluginRows(Map.of(KEY, pluginRow(KEY, "")));
        table.setPluginBinding(KEY, KeybindingTable.State.BOUND, "Ctrl+Alt+K");
        assertEquals(Map.of(KEY, "BOUND:Ctrl+Alt+K"), table.pluginBindingStates());
        table.setPluginBinding(KEY, KeybindingTable.State.DISABLED, "");
        assertEquals(Map.of(KEY, "DISABLED"), table.pluginBindingStates());
        table.setPluginBinding(KEY, KeybindingTable.State.UNSET, "");
        assertTrue(table.pluginBindingStates().isEmpty());
    }

    @Test
    void describeOrdersPluginsBeforeNatives() {
        KeybindingTable table = new KeybindingTable();
        table.putNativeRow(nativeRow("save", "Ctrl+S"));
        table.setPluginRows(Map.of(KEY, pluginRow(KEY, "")));
        List<KeybindingService.Row> rows = table.describe();
        assertEquals(KeybindingService.Scope.PLUGIN, rows.get(0).scope());
        assertEquals(KeybindingService.Scope.NATIVE, rows.get(1).scope());
        assertFalse(rows.get(0).removable());
    }

    @Test
    void pluginBindingRequiresRegisteredRow() {
        KeybindingTable table = new KeybindingTable();
        assertThrows(
                IllegalArgumentException.class, () -> table.setPluginBinding(KEY, KeybindingTable.State.BOUND, "F1"));
    }

    @Test
    void setNativeStrokeChangesTranslationTarget() {
        KeybindingTable table = new KeybindingTable();
        table.putNativeRow(nativeRow("save", "Ctrl+S"));
        table.setNativeStroke("save", "Ctrl+Alt+S");
        assertEquals(
                "Ctrl+Alt+S", row(table, KeybindingTable.nativeRowId("save")).nativeStroke());
    }
}
