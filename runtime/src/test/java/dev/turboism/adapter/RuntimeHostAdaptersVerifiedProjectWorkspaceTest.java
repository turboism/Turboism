package dev.turboism.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.mapping.verification.ProjectWorkspaceVerificationManifest;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeHostAdaptersVerifiedProjectWorkspaceTest {

    @Test
    void connectsOnlyCompleteProjectWorkspacePlanAndKeepsOtherSlicesInSafeMode() {
        VerifiedMemberResolver resolver = TestVerifiedResolvers.create(
                ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
                ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
                completeSelectors(),
                Host.class.getClassLoader());

        RuntimeHostAdapters adapters = RuntimeHostAdapters.withVerifiedProjectWorkspace(resolver);

        assertTrue(adapters.projectWorkspace().activeProject().isAvailable());
        assertEquals(
                SafeModeDiagnostic.Code.ADAPTER_UNAVAILABLE,
                adapters.renderStatus()
                        .renderStatus()
                        .diagnostic()
                        .orElseThrow()
                        .code());
        assertEquals(
                SafeModeDiagnostic.Code.ADAPTER_UNAVAILABLE,
                adapters.clipMaskRead().clipMasks().diagnostic().orElseThrow().code());
    }

    @Test
    void connectsExactCubism52ProjectWorkspaceResolver() {
        VerifiedMemberResolver resolver = TestVerifiedResolvers.create(
                "5.2.03",
                ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
                ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
                completeSelectors(),
                Host.class.getClassLoader());

        RuntimeHostAdapters adapters = RuntimeHostAdapters.withVerifiedProjectWorkspace(resolver);

        assertTrue(adapters.projectWorkspace().activeProject().isAvailable());
    }

    private static List<StaticSelector> completeSelectors() {
        final String owner = Host.class.getName().replace('.', '/');
        return ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES.stream()
                .sorted()
                .map(alias -> {
                    if (alias.endsWith(".class")) {
                        return StaticSelector.classSelector(alias, owner);
                    }
                    if (alias.equals("cubism.app-controller.view-context-history")) {
                        return StaticSelector.field(
                                alias, owner, "viewContextHistory", "Ljava/util/ArrayList;", 0x0012);
                    }
                    return alias.equals("cubism.app-controller.instance")
                            ? StaticSelector.staticMethod(
                                    alias, owner, "instance", "()L" + owner + ";", StaticSelector.ACCESS_PUBLIC)
                            : StaticSelector.method(
                                    alias, owner, "placeholder", "()Ljava/lang/Object;", StaticSelector.ACCESS_PUBLIC);
                })
                .toList();
    }

    public static final class Host {
        private final java.util.ArrayList<Object> viewContextHistory = new java.util.ArrayList<>();

        public static Host instance() {
            return null;
        }

        public Object placeholder() {
            return viewContextHistory;
        }
    }
}
