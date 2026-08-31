package dev.turboism.plugin.turboismwithfx;

import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Turboism-owned non-secret launch and provider-profile settings. */
final class FxPluginSettings implements AutoCloseable {

    private static final String FILE = "settings.properties";
    private static final String EXECUTABLE = "fxExecutable";
    private static final String SESSION_ID = "fxSessionId";
    private static final String COMPATIBILITY = "allowFxNativeTools";
    private static final String INITIAL_PROMPT = "initialPrompt";
    private static final String ACTIVE_PROVIDER_PROFILE = "activeProviderProfile";
    private static final String CUSTOM_PROVIDER_PROFILES = "customProviderProfiles";
    private static final int MAX_INITIAL_PROMPT_CHARS = 64 * 1024;

    private final PluginConfigRegistry config;
    private final PluginLogger logger;
    private final FxSecretStore secrets;
    private final Registration readScope;
    private final Registration writeScope;
    private String executableSnapshot = "";
    private String sessionIdSnapshot;
    private boolean compatibilitySnapshot;
    private String initialPromptSnapshot = "";
    private FxProviderConfiguration providerConfigurationSnapshot =
        new FxProviderConfiguration();

    FxPluginSettings(final PluginConfigRegistry config, final PluginLogger logger) {
        this(config, logger, FxSecretStore.unavailable());
    }

