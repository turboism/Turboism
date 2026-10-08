package dev.turboism.plugin.acp;

import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Plugin-scoped ACP settings with rollback-safe writes.
 *
 * <p>Only non-secret launch and session state is stored: the selected agent profile, an optional
 * custom command line, the durable session id, and the standing instruction prompt. Agent
 * credentials always remain inside the external agent's own configuration.</p>
 */
final class AcpPluginSettings implements AutoCloseable {

    private static final String FILE = "settings.properties";
    private static final String AGENT_ID = "agentId";
    private static final String CUSTOM_COMMAND = "customCommand";
    private static final String SESSION_ID = "acpSessionId";
    private static final String INITIAL_PROMPT = "initialPrompt";
    private static final int MAX_STORED_CHARS = 8192;
    private static final int MAX_INITIAL_PROMPT_CHARS = 64 * 1024;

    private final PluginConfigRegistry config;
    private final PluginLogger logger;
    private final Registration readScope;
    private final Registration writeScope;
    private String agentIdSnapshot;
    private String customCommandSnapshot;
    private String sessionIdSnapshot;
    private String initialPromptSnapshot = "";

    /** Loads persisted settings, dropping values that no longer decode to sane shapes. */
    AcpPluginSettings(final PluginConfigRegistry config, final PluginLogger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        Registration read = null;
        Registration write = null;
        try {
            read = config.readScope(FILE);
            write = config.writeScope(FILE);
            readScope = read;
            writeScope = write;
            agentIdSnapshot = normalizeStored(read(AGENT_ID, null));
            if (agentIdSnapshot != null
                    && !AgentCatalog.CUSTOM_AGENT_ID.equals(agentIdSnapshot)
                    && AgentCatalog.profile(agentIdSnapshot).isEmpty()) {
                agentIdSnapshot = null;
            }
            customCommandSnapshot = normalizeStored(read(CUSTOM_COMMAND, null));
            if (customCommandSnapshot != null) {
                try {
                    splitCommand(customCommandSnapshot);
                } catch (IllegalArgumentException failure) {
                    customCommandSnapshot = null;
                }
            }
            sessionIdSnapshot = normalizeStored(read(SESSION_ID, null));
            initialPromptSnapshot = boundedPrompt(read(INITIAL_PROMPT, ""));
        } catch (RuntimeException | Error failure) {
            if (write != null) write.close();
            if (read != null) read.close();
            throw failure;
        }
    }

    /** Returns the selected agent profile id, or the catalog default. */
    synchronized String agentId() {
        return agentIdSnapshot == null ? defaultAgentId() : agentIdSnapshot;
    }

    static String defaultAgentId() {
        return AgentCatalog.profiles().get(0).id();
    }

    /** Returns the user-edited custom command line used when {@code agentId} is {@code custom}. */
    synchronized String customCommand() {
        return customCommandSnapshot;
    }

    /** Returns the durable ACP session id to reopen on the next connect, when one was kept. */
    synchronized String sessionId() {
        return sessionIdSnapshot;
    }

    /** Returns the session-level standing instruction prompt, when configured. */
    synchronized Optional<String> initialPrompt() {
        return Optional.ofNullable(initialPromptSnapshot).filter(text -> !text.isBlank());
    }

