package dev.turboism.validation;

import java.nio.file.Path;

/** Offline task-binding checks; never starts a UI or Cubism process. */
public final class NativeStartupAssistanceAgentTest {
    public static void main(String[] args) {
        final String run = "queue-0123456789abcdef0123456789abcdef";
        NativeStartupAssistanceAgent.validateTaskHome(Path.of("/tmp", run, "turboism-home"), run);
        for (Path invalid : new Path[] {Path.of("/tmp/normal-profile"),
                Path.of("/tmp/another-task/turboism-home"), Path.of("/tmp", run, "other-home"), Path.of("/")}) {
            try {
                NativeStartupAssistanceAgent.validateTaskHome(invalid, run);
                throw new AssertionError("unbound home accepted");
            } catch (IllegalArgumentException expected) { }
        }
        for (String invalid : new String[] {"", "normal-profile", "queue-../escape", run + "extra"}) {
            try {
                NativeStartupAssistanceAgent.validateTaskHome(Path.of("/tmp", run, "turboism-home"), invalid);
                throw new AssertionError("unbound run accepted");
            } catch (IllegalArgumentException expected) { }
        }
        System.out.println("PASS: startup assistance bound to queue task; foreign homes/runs rejected");
    }
}
