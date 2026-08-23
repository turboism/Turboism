package dev.turboism.adapter.cubism;

import dev.turboism.mapping.verification.EmbeddedPanelVerificationManifest;
import dev.turboism.mapping.verification.ProjectWorkspaceVerificationManifest;
import dev.turboism.mapping.verification.RecentPreviewVerificationManifest;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.panelResolver;
import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.projectResolver;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentPreviewVerificationManifestTest {

    @Test
    void acceptsBothReviewedVersionPairsOnTheSameHostClassloader() {
        final ClassLoader loader = RecentPreviewVerificationManifestTest.class.getClassLoader();
        assertTrue(RecentPreviewVerificationManifest.authorizes(
            projectResolver("5.3.02", loader), panelResolver("5.3.02", loader)
        ));
        assertTrue(RecentPreviewVerificationManifest.authorizes(
            projectResolver("5.2.03", loader), panelResolver("5.2.03", loader)
        ));
        assertTrue(RecentPreviewVerificationManifest.CAPABILITY_IDS.contains("cubism.recent-file.read"));
        assertTrue(RecentPreviewVerificationManifest.CAPABILITY_IDS.contains("cubism.screenshot.capture"));
        assertTrue(RecentPreviewVerificationManifest.CAPABILITY_IDS.contains("ui.recent-preview.contribute"));
        // The contract only narrows the already-reviewed records; it must not widen them.
        assertTrue(ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES
            .containsAll(RecentPreviewVerificationManifest.PROJECT_REQUIRED_ALIASES));
        assertTrue(EmbeddedPanelVerificationManifest.REQUIRED_ALIASES
            .containsAll(RecentPreviewVerificationManifest.PANEL_REQUIRED_ALIASES));
    }

    @Test
    void rejectsUnreviewedPairsMixedVersionsAndForeignClassloaders() {
        final ClassLoader loader = RecentPreviewVerificationManifestTest.class.getClassLoader();
        assertFalse(RecentPreviewVerificationManifest.authorizes(
            projectResolver("5.3.01", loader), panelResolver("5.3.02", loader)
        ));
        assertFalse(RecentPreviewVerificationManifest.authorizes(
            projectResolver("5.3.02", loader), panelResolver("5.2.03", loader)
        ));
        assertFalse(RecentPreviewVerificationManifest.authorizes(
            projectResolver("5.3.02", loader), panelResolver("5.3.02", new ClassLoader() { })
        ));
        assertFalse(RecentPreviewVerificationManifest.authorizes(null, panelResolver("5.3.02", loader)));
        assertFalse(RecentPreviewVerificationManifest.authorizes(projectResolver("5.3.02", loader), null));
    }

    @Test
    void rejectsResolversThatDoNotAuthorizeTheNarrowedAliasSets() {
        final ClassLoader loader = RecentPreviewVerificationManifestTest.class.getClassLoader();
        final VerifiedMemberResolver project = projectResolver("5.3.02", loader);
        // A plan missing one required alias must fail the contract.
        final String owner = RecentPreviewHostFixture.PanelHost.class.getName().replace('.', '/');
        final List<StaticSelector> trimmed = List.of(
            StaticSelector.staticMethod("cubism.ui-panel.app-controller.instance", owner, "instance",
                "()Ljava/lang/Object;", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("cubism.ui-panel.app-controller.main-frame", owner, "mainFrame",
                "()Ljava/lang/Object;", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("cubism.ui-panel.main-frame.window", owner, "window",
                "()Ljava/lang/Object;", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("cubism.ui-panel.window.menu-bar", owner, "menuBar",
                "()Ljava/lang/Object;", StaticSelector.ACCESS_PUBLIC),
            StaticSelector.method("cubism.ui-panel.menu-bar.menus", owner, "menus",
                "()Ljava/lang/Object;", StaticSelector.ACCESS_PUBLIC)
        );
        final VerifiedMemberResolver trimmedPanel = TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-ui.embedded-panel",
            java.util.Set.of("cubism.editor-ui.embedded-panel"),
            trimmed,
            loader
        );
        assertFalse(RecentPreviewVerificationManifest.authorizes(project, trimmedPanel));
        assertThrows(IllegalArgumentException.class,
            () -> RecentPreviewVerificationManifest.requireAuthorized(project, trimmedPanel));
    }
}
