package dev.turboism.adapter.cubism.integration;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorIntegrationSettingsDialogSelectorContract;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Host-domain persistence for the native 「编辑」 edit-checkbox state (spec 051, Phase 2).
 *
 * <p>Persistence decision (recorded in {@code host-evidence/native-edit-toggle/
 * native-toggle-internals.md} §12.2): the toggle rides the host's own
 * {@code UUConfig} key space as {@value #CONFIG_KEY} — the same store, API, and
 * {@code CExternalAppSettingDialog.*} key family the native remote-connect checkbox uses —
 * rather than a Turboism-owned configuration file. The state is therefore a sibling of
 * {@code CExternalAppSettingDialog.RemoteConnect} and travels with the host profile exactly
 * like the dialog's other row settings.</p>
 *
 * <p>The member triple — the {@code UUConfig.a} singleton field and its
 * {@code a(String,Object)} defaulting read / {@code b(String,Object)} write — is
 * byte-identical to the {@code cubism.editor-command.config.*} selectors already verified in
 * the ui-top-menu records; it is rebound under the feature namespace so one editor-model
 * resolver covers the whole feature. {@link #load()} answers {@code false} for an absent
 * key, a non-boolean value, or any resolution failure — fail-closed, matching the low-version
 * no-edit-approval posture. {@link #store} is called from the toggle listener on the EDT,
 * the same call shape and timing as the native {@code K} callback's RemoteConnect write;
 * write failures are swallowed so a persistence hiccup can never break the checkbox.</p>
 */
public final class EditToggleConfigStore {

    /** UUConfig key persisting the edit toggle; sibling of {@code …RemoteConnect}. */
    public static final String CONFIG_KEY = "CExternalAppSettingDialog.EditEnabled";

    private static final String ADAPTER_SLICE_ID =
        EditorIntegrationSettingsDialogSelectorContract.ADAPTER_SLICE_ID;
    private static final String CAPABILITY_ID =
        EditorIntegrationSettingsDialogSelectorContract.EDIT_TOGGLE_CAPABILITY_ID;
    private static final Set<String> CONFIG_ALIASES = Set.of(
        EditorIntegrationSettingsDialogSelectorContract.CONFIG_INSTANCE_ALIAS,
        EditorIntegrationSettingsDialogSelectorContract.CONFIG_READ_ALIAS,
        EditorIntegrationSettingsDialogSelectorContract.CONFIG_WRITE_ALIAS
    );

    private final VerifiedMemberResolver resolver;
    private final Object config;

    private EditToggleConfigStore(
        final VerifiedMemberResolver resolver,
        final Object config
    ) {
        this.resolver = resolver;
        this.config = config;
    }

    /**
     * {@return the store when the plan admits the config triple and the singleton resolves;
     *          empty otherwise}
     *
     * <p>Admission mirrors the injector's: capability plus the three config aliases on the
     * exact editor-model slice. A missing or unresolved member yields {@link Optional#empty()}
     * — the toggle then runs unpersisted rather than failing injection.</p>
     */
    public static Optional<EditToggleConfigStore> fromVerifiedResolver(
        final VerifiedMemberResolver resolver
    ) {
        final VerifiedMemberResolver verified = Objects.requireNonNull(resolver, "resolver");
        if (!verified.authorizesFeature(ADAPTER_SLICE_ID, CAPABILITY_ID, CONFIG_ALIASES)) {
            return Optional.empty();
        }
        try {
            final Object config = verified.readStaticField(
                EditorIntegrationSettingsDialogSelectorContract.CONFIG_INSTANCE_ALIAS);
            return config == null
                ? Optional.empty()
                : Optional.of(new EditToggleConfigStore(verified, config));
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    /**
     * {@return the persisted toggle state; {@code false} when the key is absent, holds a
     *          non-boolean value, or the read fails}
     */
    public boolean load() {
        try {
            final Object value = resolver.invoke(
                EditorIntegrationSettingsDialogSelectorContract.CONFIG_READ_ALIAS,
                config, CONFIG_KEY, Boolean.FALSE);
            return Boolean.TRUE.equals(value);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /**
     * Persists the toggle state through the verified {@code UUConfig.b} write — the same
     * call the native remote-connect callback issues for its own key. Failures are
     * swallowed: a persistence hiccup must never break the checkbox or its approval semantics.
     */
    public void store(final boolean enabled) {
        try {
            resolver.invoke(
                EditorIntegrationSettingsDialogSelectorContract.CONFIG_WRITE_ALIAS,
                config, CONFIG_KEY, enabled);
        } catch (RuntimeException ignored) {
            // best-effort persistence; the live toggle state remains authoritative
        }
    }
}
