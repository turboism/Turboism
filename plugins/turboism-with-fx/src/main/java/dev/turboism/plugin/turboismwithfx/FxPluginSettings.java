package dev.turboism.plugin.turboismwithfx;

import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;

/**
 * Minimal Turboism-owned launch state.
 *
 * <p>Provider and model values are intentionally absent: fx owns those inside its ACP session.
 * Turboism stores only an optional advanced executable override, the active opaque fx session id
 * when the runtime advertises load support, the user's explicit compatibility-mode acknowledgement,
 * and the user-authored initial prompt appended after the fixed Turboism security boundary. A blank
 * override selects the reviewed platform-specific runtime installed with Turboism.</p>
 */
final class FxPluginSettings implements AutoCloseable {

    private static final String FILE = "settings.properties";
    private static final String EXECUTABLE = "fxExecutable";
    private static final String SESSION_ID = "fxSessionId";
    private static final String COMPATIBILITY = "allowFxNativeTools";
    private static final String INITIAL_PROMPT = "initialPrompt";
    private static final int MAX_INITIAL_PROMPT_CHARS = 64 * 1024;

    private final PluginConfigRegistry config;
    private final PluginLogger logger;
    private final Registration readScope;
    private final Registration writeScope;
    private String executableSnapshot = "";
    private String sessionIdSnapshot;
    private boolean compatibilitySnapshot;
    private String initialPromptSnapshot = "";

    FxPluginSettings(
        final PluginConfigRegistry config,
        final PluginLogger logger
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        Registration read = null;
        Registration write = null;
        try {
            read = config.readScope(FILE);
            write = config.writeScope(FILE);
            readScope = read;
            writeScope = write;
            executableSnapshot = read(EXECUTABLE, "");
            sessionIdSnapshot = blankToNull(read(SESSION_ID, ""));
            compatibilitySnapshot = Boolean.parseBoolean(read(COMPATIBILITY, "false"));
            initialPromptSnapshot = boundedInitialPrompt(read(INITIAL_PROMPT, ""));
        } catch (RuntimeException | Error failure) {
            if (write != null) write.close();
            if (read != null) read.close();
            throw failure;
        }
    }

    synchronized String executable() {
        return executableSnapshot;
    }

    synchronized String sessionId() {
        return sessionIdSnapshot;
    }

    synchronized boolean compatibilityMode() {
        return compatibilitySnapshot;
    }

    synchronized String initialPrompt() {
        return initialPromptSnapshot;
    }

    synchronized void writeExecutable(final String value) throws PluginConfigException {
        final String executable = Objects.requireNonNull(value, "value").strip();
        write(EXECUTABLE, executable);
        executableSnapshot = executable;
    }

    synchronized void writeSessionId(final String value) {
        final String sessionId = Objects.requireNonNull(value, "value");
        try {
            write(SESSION_ID, sessionId);
            sessionIdSnapshot = sessionId;
        } catch (PluginConfigException failure) {
            logger.warn("Turboism with fx session id could not be persisted");
        }
    }

    synchronized void clearSessionId() {
        try {
            write(SESSION_ID, "");
            sessionIdSnapshot = null;
        } catch (PluginConfigException failure) {
            logger.warn("Turboism with fx obsolete session id could not be cleared");
        }
    }

    synchronized void writeCompatibilityMode(final boolean enabled) throws PluginConfigException {
        write(COMPATIBILITY, Boolean.toString(enabled));
        compatibilitySnapshot = enabled;
    }

    synchronized void writeInitialPrompt(final String value) throws PluginConfigException {
        final String prompt = boundedInitialPrompt(Objects.requireNonNull(value, "value"));
        write(INITIAL_PROMPT, prompt);
        initialPromptSnapshot = prompt;
    }

    /**
     * Persists the user-visible settings as one logical update.
     *
     * <p>The legacy registry writes one property at a time, so a later I/O failure is rolled back
     * to the exact prior values before this method reports failure. Rollback failure remains visible
     * as a suppressed cause instead of silently claiming that nothing changed.</p>
     */
    synchronized void writeUserSettings(
        final String executable,
        final boolean compatibilityMode,
        final String initialPrompt
    ) throws PluginConfigException {
        final String nextExecutable = Objects.requireNonNull(executable, "executable").strip();
        final String nextPrompt = boundedInitialPrompt(
            Objects.requireNonNull(initialPrompt, "initialPrompt")
        );
        final String previousExecutable = executableSnapshot;
        final boolean previousCompatibility = compatibilitySnapshot;
        final String previousPrompt = initialPromptSnapshot;
        int attempted = 0;
        try {
            attempted = 1;
            write(EXECUTABLE, nextExecutable);
            attempted = 2;
            write(COMPATIBILITY, Boolean.toString(compatibilityMode));
            attempted = 3;
            write(INITIAL_PROMPT, nextPrompt);
            executableSnapshot = nextExecutable;
            compatibilitySnapshot = compatibilityMode;
            initialPromptSnapshot = nextPrompt;
        } catch (PluginConfigException failure) {
            try {
                rollbackUserSettings(
                    attempted,
                    previousExecutable,
                    previousCompatibility,
                    previousPrompt
                );
            } catch (PluginConfigException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private void rollbackUserSettings(
        final int attempted,
        final String executable,
        final boolean compatibilityMode,
        final String initialPrompt
    ) throws PluginConfigException {
        PluginConfigException failure = null;
        if (attempted >= 2) {
            try {
                write(COMPATIBILITY, Boolean.toString(compatibilityMode));
            } catch (PluginConfigException rollbackFailure) {
                failure = rollbackFailure;
            }
        }
        if (attempted >= 1) {
            try {
                write(EXECUTABLE, executable);
            } catch (PluginConfigException rollbackFailure) {
                if (failure == null) failure = rollbackFailure;
                else failure.addSuppressed(rollbackFailure);
            }
        }
        if (attempted >= 3) {
            try {
                write(INITIAL_PROMPT, initialPrompt);
            } catch (PluginConfigException rollbackFailure) {
                if (failure == null) failure = rollbackFailure;
                else failure.addSuppressed(rollbackFailure);
            }
        }
        if (failure != null) throw failure;
    }

    @Override
    public void close() {
        writeScope.close();
        readScope.close();
    }

    private String read(final String key, final String fallback) {
        return config.readString(FILE, key).filter(value -> !value.isBlank()).orElse(fallback);
    }

    private void write(final String key, final String value) throws PluginConfigException {
        config.writeString(FILE, key, Objects.requireNonNull(value, "value"));
    }

    static String boundedInitialPrompt(final String value) {
        if (value.length() > MAX_INITIAL_PROMPT_CHARS || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("initial prompt is invalid");
        }
        return value;
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
