package dev.turboism.plugin.parametersync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.plugin.parametersync.domain.SyncRuleSet;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.i18n.PluginLocalization;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ParameterSyncDialogTest {

    private final PluginLocalization localization = new PluginLocalization() {
        @Override
        public Locale locale() {
            return Locale.ROOT;
        }

        @Override
        public String text(final String key) {
            return switch (key) {
                case "dialog.top.count" -> "Synchronized: %d parameter(s)";
                default -> key;
            };
        }

        @Override
        public String format(final String key, final Object... arguments) {
            return text(key);
        }

        @Override
        public boolean contains(final String key) {
            return true;
        }
    };

    @Test
    void dialogRendersAllParametersWithCheckboxes() {
        final DummyModel model = new DummyModel("m1");
        model.addParam("ParamA", -30.0f, 30.0f);
        model.addParam("ParamB", 0.0f, 1.0f);

        final SyncRuleSet ruleSet = new SyncRuleSet();
        ruleSet.setSynchronized("ParamA", true);
        ruleSet.setSynchronized("ParamB", true);

        final ParameterSyncDialog dialog = new ParameterSyncDialog(model, "ParamA", ruleSet, null, localization);

        assertEquals(2, dialog.table().getRowCount());
        assertTrue((Boolean) dialog.table().getValueAt(0, 0));
        assertTrue(((String) dialog.table().getValueAt(0, 1)).contains("ParamA"));
        assertTrue((Boolean) dialog.table().getValueAt(1, 0));
        assertTrue(((String) dialog.table().getValueAt(1, 1)).contains("ParamB"));

        assertNotNull(dialog.countLabel());
        assertTrue(dialog.countLabel().getText().contains("2"));

        dialog.dispose();
    }

    @Test
    void dialogTogglesGlobalEnabledState() {
        final DummyModel model = new DummyModel("m1");
        final SyncRuleSet ruleSet = new SyncRuleSet();
        assertTrue(ruleSet.isGlobalEnabled());

        final ParameterSyncDialog dialog = new ParameterSyncDialog(model, null, ruleSet, null, localization);

        dialog.globalEnabledCheck().doClick();
        assertFalse(ruleSet.isGlobalEnabled());

        dialog.globalEnabledCheck().doClick();
        assertTrue(ruleSet.isGlobalEnabled());

        dialog.dispose();
    }

    @Test
    void togglingCheckboxUpdatesRuleSetAndCountLabel() {
        final DummyModel model = new DummyModel("m1");
        model.addParam("ParamA", 0.0f, 1.0f);
        model.addParam("ParamB", 0.0f, 1.0f);

        final SyncRuleSet ruleSet = new SyncRuleSet();
        final ParameterSyncDialog dialog = new ParameterSyncDialog(model, null, ruleSet, null, localization);

        assertEquals(0, ruleSet.synchronizedCount());

        // Check first parameter
        dialog.table().setValueAt(true, 0, 0);
        assertTrue(ruleSet.isSynchronized("ParamA"));
        assertTrue(dialog.countLabel().getText().contains("1"));

        // Uncheck
        dialog.table().setValueAt(false, 0, 0);
        assertFalse(ruleSet.isSynchronized("ParamA"));
        assertTrue(dialog.countLabel().getText().contains("0"));

        dialog.dispose();
    }

    private static final class DummyModel implements CubismModel {
        private final String guid;
        private final Map<String, DummyParameter> params = new LinkedHashMap<>();

        DummyModel(final String guid) {
            this.guid = guid;
        }

        void addParam(final String id, final float min, final float max) {
            params.put(id, new DummyParameter(id, min, max));
        }

        @Override
        public ModelId id() {
            return new ModelId(guid);
        }

        @Override
        public String name() {
            return "DummyModel";
        }

        @Override
        public Parameters parameters() {
            return new Parameters() {
                @Override
                public List<Parameter> all() {
                    return new ArrayList<>(params.values());
                }

                @Override
                public Parameter find(final ParameterId id) {
                    final DummyParameter p = params.get(id.value());
                    if (p == null) throw new NoSuchElementException(id.value());
                    return p;
                }

                @Override
                public Optional<Parameter> findById(final ParameterId id) {
                    return Optional.ofNullable(params.get(id.value()));
                }

                @Override
                public Optional<Parameter> findById(final String id) {
                    return Optional.ofNullable(params.get(id));
                }
            };
        }

        @Override
        public Parts parts() {
            return null;
        }

        @Override
        public Drawables drawables() {
            return null;
        }

        @Override
        public Deformers deformers() {
            return null;
        }

        @Override
        public Glues glues() {
            return null;
        }

        @Override
        public void update() {}
    }

    private static final class DummyParameter implements Parameter {
        private final String id;
        private final float min;
        private final float max;
        private float value;

        DummyParameter(final String id, final float min, final float max) {
            this.id = id;
            this.min = min;
            this.max = max;
            this.value = min;
        }

        @Override
        public ParameterId id() {
            return new ParameterId(id);
        }

        @Override
        public float getValue() {
            return value;
        }

        @Override
        public void setValue(final float value) {
            this.value = value;
        }

        @Override
        public float getMinimumValue() {
            return min;
        }

        @Override
        public float getMaximumValue() {
            return max;
        }

        @Override
        public float getDefaultValue() {
            return min;
        }
    }
}
