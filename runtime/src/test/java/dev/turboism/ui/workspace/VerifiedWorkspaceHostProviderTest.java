package dev.turboism.ui.workspace;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.ui.workspace.WorkspaceId;
import dev.turboism.sdk.ui.workspace.WorkspaceOperationResult;
import dev.turboism.sdk.ui.workspace.WorkspaceStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedWorkspaceHostProviderTest {

    @Test
    void exactProvidersReadSwitchAndRunSemanticCommands() {
        for (String version : List.of("5.2.03", "5.3.02")) {
            SyntheticApp.instance = new SyntheticApp(new SyntheticMainFrame(new SyntheticDock(
                workspace("modeling", "Modeling"),
                new java.util.ArrayList<>(List.of(workspace("modeling", "Modeling"), workspace("animation", "Animation")))
            )));
            WorkspaceHostProvider provider = provider(version, resolver(version));

            var status = provider.readStatus();
            assertEquals("modeling", status.current().orElseThrow().id().value());
            assertEquals(List.of("modeling", "animation"), status.available().stream()
                .map(workspace -> workspace.id().value()).toList());
            assertEquals(WorkspaceOperationResult.Outcome.NO_CHANGE, provider.switchTo(new WorkspaceId("modeling")));
            assertEquals(WorkspaceOperationResult.Outcome.NOT_FOUND, provider.switchTo(new WorkspaceId("missing")));
            assertEquals(WorkspaceOperationResult.Outcome.CHANGED, provider.switchTo(new WorkspaceId("animation")));
            assertEquals(WorkspaceOperationResult.Outcome.CHANGED, provider.updateDefault());
            assertEquals(WorkspaceOperationResult.Outcome.CHANGED, provider.resetToDefault());

            SyntheticDock dock = SyntheticApp.instance.frame.dock;
            assertEquals("animation", dock.current.id.idString);
            assertEquals(1, dock.changeCount);
            assertEquals(1, dock.updateCount);
            assertEquals(1, dock.resetCount);
        }
    }

    @Test
    void exactProvidersRejectWrongVersionAndFailedPostState() {
        VerifiedMemberResolver resolver52 = resolver("5.2.03");
        assertTrue(WorkspaceControlAdmission.authorizes5203(resolver52));
        assertFalse(WorkspaceControlAdmission.authorizes5302(resolver52));

        SyntheticDock dock = new SyntheticDock(
            workspace("modeling", "Modeling"),
            new java.util.ArrayList<>(List.of(workspace("modeling", "Modeling"), workspace("animation", "Animation")))
        );
        dock.ignoreChange = true;
        SyntheticApp.instance = new SyntheticApp(new SyntheticMainFrame(dock));
        assertEquals(
            WorkspaceOperationResult.Outcome.FAILED,
            provider("5.2.03", resolver52).switchTo(new WorkspaceId("animation"))
        );
    }

    @Test
    void missingWorkspaceListsFailClosedWithoutMutation() {
        SyntheticWorkspace current = workspace("current", "Current");
        SyntheticDock dock = new SyntheticDock(current, null);
        dock.custom = null;
        SyntheticApp.instance = new SyntheticApp(new SyntheticMainFrame(dock));
        WorkspaceHostProvider provider = provider("5.3.02", resolver("5.3.02"));

        var status = provider.readStatus();
        assertEquals(WorkspaceStatus.Availability.UNAVAILABLE, status.availability(),
            "a missing workspace list must never produce a partial AVAILABLE view");
        assertEquals("workspace.enumeration.incomplete", status.diagnosticCode().orElseThrow());
        assertEquals(WorkspaceOperationResult.Outcome.FAILED, provider.switchTo(new WorkspaceId("current")));
        assertEquals(WorkspaceOperationResult.Outcome.FAILED, provider.updateDefault());
        assertEquals(WorkspaceOperationResult.Outcome.FAILED, provider.resetToDefault());
        assertEquals(0, dock.changeCount, "no host mutation may run when a workspace list is missing");
        assertEquals(0, dock.updateCount);
        assertEquals(0, dock.resetCount);
    }

    @Test
    void identityLessWorkspaceItemsFailClosedInsteadOfBeingSkipped() {
        java.util.List<SyntheticWorkspace> many = new java.util.ArrayList<>();
        many.add(workspace("w1", "Workspace 1"));
        many.add(new SyntheticWorkspace(new SyntheticId(null), "Broken"));
        SyntheticWorkspace current = workspace("current", "Current");
        SyntheticDock dock = new SyntheticDock(current, many);
        SyntheticApp.instance = new SyntheticApp(new SyntheticMainFrame(dock));
        WorkspaceHostProvider provider = provider("5.3.02", resolver("5.3.02"));

        var status = provider.readStatus();
        assertEquals(WorkspaceStatus.Availability.UNAVAILABLE, status.availability(),
            "an identity-less list item must never be skipped into a partial AVAILABLE view");
        assertEquals("workspace.enumeration.incomplete", status.diagnosticCode().orElseThrow());
        assertEquals(WorkspaceOperationResult.Outcome.FAILED, provider.switchTo(new WorkspaceId("w1")),
            "a real workspace behind an identity-less item must not be mutated");
        assertEquals(WorkspaceOperationResult.Outcome.FAILED, provider.switchTo(new WorkspaceId("missing")),
            "an incomplete list must not report false NOT_FOUND");
        assertEquals(0, dock.changeCount);
    }

    @Test
    void enumerationBeyondCapFailsClosedWithoutMutation() {
        java.util.List<SyntheticWorkspace> many = new java.util.ArrayList<>();
        for (int index = 0; index < WorkspaceReflectionEngine.MAX_AVAILABLE_WORKSPACES; index++) {
            many.add(workspace("w" + index, "Workspace " + index));
        }
        SyntheticWorkspace current = workspace("current", "Current");
        SyntheticDock dock = new SyntheticDock(current, many);
        SyntheticApp.instance = new SyntheticApp(new SyntheticMainFrame(dock));
        WorkspaceHostProvider provider = provider("5.3.02", resolver("5.3.02"));

        var status = provider.readStatus();
        assertEquals(WorkspaceStatus.Availability.UNAVAILABLE, status.availability(),
            "incomplete enumeration must never produce a partial AVAILABLE view");
        assertEquals("workspace.enumeration.incomplete", status.diagnosticCode().orElseThrow());

        assertEquals(
            WorkspaceOperationResult.Outcome.FAILED,
            provider.switchTo(new WorkspaceId("w" + (WorkspaceReflectionEngine.MAX_AVAILABLE_WORKSPACES - 1))),
            "an id beyond the cap must not be reported NOT_FOUND"
        );
        assertEquals(WorkspaceOperationResult.Outcome.FAILED,
            provider.switchTo(new WorkspaceId("w5")),
            "no switch may run while enumeration is incomplete");
        assertEquals(WorkspaceOperationResult.Outcome.FAILED, provider.updateDefault());
        assertEquals(WorkspaceOperationResult.Outcome.FAILED, provider.resetToDefault());
        assertEquals(0, dock.changeCount, "no host mutation may run when enumeration is incomplete");
        assertEquals(0, dock.updateCount);
        assertEquals(0, dock.resetCount);
    }

    @Test
    void enumerationWithinCapRemainsAvailable() {
        java.util.List<SyntheticWorkspace> many = new java.util.ArrayList<>();
        for (int index = 0; index < WorkspaceReflectionEngine.MAX_AVAILABLE_WORKSPACES - 1; index++) {
            many.add(workspace("w" + index, "Workspace " + index));
        }
        SyntheticWorkspace current = workspace("current", "Current");
        SyntheticDock dock = new SyntheticDock(current, many);
        SyntheticApp.instance = new SyntheticApp(new SyntheticMainFrame(dock));
        WorkspaceHostProvider provider = provider("5.3.02", resolver("5.3.02"));

        var status = provider.readStatus();
        assertEquals(WorkspaceStatus.Availability.AVAILABLE, status.availability());
        assertEquals(WorkspaceReflectionEngine.MAX_AVAILABLE_WORKSPACES, status.available().size(),
            "cap-sized enumeration stays available");
        assertTrue(status.available().stream().anyMatch(info -> info.id().value().equals("current")),
            "the current workspace must always be included");
        assertEquals(
            WorkspaceOperationResult.Outcome.CHANGED,
            provider.switchTo(new WorkspaceId("w10"))
        );
        assertEquals(1, dock.changeCount);
    }

    @Test
    void mutatingCallsFailClosedWhenReflectionThrows() {
        SyntheticDock dock = new SyntheticDock(
            workspace("modeling", "Modeling"),
            new java.util.ArrayList<>(List.of(workspace("modeling", "Modeling"), workspace("animation", "Animation")))
        );
        dock.failCommands = true;
        SyntheticApp.instance = new SyntheticApp(new SyntheticMainFrame(dock));
        WorkspaceHostProvider provider = provider("5.3.02", resolver("5.3.02"));

        assertEquals(WorkspaceOperationResult.Outcome.FAILED, provider.switchTo(new WorkspaceId("animation")));
        assertEquals(WorkspaceOperationResult.Outcome.FAILED, provider.updateDefault());
        assertEquals(WorkspaceOperationResult.Outcome.FAILED, provider.resetToDefault());
    }

    private static WorkspaceHostProvider provider(String version, VerifiedMemberResolver resolver) {
        return version.equals("5.2.03")
            ? new Cubism52WorkspaceHostProvider(resolver)
            : new Cubism53WorkspaceHostProvider(resolver);
    }

    private static SyntheticWorkspace workspace(String id, String name) {
        return new SyntheticWorkspace(new SyntheticId(id), name);
    }

    private static VerifiedMemberResolver resolver(String version) {
        List<StaticSelector> selectors = List.of(
            StaticSelector.classSelector("workspace.app.class", name(SyntheticApp.class)),
            StaticSelector.staticMethod("workspace.app.instance", name(SyntheticApp.class), "instance", "()L" + name(SyntheticApp.class) + ";", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("workspace.app.main-frame", name(SyntheticApp.class), "mainFrame", "()L" + name(SyntheticMainFrame.class) + ";", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("workspace.main-frame.dock", name(SyntheticMainFrame.class), "dock", "()L" + name(SyntheticDock.class) + ";", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("workspace.dock.current", name(SyntheticDock.class), "current", "()L" + name(SyntheticWorkspace.class) + ";", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("workspace.dock.preset", name(SyntheticDock.class), "preset", "()Ljava/util/List;", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("workspace.dock.custom", name(SyntheticDock.class), "custom", "()Ljava/util/List;", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("workspace.workspace.id", name(SyntheticWorkspace.class), "id", "()L" + name(SyntheticId.class) + ";", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("workspace.workspace.name", name(SyntheticWorkspace.class), "name", "()Ljava/lang/String;", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("workspace.id.value", name(SyntheticId.class), "idString", "()Ljava/lang/String;", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("workspace.dock.change", name(SyntheticDock.class), "change", "(L" + name(SyntheticId.class) + ";)V", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("workspace.dock.update-default", name(SyntheticDock.class), "updateDefault", "()V", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("workspace.dock.reset-default", name(SyntheticDock.class), "resetDefault", "()V", StaticSelector.ACCESS_PUBLIC)
        );
        return TestVerifiedResolvers.create(
            version,
            version.equals("5.2.03") ? WorkspaceControlAdmission.ADAPTER_SLICE_ID_5_2_03 : WorkspaceControlAdmission.ADAPTER_SLICE_ID_5_3_02,
            Set.of(WorkspaceControlAdmission.CAPABILITY_ID),
            selectors,
            SyntheticApp.class.getClassLoader()
        );
    }

    private static String name(Class<?> type) { return type.getName().replace('.', '/'); }

    public static final class SyntheticApp {
        static SyntheticApp instance;
        final SyntheticMainFrame frame;
        SyntheticApp(SyntheticMainFrame frame) { this.frame = frame; }
        public static SyntheticApp instance() { return instance; }
        public SyntheticMainFrame mainFrame() { return frame; }
    }
    public static final class SyntheticMainFrame {
        final SyntheticDock dock;
        SyntheticMainFrame(SyntheticDock dock) { this.dock = dock; }
        public SyntheticDock dock() { return dock; }
    }
    public static final class SyntheticDock {
        SyntheticWorkspace current;
        final List<SyntheticWorkspace> workspaces;
        java.util.List<SyntheticWorkspace> custom = List.of();
        int changeCount;
        int updateCount;
        int resetCount;
        boolean ignoreChange;
        boolean failCommands;
        SyntheticDock(SyntheticWorkspace current, List<SyntheticWorkspace> workspaces) {
            this.current = current; this.workspaces = workspaces;
        }
        public SyntheticWorkspace current() { return current; }
        public List<SyntheticWorkspace> preset() { return workspaces; }
        public List<SyntheticWorkspace> custom() { return custom; }
        public void change(SyntheticId id) {
            if (failCommands) throw new IllegalStateException("change failed");
            changeCount++;
            if (!ignoreChange) current = workspaces.stream().filter(value -> value.id.equals(id)).findFirst().orElse(current);
        }
        public void updateDefault() {
            if (failCommands) throw new IllegalStateException("update failed");
            updateCount++;
        }
        public void resetDefault() {
            if (failCommands) throw new IllegalStateException("reset failed");
            resetCount++;
    }
        }
    public record SyntheticWorkspace(SyntheticId id, String name) { }
    public record SyntheticId(String idString) { }
}
