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
 * capability id is candidate-only until the mapping lands on the reviewed records; any member
 * a record drops still fails the feature closed and the injector stays inert.</p>
 *
 * <p>The injector needs exactly three members: the dialog class (admission evidence), the
 * {@code y.p} static remote-connect checkbox field (anchor for the current row container),
 * and {@code CCheckBox.getJCheckBox()} (unwrap to the Swing component whose parent is the
 * CHBox backing panel). The Phase-2 reinjection seam {@code y.a(V|X)} — the dialog-show
 * entry whose descriptor differs by version — is recorded in the candidate file but is not
 * part of this admission set.</p>
 */
public final class EditorIntegrationSettingsDialogSelectorContract {

    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-editor-model.json:adapterSliceId}";

    /** Capability gating the native-dialog edit-checkbox injection feature. */
    public static final String EDIT_TOGGLE_CAPABILITY_ID =
        "cubism.integration.external-app-settings.edit-toggle";

    /**
     * The complete member set the edit-toggle injection needs on the host: the dialog
     * singleton class, its remote-connect checkbox field, and the Swing-unwrap accessor.
     * Admission checks this set under {@link #EDIT_TOGGLE_CAPABILITY_ID}.
     */
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.integration.external-app-settings.dialog.class",
        "cubism.integration.external-app-settings.dialog.remote-checkbox",
        "cubism.integration.external-app-settings.checkbox.jcheckbox"
    );

    /** Alias of the {@code y.p} static field holding the remote-connect {@code CCheckBox}. */
    public static final String REMOTE_CHECKBOX_ALIAS =
        "cubism.integration.external-app-settings.dialog.remote-checkbox";

    /** Alias of {@code CCheckBox.getJCheckBox()} returning the row-anchored JCheckBox. */
    public static final String JCHECKBOX_ALIAS =
        "cubism.integration.external-app-settings.checkbox.jcheckbox";

    private EditorIntegrationSettingsDialogSelectorContract() {
    }
}
