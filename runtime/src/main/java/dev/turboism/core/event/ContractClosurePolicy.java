package dev.turboism.core.event;

import dev.turboism.sdk.plugin.PluginDescriptor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Single source for the contract-closure predicates shared by the byte-level install
 * preflight ({@link PublicEventContractPreflight}) and the reflective bind-time
 * defense ({@link PublicEventContractClosure}). Keeping the predicates here prevents
 * the two validation surfaces from drifting apart.
 */
final class ContractClosurePolicy {
    private ContractClosurePolicy() {}

    /** Packages a contract artifact must never define classes in. */
    static final List<String> FORBIDDEN_CLASS_PREFIXES = List.of(
        "java.", "javax.", "jdk.", "sun.", "com.sun.", "com.live2d.",
        "dev.turboism."
    );

    /** Binary-name prefix of the shared SDK surface contract types may reference. */
    static final String SDK_PACKAGE_PREFIX = "dev.turboism.sdk.";

    /**
     * API membership, shared literally between the JVM access flags ASM reports and the
     * {@code java.lang.reflect.Modifier} bits reflection reports — the {@code PUBLIC},
     * {@code PROTECTED} and {@code SYNTHETIC} bits occupy the same positions in both.
     * Constructors are exempt from the synthetic filter to mirror
     * {@code PublicEventContractClosure}: it applies {@code isSynthetic()} to methods
     * and fields only.
     */
    static boolean isApiMember(final int modifiers, final boolean constructor) {
        final int PUBLIC = 0x0001;
        final int PROTECTED = 0x0004;
        final int SYNTHETIC = 0x1000;
        if ((modifiers & (PUBLIC | PROTECTED)) == 0) {
            return false;
        }
        return constructor || (modifiers & SYNTHETIC) == 0;
    }

    /**
     * The event types a descriptor pins for route binding; artifact members among them
     * anchor the payload closure walk.
     */
    static Set<String> payloadSeeds(final PluginDescriptor descriptor) {
        final Set<String> seeds = new LinkedHashSet<>();
        descriptor.eventExports().forEach(export -> seeds.add(export.eventType()));
        descriptor.eventImports().forEach(imports -> seeds.add(imports.eventType()));
        return seeds;
    }

    /** @return the binary package name of {@code binaryName} */
    static String packageName(final String binaryName) {
        final int dot = binaryName.lastIndexOf('.');
        return dot < 0 ? "" : binaryName.substring(0, dot);
    }
}
