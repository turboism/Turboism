package dev.turboism.core.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkBudgetPolicyTest {

    private final WorkBudgetPolicy policy = new DefaultWorkBudgetPolicy();

    @Test
    void lifecycleInitIsLightweight() {
        PluginTask task = new PluginTask("lifecycle.init", "demo.plugin", "init", "none");
        assertEquals(WorkBudget.LIGHTWEIGHT, policy.classify(task));
    }

    @Test
    void lifecycleDisableIsLightweight() {
        PluginTask task = new PluginTask("lifecycle.disable", "demo.plugin", "disable", "none");
        assertEquals(WorkBudget.LIGHTWEIGHT, policy.classify(task));
    }

    @Test
    void lifecycleShutdownIsLightweight() {
        PluginTask task = new PluginTask("lifecycle.shutdown", "demo.plugin", "shutdown", "none");
        assertEquals(WorkBudget.LIGHTWEIGHT, policy.classify(task));
    }

    @Test
    void eventSubscriberIsLightweight() {
        PluginTask task = new PluginTask("event.subscribe", "demo.plugin", "onModelLoaded", "none");
        assertEquals(WorkBudget.LIGHTWEIGHT, policy.classify(task));
    }

    @Test
    void actionHandlerIsLightweight() {
        PluginTask task = new PluginTask("action.handle", "demo.plugin", "executeQuickAction", "none");
        assertEquals(WorkBudget.LIGHTWEIGHT, policy.classify(task));
    }

    @Test
    void uiSchedulingIsLightweight() {
        PluginTask task = new PluginTask("ui.schedule", "demo.plugin", "schedulePanelUpdate", "none");
        assertEquals(WorkBudget.LIGHTWEIGHT, policy.classify(task));
    }

    @Test
    void networkTaskWithSidecarCapabilityIsSidecar() {
        PluginTask task = new PluginTask("network", "demo.plugin", "fetchRemote", "sidecar");
        assertEquals(WorkBudget.SIDECAR, policy.classify(task));
    }

    @Test
    void aiTaskWithSidecarCapabilityIsSidecar() {
        PluginTask task = new PluginTask("ai", "demo.plugin", "runInference", "sidecar");
        assertEquals(WorkBudget.SIDECAR, policy.classify(task));
    }

    @Test
    void fileScanTaskWithSidecarCapabilityIsSidecar() {
        PluginTask task = new PluginTask("file-scan", "demo.plugin", "scanWorkspace", "sidecar");
        assertEquals(WorkBudget.SIDECAR, policy.classify(task));
    }

    @Test
    void heavyAnalysisTaskWithSidecarCapabilityIsSidecar() {
        PluginTask task = new PluginTask("heavy-analysis", "demo.plugin", "computeStatistics", "sidecar");
        assertEquals(WorkBudget.SIDECAR, policy.classify(task));
    }

    @Test
    void networkTaskWithoutSidecarCapabilityIsRejected() {
        PluginTask task = new PluginTask("network", "demo.plugin", "fetchRemote", "none");
        assertEquals(WorkBudget.REJECTED, policy.classify(task));
    }

    @Test
    void aiTaskWithoutSidecarCapabilityIsRejected() {
        PluginTask task = new PluginTask("ai", "demo.plugin", "runInference", "none");
        assertEquals(WorkBudget.REJECTED, policy.classify(task));
    }

    @Test
    void fileScanTaskWithoutSidecarCapabilityIsRejected() {
        PluginTask task = new PluginTask("file-scan", "demo.plugin", "scanWorkspace", "none");
        assertEquals(WorkBudget.REJECTED, policy.classify(task));
    }

    @Test
    void heavyAnalysisTaskWithoutSidecarCapabilityIsRejected() {
        PluginTask task = new PluginTask("heavy-analysis", "demo.plugin", "computeStatistics", "none");
        assertEquals(WorkBudget.REJECTED, policy.classify(task));
    }
}
