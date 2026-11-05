package dev.turboism.bootstrap;

import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * Reference-byte and attestation access for one install attempt: the routine opens the artifact once
 * and every target takes its official bytes from there, so a hook can never weave a class whose loader
 * or code source does not match the artifact that admitted it.
 */
final class WeaveSource {

    private final String feature;
    private final InstallContext context;
    private final JarFile jar;

    WeaveSource(final String feature, final InstallContext context, final JarFile jar) {
        this.feature = feature;
        this.context = context;
        this.jar = jar;
    }

    /** @return the located host artifact this install is verified against */
    Path artifact() {
        return context.artifact();
    }

    /** @return the host class loader the woven classes are defined by */
    ClassLoader loader() {
        return context.hostLoader();
    }

    /**
     * Loads one host class without initializing it and attests its loader and code source.
     *
     * @param internalName the class's internal name, such as {@code com/live2d/...}
     * @return the attested class
     * @throws Exception when the class cannot be loaded or does not belong to the artifact/loader
     */
    Class<?> load(final String internalName) throws Exception {
        final Class<?> type = Class.forName(internalName.replace('/', '.'), false, context.hostLoader());
        VerifiedHookInstallerSupport.attestClassSource(
                type, context.hostLoader(), context.artifact(), feature + " host loader or artifact mismatch");
        return type;
    }

    /**
     * @param type an attested host class
     * @return its official bytes, read from the artifact that admitted the hook
     * @throws Exception when the artifact does not contain the class entry
     */
    byte[] referenceBytes(final Class<?> type) throws Exception {
        return VerifiedHookInstallerSupport.readReferenceBytes(jar, type);
    }
}
