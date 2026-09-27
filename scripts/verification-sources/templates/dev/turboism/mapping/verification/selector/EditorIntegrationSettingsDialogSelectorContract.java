package dev.turboism.mapping.verification.selector;

import java.util.Set;

/**
 * Exact additive selector contract for the host's native external-application integration
 * settings dialog — the semantic {@code CExternalAppSettingDialog} role, implemented by the
 * Kotlin object singleton {@code com.live2d.cubism.doc.webSocket.y} — that the 「编辑」 edit
 * checkbox injection binds to (spec 051).
 *
 * <p>Every member below is declared with the precise owner internal name, member name, JVM
 * descriptor, and access flags observed on the exact Cubism 5.2.03, 5.3.02, and 5.3.03 host
 * artifacts, evidenced in {@code host-evidence/native-edit-toggle/native-toggle-internals.md}
 * and reviewed in {@code host-evidence/native-edit-toggle/mapping-candidates.json}. The
 * capability admits the whole Phase-2 feature — injection, the show-time re-injection hook,
 * and the persisted toggle state — all-or-nothing: any member a record drops still fails the
 * feature closed and the injector stays inert.</p>
 *
 * <p>Members: the dialog class (admission evidence), the {@code y.p} static remote-connect
 * checkbox field (anchor for the current row container), {@code CCheckBox.getJCheckBox()}
 * (unwrap to the Swing component whose parent is the CHBox backing panel), the private
 * {@code y.b(owner)} dialog build/reuse method whose return is the re-injection seam, and the
 * {@code UUConfig} singleton field plus its {@code a(String,Object)} read /
 * {@code b(String,Object)} write pair that persist the toggle under
 * {@code CExternalAppSettingDialog.EditEnabled}. The public {@code y.a(owner)} show entry is
 * bound in the records as reviewed evidence only: its return fires after the modal dialog
 * closes, so it is not the injection seam — {@code y.b}'s return is.</p>
 */
public final class EditorIntegrationSettingsDialogSelectorContract {

    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-editor-model.json:adapterSliceId}";

    /** Capability gating the native-dialog edit-checkbox injection feature. */
    public static final String EDIT_TOGGLE_CAPABILITY_ID =
        "cubism.integration.external-app-settings.edit-toggle";

    /**
     * The complete member set the edit-toggle feature needs on the host: the dialog
     * singleton class, its remote-connect checkbox field, the Swing-unwrap accessor, the
     * build-method re-injection seam, and the {@code UUConfig} persistence triple.
     * Admission checks this set under {@link #EDIT_TOGGLE_CAPABILITY_ID}.
     */
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.integration.external-app-settings.dialog.class",
        "cubism.integration.external-app-settings.dialog.remote-checkbox",
        "cubism.integration.external-app-settings.checkbox.jcheckbox",
        "cubism.integration.external-app-settings.dialog.build",
        "cubism.integration.external-app-settings.config.instance",
        "cubism.integration.external-app-settings.config.read",
        "cubism.integration.external-app-settings.config.write"
    );

    /**
     * The complete alias set bound in the reviewed records: {@link #REQUIRED_ALIASES} plus
     * {@link #SHOW_ALIAS}, which is recorded as reviewed evidence only (the modal show
     * blocks until close, so it cannot seed the first open). The editor-model manifests
     * match record selectors exactly, so they declare this union.
     */
    public static final Set<String> BOUND_ALIASES = Set.of(
        "cubism.integration.external-app-settings.dialog.class",
        "cubism.integration.external-app-settings.dialog.remote-checkbox",
        "cubism.integration.external-app-settings.checkbox.jcheckbox",
        "cubism.integration.external-app-settings.dialog.show",
        "cubism.integration.external-app-settings.dialog.build",
        "cubism.integration.external-app-settings.config.instance",
        "cubism.integration.external-app-settings.config.read",
        "cubism.integration.external-app-settings.config.write"
    );

    /** Alias of the {@code y.p} static field holding the remote-connect {@code CCheckBox}. */
    public static final String REMOTE_CHECKBOX_ALIAS =
        "cubism.integration.external-app-settings.dialog.remote-checkbox";

    /** Alias of {@code CCheckBox.getJCheckBox()} returning the row-anchored JCheckBox. */
    public static final String JCHECKBOX_ALIAS =
        "cubism.integration.external-app-settings.checkbox.jcheckbox";

    /**
     * Alias of the private {@code y.b(owner)} dialog build/reuse method — the Phase-2
     * re-injection seam. Its return runs after the row container is (re)built and before
     * the modal show call blocks, unlike {@code y.a}'s return which fires post-close.
     */
    public static final String BUILD_HOOK_ALIAS =
        "cubism.integration.external-app-settings.dialog.build";

    /** Alias of the {@code UUConfig.a} static singleton field. */
    public static final String CONFIG_INSTANCE_ALIAS =
        "cubism.integration.external-app-settings.config.instance";

    /** Alias of {@code UUConfig.a(String,Object)} — the defaulting config read. */
    public static final String CONFIG_READ_ALIAS =
        "cubism.integration.external-app-settings.config.read";

    /** Alias of {@code UUConfig.b(String,Object)} — the config write-back. */
    public static final String CONFIG_WRITE_ALIAS =
        "cubism.integration.external-app-settings.config.write";

    /**
     * Alias of the public {@code y.a(owner)} dialog-show entry, bound in the records as
     * reviewed evidence. Not part of {@link #REQUIRED_ALIASES}: the modal show blocks until
     * the dialog closes, so its return cannot seed the checkbox for the first open.
     */
    public static final String SHOW_ALIAS =
        "cubism.integration.external-app-settings.dialog.show";

    private EditorIntegrationSettingsDialogSelectorContract() {
    }
}
