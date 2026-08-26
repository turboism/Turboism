package dev.turboism.sdk.ui.settings;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsContributionContractTest {

    @Test
    void choiceContributionKeepsOptionalOrderingAndBoundBinding() {
        final String[] stored = {"graalvm"};
        final SettingsContribution contribution = new SettingsContribution(
            "cubism-jvm",
            new SettingsTab("performance", "Performance", OptionalInt.of(200)),
            OptionalInt.empty(),
            new SettingsControl.Choice(
                "cubism-jvm",
                "Cubism JVM",
                List.of(
                    new SettingsControl.Option("graalvm", "GraalVM"),
                    new SettingsControl.Option("bundled", "Cubism bundled Java")
                ),
                SettingsBinding.of(() -> stored[0], value -> stored[0] = value)
            )
        );

        assertFalse(contribution.index().isPresent());
        final SettingsControl.Choice choice = (SettingsControl.Choice) contribution.control();
        assertEquals("graalvm", choice.binding().read());
        choice.binding().write("bundled");
        assertEquals("bundled", stored[0]);
    }

    @Test
    void priorFourArgumentDecisionConstructorRemainsAvailable() {
        final SettingsChangeDecision decision = new SettingsChangeDecision(
            false,
            "GraalVM is required",
            "Install GraalVM, then select it again.",
            java.util.Optional.empty()
        );

        assertFalse(decision.accepted());
        assertTrue(decision.action().isEmpty());
    }

    @Test
    void rejectedChangeCanDeclareOneExplicitAsynchronousAction() {
        final SettingsChangeDecision decision = SettingsChangeDecision.rejected(
            "GraalVM is required",
            "Install the managed runtime.",
            new SettingsDecisionAction("Install", () -> new SettingsActionHandle() {
                @Override public SettingsActionProgress progress() {
                    return new SettingsActionProgress(1L, 2L, "Downloading");
                }
                @Override public java.util.concurrent.CompletionStage<SettingsActionResult> completion() {
                    return java.util.concurrent.CompletableFuture.completedFuture(
                        SettingsActionResult.succeeded("Installed", "Restart Cubism.")
                    );
                }
                @Override public boolean cancel() { return true; }
            })
        );

        assertFalse(decision.accepted());
        assertEquals("Install", decision.action().orElseThrow().label());
        assertEquals(1L, decision.action().orElseThrow().action().start().progress().completed());
    }

    @Test
    void rejectedChangeCanDeclareOneSafeUserInitiatedLink() {
        final SettingsChangeDecision decision = SettingsChangeDecision.rejected(
            "GraalVM is required",
            "Install GraalVM, then select it again.",
            new SettingsLink(
                "Open download page",
                URI.create("https://www.graalvm.org/downloads/"),
                "Open the URL manually."
            )
        );

        assertFalse(decision.accepted());
        assertEquals("https", decision.link().orElseThrow().uri().getScheme());
        assertThrows(IllegalArgumentException.class, () -> new SettingsLink(
            "Run",
            URI.create("file:///tmp/runtime"),
            "Unavailable"
        ));
    }
}
