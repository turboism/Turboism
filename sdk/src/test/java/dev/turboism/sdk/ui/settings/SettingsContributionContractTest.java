package dev.turboism.sdk.ui.settings;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