    /**
     * Persists the user-edited agent selection, custom command, and standing prompt in one
     * rollback-safe write; returns {@code false} when persistence fails.
     */
    synchronized boolean writeUserSettings(
            final String agentId, final String customCommand, final String initialPrompt) {
        final String nextAgentId = normalizeStored(agentId);
        if (nextAgentId != null
                && !AgentCatalog.CUSTOM_AGENT_ID.equals(nextAgentId)
                && AgentCatalog.profile(nextAgentId).isEmpty()) {
            throw new IllegalArgumentException("unknown agent id");
        }
        final String nextCommand = normalizeStored(customCommand);
        if (nextCommand != null) splitCommand(nextCommand);
        final String nextPrompt = boundedPrompt(Objects.requireNonNull(initialPrompt, "initialPrompt"));
        if (nextPrompt.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("initialPrompt contains a NUL character");
        }
        final String previousAgent = agentIdSnapshot;
        final String previousCommand = customCommandSnapshot;
        final String previousPrompt = initialPromptSnapshot;
        final String nextSessionId = Objects.equals(nextAgentId, previousAgent) ? sessionIdSnapshot : null;
        try {
            write(AGENT_ID, nextAgentId);
            write(CUSTOM_COMMAND, nextCommand);
            write(INITIAL_PROMPT, nextPrompt);
            write(SESSION_ID, nextSessionId);
        } catch (PluginConfigException failure) {
            try {
                write(AGENT_ID, previousAgent);
                write(CUSTOM_COMMAND, previousCommand);
                write(INITIAL_PROMPT, previousPrompt);
                write(SESSION_ID, sessionIdSnapshot);
            } catch (PluginConfigException rollbackFailure) {
                logger.warn("Turboism ACP settings rollback could not be persisted");
            }
            return false;
        }
        agentIdSnapshot = nextAgentId;
        customCommandSnapshot = nextCommand;
        initialPromptSnapshot = nextPrompt;
        sessionIdSnapshot = nextSessionId;
        return true;
    }

    /** Persists the durable session id for reload. */
    synchronized void writeSessionId(final String id) {
        final String normalized = normalizeStored(id);
        try {
            write(SESSION_ID, normalized);
            sessionIdSnapshot = normalized;
        } catch (PluginConfigException failure) {
            logger.warn("Turboism ACP session id could not be persisted");
        }
    }

    /** Drops the durable session id binding. */
    synchronized void clearSessionId() {
        try {
            write(SESSION_ID, null);
            sessionIdSnapshot = null;
        } catch (PluginConfigException failure) {
            logger.warn("Turboism ACP obsolete session id could not be cleared");
        }
    }

    @Override
    public void close() {
        try {
            writeScope.close();
        } finally {
            readScope.close();
        }
    }

    private void write(final String key, final String value) throws PluginConfigException {
        config.writeString(FILE, key, value == null ? "" : value);
    }

    private String read(final String key, final String fallback) {
        return config.readString(FILE, key).orElse(fallback);
    }

    private static String normalizeStored(final String value) {
        if (value == null) return null;
        final String text = value.strip();
        return text.isEmpty() || text.length() > MAX_STORED_CHARS ? null : text;
    }

    private static String boundedPrompt(final String value) {
        if (value == null) return "";
        return value.length() > MAX_INITIAL_PROMPT_CHARS ? value.substring(0, MAX_INITIAL_PROMPT_CHARS) : value;
    }

    /**
     * Splits a user-edited command line into argv, honoring single and double quotes.
     * Backslashes are literal; unmatched quotes and empty results are rejected.
     */
    static List<String> splitCommand(final String commandLine) {
        final String text = Objects.requireNonNull(commandLine, "commandLine").strip();
        if (text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("command line contains a NUL character");
        }
        final ArrayList<String> argv = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean tokenStarted = false;
        for (int index = 0; index < text.length(); index++) {
            final char value = text.charAt(index);
            if (singleQuoted) {
                if (value == '\'') singleQuoted = false;
                else current.append(value);
            } else if (doubleQuoted) {
                if (value == '"') doubleQuoted = false;
                else current.append(value);
            } else if (value == '\'') {
                singleQuoted = true;
                tokenStarted = true;
            } else if (value == '"') {
                doubleQuoted = true;
                tokenStarted = true;
            } else if (Character.isWhitespace(value)) {
                if (tokenStarted || current.length() > 0) {
                    argv.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
            } else {
                current.append(value);
                tokenStarted = true;
            }
        }
        if (singleQuoted || doubleQuoted) {
            throw new IllegalArgumentException("command line has an unmatched quote");
        }
        if (tokenStarted || current.length() > 0) {
            argv.add(current.toString());
        }
        if (argv.isEmpty() || argv.size() > AgentLaunchSpec.MAX_ARGUMENTS) {
            throw new IllegalArgumentException("command line is invalid");
        }
        return List.copyOf(argv);
    }
}
