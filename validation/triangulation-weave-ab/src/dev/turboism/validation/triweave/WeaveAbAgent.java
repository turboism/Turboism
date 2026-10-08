package dev.turboism.validation.triweave;

import java.lang.instrument.Instrumentation;

/**
 * Default-off T029-TRIAB dump+weave agent, premain only. Admission order: validate config →
 * install sink → warm agent-side callsite classes → pre-start writer → already-loaded gate →
 * register transformer. Any refusal reports one bounded stderr line and installs nothing;
 * premain never throws.
 *
 * Helper warming happens at premain only for the official profile in woven mode (the helper
 * is an agent-jar class there); the authoritative resolvability gate runs per definition
 * event against the target's own loader — in every mode a failed transform gate marks the
 * leg INVALID rather than silently degrading to baseline.
 */
public final class WeaveAbAgent {
    private WeaveAbAgent() {}

    public static void premain(String args, Instrumentation inst) {
        try {
            run(inst);
        } catch (Throwable t) {
            report("premain-error:" + t.getClass().getSimpleName());
        }
    }

    private static void run(Instrumentation inst) {
        WeaveAbConfig config = new WeaveAbConfig();
        if (!config.enabled) {
            return; // default-off: no transformer, no writer, no output
        }
        String invalid = config.validate();
        if (invalid != null) {
            report("admission=reject reason=" + invalid);
            return;
        }
        try {
            Sink.installConfig(config);
            Class.forName("dev.turboism.validation.triweave.Sink");
            Class.forName("dev.turboism.validation.triweave.Counters");
            Sink.warm();
            if (config.woven() && config.officialProfile()
                    && config.candidateHelperInternal != null) {
                // Woven mode requires the agent-jar helper resolvable now; a missing-helper
                // jar must refuse installation instead of silently baselining the leg.
                // TRIAB warms triweave.Helper; DWEAVE warms dweave.MatchList — both are
                // agent-jar classes under the official profiles.
                Class.forName(config.candidateHelperInternal.replace('/', '.'));
            }
        } catch (Throwable t) {
            report("admission=reject reason=helper-unavailable:" + t.getClass().getSimpleName());
            return;
        }
        if (!Sink.startWriter()) {
            report("admission=reject reason=writer-start-failed");
            return;
        }
        for (Class<?> c : inst.getAllLoadedClasses()) {
            for (WeaveAbConfig.Target t : config.targets) {
                if (t.internal.replace('/', '.').equals(c.getName())) {
                    safeObserve("targetAlreadyLoaded=true transform=skipped");
                    return;
                }
            }
        }
        inst.addTransformer(new AbTransformer(config), false);
    }

    private static void report(String line) {
        System.err.println("[tri-weave] " + line);
    }

    static void safeObserve(String line) {
        try {
            Sink.def(line);
        } catch (Throwable ignored) {
            // Sink itself failed to link; nothing else to do.
        }
    }
}
