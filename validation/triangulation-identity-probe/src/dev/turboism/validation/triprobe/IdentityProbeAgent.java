package dev.turboism.validation.triprobe;

import java.lang.instrument.Instrumentation;

/**
 * Default-off identity probe agent, premain only. Admission order: validate config → install
 * config → warm helper → pre-start writer → already-loaded gate → register transformer. Any
 * refusal or failure reports one bounded stderr line and installs nothing; premain never throws.
 */
public final class IdentityProbeAgent {
    private IdentityProbeAgent() {}

    public static void premain(String args, Instrumentation inst) {
        try {
            run(inst);
        } catch (Throwable t) {
            report("premain-error:" + t.getClass().getSimpleName());
        }
    }

    private static void run(Instrumentation inst) {
        ProbeConfig config = new ProbeConfig();
        if (!config.enabled) {
            return; // default-off: no transformer, no helper init, no writer, no output
        }
        String invalid = config.validate();
        if (invalid != null) {
            report("admission=reject reason=" + invalid);
            return;
        }
        try {
            Probe.installConfig(config);
            Class.forName("dev.turboism.validation.triprobe.Probe");
            Probe.warm();
        } catch (Throwable t) {
            report("admission=reject reason=helper-unavailable:" + t.getClass().getSimpleName());
            return;
        }
        if (!Probe.startWriter()) {
            report("admission=reject reason=writer-start-failed");
            return;
        }
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (ProbeConfig.TARGET_DOT.equals(c.getName())) {
                safeObserve("targetAlreadyLoaded=true weave=skipped");
                return;
            }
        }
        inst.addTransformer(new DefinitionObserver(config), false);
    }

    private static void report(String line) {
        // One bounded stderr line; no file IO, no throw — the host continues unmodified.
        System.err.println("[tri-identity] " + line);
    }

    static void safeObserve(String line) {
        try {
            Probe.defineObserved(line);
        } catch (Throwable ignored) {
            // Probe itself failed to link; nothing else to do — the weave is still safe.
        }
    }
}
