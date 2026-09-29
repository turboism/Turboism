package dev.turboism.validation.triprobe;

import java.lang.instrument.Instrumentation;

/**
 * Default-off identity probe agent. Without {@code turboism.validation.triIdentity.enabled=true}
 * the premain returns before registering anything: no transformer, no helper init, no writer —
 * zero side effects.
 */
public final class IdentityProbeAgent {
    private IdentityProbeAgent() {}

    public static void premain(String args, Instrumentation inst) {
        ProbeConfig config = new ProbeConfig();
        if (!config.enabled) {
            return;
        }
        start(inst, config);
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    private static void start(Instrumentation inst, ProbeConfig config) {
        try {
            Probe.installConfig(config);
            Class.forName("dev.turboism.validation.triprobe.Probe");
            Probe.warm();
        } catch (Throwable t) {
            // Helper unavailable is NOT fatal to the weave: every callsite is individually
            // catch-protected, and the missing-helper path is a required negative control.
            safeObserve("helperInitFailed=" + t.getClass().getSimpleName());
        }
        boolean alreadyLoaded = false;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (ProbeConfig.TARGET_DOT.equals(c.getName())) {
                alreadyLoaded = true;
                break;
            }
        }
        if (alreadyLoaded) {
            safeObserve("targetAlreadyLoaded=true weave=skipped");
            return;
        }
        inst.addTransformer(new DefinitionObserver(config), false);
    }

    private static void safeObserve(String line) {
        try {
            Probe.defineObserved(line);
        } catch (Throwable ignored) {
            // Probe itself failed to link; nothing else to do — the weave is still safe.
        }
    }
}
