package dev.turboism.gradle

import dev.turboism.gradle.internal.CommandLineValueSource
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import java.io.File

/**
 * One external command as a lazy, configuration-cache-compatible value.
 *
 * `providers.exec { ... }.standardOutput.asText.get()` runs the command during configuration and
 * then cannot be stored in the configuration cache. This helper declares the same command as a
 * [CommandLineValueSource] instead: the command runs at most once per build, when a task first
 * reads the value, and only its plain result enters the cache entry.
 *
 * The command never runs while Gradle configures the build, so a caller must not assume the value
 * is available at configuration time. Derive task inputs with `map` and read the value inside a
 * task action.
 */
object CommandLine {

    /**
     * Declares [commandLine] as a value source rooted at [project].
     *
     * @param project the project that owns the value source registration
     * @param commandLine the executable and its arguments, in argv order
     * @param workingDirectory the directory the command runs in; the build directory when null
     * @param environment extra variables layered on top of the build process environment
     * @param ignoreExitValue when false, a non-zero exit status becomes a captured failure
     */
    fun value(
        project: Project,
        commandLine: List<String>,
        workingDirectory: File? = null,
        environment: Map<String, String> = emptyMap(),
        ignoreExitValue: Boolean = true,
    ): Provider<CommandLineValueSource.Result> =
        project.providers.of(CommandLineValueSource::class.java) {
            parameters.commandLine.set(commandLine)
            workingDirectory?.let { parameters.workingDirectory.set(it) }
            parameters.environment.set(environment)
            parameters.ignoreExitValue.set(ignoreExitValue)
        }
}
