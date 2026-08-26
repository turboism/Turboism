package dev.turboism.tests.validation;

import dev.turboism.adapter.host.HostSession;
import dev.turboism.tests.validation.WorkspaceValidationAgent.HostAdmission;
import dev.turboism.tests.validation.WorkspaceValidationAgent.Options;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceValidationAgentTest {

    @Test
    void optionsParseWithDefaultsAndOverrides() {
        final Options defaults = Options.parse(null, Path.of("/default/home"));
        assertEquals(Path.of("/default/home"), defaults.home());
        assertEquals("com.live2d.cubism.CEAppCtrl", defaults.hostClassName());
        assertEquals(Duration.ofSeconds(180), defaults.timeout());
        assertNull(defaults.recordOverride());
        assertFalse(defaults.allowDegradedRuntime(), "degraded runtime must default to false");

        final Options configured = Options.parse(
            "home=/tmp/val;timeoutSeconds=90;hostClass=com.live2d.cubism.CEAppCtrl;"
                + "workspaceControlRecord=/bundle/cubism-5.2.03-workspace-control.json;"
                + "allowDegradedRuntime=true",
            Path.of("/ignored")
        );
        assertEquals(Path.of("/tmp/val"), configured.home());
        assertEquals(Duration.ofSeconds(90), configured.timeout());
        assertEquals(Path.of("/bundle/cubism-5.2.03-workspace-control.json"),
            configured.recordOverride());
        assertTrue(configured.allowDegradedRuntime());

        assertFalse(Options.parse("allowDegradedRuntime=false", Path.of("/h"))
            .allowDegradedRuntime());
    }

    @Test
    void optionsRejectUnknownDuplicateBlankAndInvalidDegradedValues() {
        final Path home = Path.of("/h");
        assertThrows(IllegalArgumentException.class, () -> Options.parse("bogus=1", home));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("home=/a;home=/b", home));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("timeoutSeconds=0", home));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("timeoutSeconds=601", home));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("timeoutSeconds=abc", home));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("home=", home));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("hostClass=  ", home));
        assertThrows(IllegalArgumentException.class, () ->
            Options.parse("allowDegradedRuntime=yes", home));
        assertThrows(IllegalArgumentException.class, () ->
            Options.parse("allowDegradedRuntime=TRUE", home));
        assertThrows(IllegalArgumentException.class, () ->
            Options.parse("allowDegradedRuntime=1", home));
        assertThrows(IllegalArgumentException.class, () ->
            Options.parse("allowDegradedRuntime=true;allowDegradedRuntime=false", home));
    }

    @Test
    void admissionRequiresActiveByDefaultAndAllowsFailedOnlyWhenExplicit() {
        assertAdmitted(HostSession.State.ACTIVE, false, true, false, "host=ACTIVE");
        assertAdmitted(HostSession.State.ACTIVE, true, true, false, "host=ACTIVE");

        assertAdmitted(HostSession.State.FAILED, false, false, false,
            "requires allowDegradedRuntime=true");
        assertAdmitted(HostSession.State.FAILED, true, true, true, "degraded mode: host=FAILED");
    }

    @Test
    void admissionNeverAllowsSafeModeOrClosed() {
        assertAdmitted(HostSession.State.SAFE_MODE, false, false, false, "never admissible");
        assertAdmitted(HostSession.State.SAFE_MODE, true, false, false, "never admissible");
        assertAdmitted(HostSession.State.CLOSED, false, false, false, "never admissible");
        assertAdmitted(HostSession.State.CLOSED, true, false, false, "never admissible");
    }

    @Test
    void degradedClassificationFollowsAdmittedStateNotTheOptionFlag() {
        // ACTIVE with the option enabled is a normal ACTIVE run, never degraded.
        final HostAdmission activeWithOption = WorkspaceValidationAgent.admitHostState(
            HostSession.State.ACTIVE, true);
        assertTrue(activeWithOption.allowed());
        assertFalse(activeWithOption.degraded(),
            "ACTIVE + option=true must not be classified as degraded");
        assertEquals("host=ACTIVE", activeWithOption.reason());

        // FAILED with the option enabled is degraded; the classification carries on the result.
        final HostAdmission failedWithOption = WorkspaceValidationAgent.admitHostState(
            HostSession.State.FAILED, true);
        assertTrue(failedWithOption.allowed());
        assertTrue(failedWithOption.degraded(),
            "FAILED + option=true must be classified as degraded");

        // Rejected states are never degraded.
        assertFalse(WorkspaceValidationAgent.admitHostState(HostSession.State.FAILED, false)
            .degraded());
        assertFalse(WorkspaceValidationAgent.admitHostState(HostSession.State.CLOSED, true)
            .degraded());
    }

    private static void assertAdmitted(
        final HostSession.State state,
        final boolean allowDegraded,
        final boolean expectedAllowed,
        final boolean expectedDegraded,
        final String expectedReasonPart
    ) {
        final HostAdmission admission = WorkspaceValidationAgent.admitHostState(state, allowDegraded);
        assertEquals(expectedAllowed, admission.allowed(),
            "state " + state + " allowDegraded=" + allowDegraded);
        assertEquals(expectedDegraded, admission.degraded(),
            "state " + state + " allowDegraded=" + allowDegraded);
        assertTrue(admission.reason().contains(expectedReasonPart),
            "reason " + admission.reason() + " must mention " + expectedReasonPart);
    }
}
