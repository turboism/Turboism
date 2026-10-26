package dev.turboism.plugin.parametersync.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.plugin.parametersync.domain.SyncRuleSet;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Parameter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParameterSyncEngineTest {

    private ParameterSyncEngine engine;
    private SyncRuleSet ruleSet;
    private Map<String, SimpleParameter> params;

    @BeforeEach
    void setUp() {
        engine = new ParameterSyncEngine();
        ruleSet = new SyncRuleSet();
        params = new HashMap<>();
    }

    private SimpleParameter createParam(final String id, final float min, final float max, final float initial) {
        final SimpleParameter param = new SimpleParameter(id, min, max, initial);
        params.put(id, param);
        return param;
    }

    private Optional<Parameter> lookup(final String id) {
        return Optional.ofNullable(params.get(id));
    }

    @Test
    void forwardSyncUpdatesTargetParameter() {
        createParam("ParamEyeL", 0.0f, 1.0f, 0.0f);
        final SimpleParameter eyeR = createParam("ParamEyeR", 0.0f, 1.0f, 0.0f);

        ruleSet.setSynchronized("ParamEyeL", true);
        ruleSet.setSynchronized("ParamEyeR", true);

        final Map<String, Float> writes = engine.executeSync("ParamEyeL", 0.8f, ruleSet, this::lookup);

        assertEquals(1, writes.size());
        assertEquals(0.8f, writes.get("ParamEyeR"), 1e-4f);
        assertEquals(0.8f, eyeR.getValue(), 1e-4f);
        assertTrue(engine.suppressionGuard().shouldSuppress("ParamEyeR", 0.8f));
    }

    @Test
    void bidirectionalSyncUpdatesPartnerWhenTargetChanges() {
        final SimpleParameter eyeL = createParam("ParamEyeL", 0.0f, 1.0f, 0.0f);
        createParam("ParamEyeR", 0.0f, 1.0f, 0.0f);

        ruleSet.setSynchronized("ParamEyeL", true);
        ruleSet.setSynchronized("ParamEyeR", true);

        final Map<String, Float> writes = engine.executeSync("ParamEyeR", 0.65f, ruleSet, this::lookup);

        assertEquals(1, writes.size());
        assertEquals(0.65f, writes.get("ParamEyeL"), 1e-4f);
        assertEquals(0.65f, eyeL.getValue(), 1e-4f);
        assertTrue(engine.suppressionGuard().shouldSuppress("ParamEyeL", 0.65f));
    }

    @Test
    void multiTargetSyncDrivesAllSynchronizedParametersSimultaneously() {
        createParam("MasterAngleX", -30.0f, 30.0f, 0.0f);
        final SimpleParameter followerBody = createParam("ParamBodyAngleX", -10.0f, 10.0f, 0.0f);
        final SimpleParameter followerEye = createParam("ParamEyeBallX", -1.0f, 1.0f, 0.0f);

        ruleSet.setSynchronized("MasterAngleX", true);
        ruleSet.setSynchronized("ParamBodyAngleX", true);
        ruleSet.setSynchronized("ParamEyeBallX", true);

        // Move MasterAngleX to 15 (75% in [-30, 30])
        final Map<String, Float> writes = engine.executeSync("MasterAngleX", 15.0f, ruleSet, this::lookup);

        assertEquals(2, writes.size());
        // BodyAngleX 75% in [-10, 10] is 5.0
        assertEquals(5.0f, followerBody.getValue(), 1e-4f);
        // EyeBallX 75% in [-1, 1] is 0.5
        assertEquals(0.5f, followerEye.getValue(), 1e-4f);

        assertTrue(engine.suppressionGuard().shouldSuppress("ParamBodyAngleX", 5.0f));
        assertTrue(engine.suppressionGuard().shouldSuppress("ParamEyeBallX", 0.5f));
    }

    @Test
    void movingAnyParameterInSynchronizedGroupUpdatesAllOthers() {
        final SimpleParameter master = createParam("MasterAngleX", -30.0f, 30.0f, 0.0f);
        createParam("ParamBodyAngleX", -10.0f, 10.0f, 0.0f);
        final SimpleParameter followerEye = createParam("ParamEyeBallX", -1.0f, 1.0f, 0.0f);

        ruleSet.setSynchronized("MasterAngleX", true);
        ruleSet.setSynchronized("ParamBodyAngleX", true);
        ruleSet.setSynchronized("ParamEyeBallX", true);

        // User drags ParamBodyAngleX to 5.0 (75%)
        final Map<String, Float> writes = engine.executeSync("ParamBodyAngleX", 5.0f, ruleSet, this::lookup);

        assertEquals(2, writes.size());
        assertEquals(15.0f, master.getValue(), 1e-4f);
        assertEquals(0.5f, followerEye.getValue(), 1e-4f);
    }

    @Test
    void globalDisableSuppressesAllSyncing() {
        createParam("A", 0.0f, 1.0f, 0.0f);
        final SimpleParameter b = createParam("B", 0.0f, 1.0f, 0.0f);

        ruleSet.setSynchronized("A", true);
        ruleSet.setSynchronized("B", true);
        ruleSet.setGlobalEnabled(false);

        final Map<String, Float> writes = engine.executeSync("A", 0.9f, ruleSet, this::lookup);

        assertTrue(writes.isEmpty());
        assertEquals(0.0f, b.getValue());
    }

    @Test
    void unselectedParameterIsNotSynchronized() {
        createParam("A", 0.0f, 1.0f, 0.0f);
        final SimpleParameter b = createParam("B", 0.0f, 1.0f, 0.0f);

        ruleSet.setSynchronized("A", true);
        // B is not checked into sync

        final Map<String, Float> writes = engine.executeSync("A", 0.9f, ruleSet, this::lookup);

        assertTrue(writes.isEmpty());
        assertEquals(0.0f, b.getValue());
    }

    private static final class SimpleParameter implements Parameter {
        private final String id;
        private final float min;
        private final float max;
        private float value;

        SimpleParameter(final String id, final float min, final float max, final float initial) {
            this.id = id;
            this.min = min;
            this.max = max;
            this.value = initial;
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
