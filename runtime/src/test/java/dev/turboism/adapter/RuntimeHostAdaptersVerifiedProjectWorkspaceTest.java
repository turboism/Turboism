package dev.turboism.adapter;

import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.cubism.VerifiedProjectWorkspaceHostOperations;
import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeHostAdaptersVerifiedProjectWorkspaceTest {

    @Test
    void connectsOnlyCompleteProjectWorkspacePlanAndKeepsOtherSlicesInSafeMode() {
        VerifiedMemberResolver resolver = TestVerifiedResolvers.create(
            ProjectWorkspaceAdapter.ADAPTER_SLICE_ID,
            Set.of(
                ProjectWorkspaceAdapter.PROJECT_CAPABILITY_ID,
                ProjectWorkspaceAdapter.WORKSPACE_CAPABILITY_ID
            ),
            completeSelectors(),
            Host.class.getClassLoader()
        );

        RuntimeHostAdapters adapters = RuntimeHostAdapters.withVerifiedProjectWorkspace(resolver);

        assertTrue(adapters.projectWorkspace().activeProject().isAvailable());
        assertEquals(
            SafeModeDiagnostic.Code.ADAPTER_UNAVAILABLE,
            adapters.renderStatus().renderStatus().diagnostic().orElseThrow().code()
        );
        assertEquals(
            SafeModeDiagnostic.Code.ADAPTER_UNAVAILABLE,
            adapters.clipMaskRead().clipMasks().diagnostic().orElseThrow().code()
        );
    }

    @Test
    void connectsExactCubism52ProjectWorkspaceResolver() {
        VerifiedMemberResolver resolver = TestVerifiedResolvers.create(
            "5.2.03",
            ProjectWorkspaceAdapter.ADAPTER_SLICE_ID,
            Set.of(
                ProjectWorkspaceAdapter.PROJECT_CAPABILITY_ID,
                ProjectWorkspaceAdapter.WORKSPACE_CAPABILITY_ID
            ),
            completeSelectors(),
            Host.class.getClassLoader()
        );

        RuntimeHostAdapters adapters = RuntimeHostAdapters.withVerifiedProjectWorkspace(resolver);

        assertTrue(adapters.projectWorkspace().activeProject().isAvailable());
    }

    private static List<StaticSelector> completeSelectors() {
        final String owner = Host.class.getName().replace('.', '/');
        return VerifiedProjectWorkspaceHostOperations.REQUIRED_ALIASES.stream()
            .sorted()
            .map(alias -> {
                if (alias.endsWith(".class")) {
                    return StaticSelector.classSelector(alias, owner);
                }
                return alias.equals("cubism.app-controller.instance")
                    ? StaticSelector.staticMethod(alias, owner, "instance", "()L" + owner + ";", StaticSelector.ACCESS_PUBLIC)
                    : StaticSelector.method(alias, owner, "placeholder", "()Ljava/lang/Object;", StaticSelector.ACCESS_PUBLIC);
            })
            .toList();
    }

    public static final class Host {
        public static Host instance() {
            return null;
        }

        public Object placeholder() {
            return null;
        }
    }
}
