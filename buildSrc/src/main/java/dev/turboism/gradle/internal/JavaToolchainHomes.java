package dev.turboism.gradle.internal;

import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;

/**
 * Resolves a toolchain installation path once, so every gate that shells out to a JDK names the
 * same installation instead of an incidental system {@code java}.
 *
 * <p>The result stays a {@link Provider}: configuration never probes the toolchain, and a task
 * reads the path inside its own action. Two gates that used to duplicate the lookup now share one
 * definition, so changing the required language version changes both.
 */
public final class JavaToolchainHomes {

    private JavaToolchainHomes() {
    }

    /**
     * Returns the installation directory of the compiler toolchain for {@code languageVersion}.
     *
     * @param project the project whose toolchain service performs the lookup
     * @param languageVersion the required Java feature release
     */
    public static Provider<String> compilerHome(final Project project, final int languageVersion) {
        return project.getExtensions().getByType(JavaToolchainService.class)
            .compilerFor(spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(languageVersion)))
            .map(toolchain -> toolchain.getMetadata().getInstallationPath().getAsFile().getAbsolutePath());
    }
}