    FxPluginSettings(
        final PluginConfigRegistry config,
        final PluginLogger logger,
        final FxSecretStore secrets
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
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
            providerConfigurationSnapshot = readProviderConfiguration();
        } catch (RuntimeException | Error failure) {
            if (write != null) write.close();
            if (read != null) read.close();
            throw failure;
        }
    }

    synchronized String executable() { return executableSnapshot; }
    synchronized String sessionId() { return sessionIdSnapshot; }
    synchronized boolean compatibilityMode() { return compatibilitySnapshot; }
    synchronized String initialPrompt() { return initialPromptSnapshot; }
    synchronized FxProviderConfiguration providerConfiguration() {
        return providerConfigurationSnapshot;
    }
    synchronized FxCustomEndpointSettings customEndpoint() {
        return providerConfigurationSnapshot.customEndpoint();
    }
    boolean secureApiKeyPersistenceAvailable() { return secrets.persistent(); }

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

    synchronized void writeUserSettings(
        final String executable,
        final boolean compatibilityMode,
        final String initialPrompt
    ) throws PluginConfigException {
        writeUserSettings(
            executable,
            compatibilityMode,
            initialPrompt,
            providerConfigurationSnapshot
        );
    }

    synchronized void writeUserSettings(
        final String executable,
        final boolean compatibilityMode,
        final String initialPrompt,
        final FxCustomEndpointSettings customEndpoint
    ) throws PluginConfigException {
        final FxProviderConfiguration current = providerConfigurationSnapshot;
        final FxProviderProfile active = current.activeProfile();
        final FxProviderConfiguration providerConfiguration;
        if (customEndpoint.enabled()) {
            final FxProviderProfile replacement = new FxProviderProfile(
                active.id(),
                active.name(),
                FxProviderProfile.Kind.OPENAI_COMPATIBLE,
                "",
                customEndpoint.endpoint(),
                customEndpoint.apiKeyEnvironment(),
                customEndpoint.model(),
                active.manualModels()
            );
            final java.util.ArrayList<FxProviderProfile> profiles =
                new java.util.ArrayList<>(current.customProfiles());
            profiles.removeIf(profile -> profile.id().equals(replacement.id()));
            profiles.add(replacement);
            providerConfiguration = new FxProviderConfiguration(
                replacement.id(),
                profiles,
                current.sessionApiKeys()
            ).withSessionApiKey(replacement.id(), customEndpoint.sessionApiKey());
        } else {
            providerConfiguration = current;
        }
        writeUserSettings(executable, compatibilityMode, initialPrompt, providerConfiguration);
    }

    synchronized void writeUserSettings(
        final String executable,
        final boolean compatibilityMode,
        final String initialPrompt,
        final FxProviderConfiguration providerConfiguration
    ) throws PluginConfigException {
        final String nextExecutable = Objects.requireNonNull(executable, "executable").strip();
        final String nextPrompt = boundedInitialPrompt(
            Objects.requireNonNull(initialPrompt, "initialPrompt")
        );
        final FxProviderConfiguration nextProviders = Objects.requireNonNull(
            providerConfiguration,
            "providerConfiguration"
        );
        final String previousExecutable = executableSnapshot;
        final boolean previousCompatibility = compatibilitySnapshot;
        final String previousPrompt = initialPromptSnapshot;
        final FxProviderConfiguration previousProviders = providerConfigurationSnapshot;
        final List<SettingValue> next = List.of(
            new SettingValue(EXECUTABLE, nextExecutable),
            new SettingValue(COMPATIBILITY, Boolean.toString(compatibilityMode)),
            new SettingValue(INITIAL_PROMPT, nextPrompt),
            new SettingValue(ACTIVE_PROVIDER_PROFILE, nextProviders.activeProfileId()),
            new SettingValue(
                CUSTOM_PROVIDER_PROFILES,
                FxProviderProfileCodec.encode(nextProviders.customProfiles())
            )
        );
        final List<SettingValue> previous = List.of(
            new SettingValue(EXECUTABLE, previousExecutable),
            new SettingValue(COMPATIBILITY, Boolean.toString(previousCompatibility)),
            new SettingValue(INITIAL_PROMPT, previousPrompt),
            new SettingValue(ACTIVE_PROVIDER_PROFILE, previousProviders.activeProfileId()),
            new SettingValue(
                CUSTOM_PROVIDER_PROFILES,
                FxProviderProfileCodec.encode(previousProviders.customProfiles())
            )
        );
        int attempted = 0;
        try {
            for (SettingValue value : next) {
                attempted++;
                write(value.key(), value.value());
            }
            executableSnapshot = nextExecutable;
            compatibilitySnapshot = compatibilityMode;
            initialPromptSnapshot = nextPrompt;
            providerConfigurationSnapshot = nextProviders;
            synchronizeSecrets(previousProviders, nextProviders);
        } catch (PluginConfigException failure) {
            try {
                rollbackUserSettings(attempted, previous);
            } catch (PluginConfigException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private FxProviderConfiguration readProviderConfiguration() {
        try {
            final List<FxProviderProfile> profiles =
                FxProviderProfileCodec.decode(read(CUSTOM_PROVIDER_PROFILES, ""));
            return new FxProviderConfiguration(
                read(ACTIVE_PROVIDER_PROFILE, FxProviderProfile.VERCEL_ID),
                profiles,
                readSecrets(profiles)
            );
        } catch (IllegalArgumentException failure) {
            logger.warn("Turboism with fx ignored invalid provider profile settings");
            return new FxProviderConfiguration();
        }
    }

    /** Reloads saved provider credentials so a restart does not force re-entry. */
    private Map<String, String> readSecrets(final List<FxProviderProfile> profiles) {
        if (!secrets.persistent()) return Map.of();
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (FxProviderProfile profile : profiles) {
            try {
                secrets.read(profile.id()).ifPresent(value -> values.put(profile.id(), value));
            } catch (IOException | RuntimeException failure) {
                logger.warn("Turboism with fx could not read a saved provider credential");
            }
        }
        return values;
    }

    /** Persists current credentials and deletes those belonging to removed profiles. */
    private void synchronizeSecrets(
        final FxProviderConfiguration previous,
        final FxProviderConfiguration next
    ) {
        if (!secrets.persistent()) return;
        final java.util.LinkedHashSet<String> retained = new java.util.LinkedHashSet<>();
        for (FxProviderProfile profile : next.customProfiles()) retained.add(profile.id());
        for (FxProviderProfile profile : previous.customProfiles()) {
            if (!retained.contains(profile.id())) writeSecret(profile.id(), "");
        }
        for (FxProviderProfile profile : next.customProfiles()) {
            writeSecret(profile.id(), next.sessionApiKeys().getOrDefault(profile.id(), ""));
        }
    }

    private void writeSecret(final String profileId, final String value) {
        try {
            secrets.write(profileId, value);
        } catch (IOException | RuntimeException failure) {
            logger.warn("Turboism with fx could not persist a provider credential");
        }
    }

    private void rollbackUserSettings(
        final int attempted,
        final List<SettingValue> previous
    ) throws PluginConfigException {
        PluginConfigException failure = null;
        for (int index = Math.min(attempted, previous.size()) - 1; index >= 0; index--) {
            final SettingValue value = previous.get(index);
            try {
                write(value.key(), value.value());
            } catch (PluginConfigException rollbackFailure) {
                if (failure == null) failure = rollbackFailure;
                else failure.addSuppressed(rollbackFailure);
            }
        }
        if (failure != null) throw failure;
    }

    private record SettingValue(String key, String value) {
        private SettingValue {
            key = Objects.requireNonNull(key, "key");
            value = Objects.requireNonNull(value, "value");
        }
    }

    @Override public void close() {
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
