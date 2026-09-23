package dev.turboism.adapter.cubism.integration;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorIntegrationSettingsDialogSelectorContract;

import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ItemEvent;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Injects the 「编辑」 edit checkbox into the host's native external-application integration
 * settings dialog (spec 051, Phase 1 skeleton).
 *
 * <p>The dialog is the Kotlin object singleton {@code com.live2d.cubism.doc.webSocket.y}
 * (the semantic {@code CExternalAppSettingDialog} role; that class name does not exist on
 * the artifacts). Its remote-connect checkbox {@code y.p} is a {@code private static final}
 * {@code CCheckBox}; the injected checkbox is appended to that checkbox's current Swing
 * parent — the {@code CHBox} backing panel whose layout manager
 * ({@code com.live2d.ui.container.a.d}) iterates raw {@code Container.getComponents()}, so
 * an unmanaged {@link JCheckBox} lands as a fixed-width child after the remote checkbox.
 * Row order then matches 5.4: port → toggle → spacer → remote → edit.</p>
 *
 * <p>The dialog rebuilds its whole widget tree whenever it is opened for a different owner
 * window (evidence: {@code y.b(owner)} disposes the cached {@code window.m} and re-parents
 * the static controls into a fresh {@code CHBox}). {@link #ensureInjected()} therefore
 * locates the row by {@code remoteJCheckBox.getParent()} on every call, treats a same-parent
 * injected checkbox as already installed, and re-adds after rebuilds. Phase 2 automates the
 * call: {@link VerifiedEditToggleHookInstaller} instruments the return of {@code y.b(owner)}
 * — the seam chosen over the {@code y.a} show entry because the modal show blocks until the
 * dialog closes — so the checkbox is (re)installed on the EDT before every open.</p>
 *
 * <p>Admission is fail-closed: {@link #fromVerifiedResolver} requires an exact reviewed
 * version and the complete capability/alias set from
 * {@link EditorIntegrationSettingsDialogSelectorContract} — injection members, the
 * {@code y.b} hook seam, and the {@code UUConfig} persistence triple, all-or-nothing. While
 * any piece is missing from the verified record, admission returns {@link Optional#empty()}
 * and nothing is injected — the native dialog is byte-for-byte untouched, which is also the
 * kill-switch ({@value #ENABLED_PROPERTY}{@code =false}) behaviour.</p>
 *
 * <p>The injected component is a plain {@link JCheckBox} whose
 * {@link java.awt.event.ItemListener} forwards the selection into {@link EditToggleState};
 * {@link EditToggleApprovalGate} answers the 050 protocol bridge's approval queries from that
 * state (live-state semantics — no prompt, instant flip), and
 * {@link EditToggleConfigStore} persists it under the host-domain UUConfig key
 * {@code CExternalAppSettingDialog.EditEnabled}. No native control, close, or persistence
 * behaviour is changed.</p>
 */
public final class NativeEditToggleInjector {

    /** Kill switch; any value other than {@code "false"} leaves injection eligible. */
    public static final String ENABLED_PROPERTY =
        "dev.turboism.integration.edit-toggle.enabled";

    /**
     * Label shown by the injected checkbox — the 5.4 row's edit toggle text. Phase-2
     * decision (evidence §12.4): no standalone edit key exists in the artifacts'
     * {@code res/i18n/I18N_cubism3*} catalogs — every CUB3 key with edit semantics is a
     * compound label — so the label stays hardcoded rather than resolving through the
     * {@code b/c.a} localization chain.
     */
    public static final String EDIT_LABEL = "编辑";

    /** Client-property marker on the injected checkbox; diagnostics and tests. */
    public static final String MARKER_KEY = "dev.turboism.editToggle";

    private static final String ADAPTER_SLICE_ID =
        EditorIntegrationSettingsDialogSelectorContract.ADAPTER_SLICE_ID;
    private static final String CAPABILITY_ID =
        EditorIntegrationSettingsDialogSelectorContract.EDIT_TOGGLE_CAPABILITY_ID;
    private static final Set<String> REQUIRED_ALIASES =
        EditorIntegrationSettingsDialogSelectorContract.REQUIRED_ALIASES;

    private final VerifiedMemberResolver resolver;
    private final EditToggleState state;
    // EDT-confined: every touch point below asserts the event dispatch thread.
    private JCheckBox injected;

    private NativeEditToggleInjector(
        final VerifiedMemberResolver resolver,
        final EditToggleState state
    ) {
        this.resolver = resolver;
        this.state = state;
    }

    /**
     * Builds an injector when the verified plan admits the complete edit-toggle surface.
     *
     * <p>Admission is all-or-nothing: exact reviewed version, the capability id, and every
     * required alias must be present, and the two member selectors must keep their reviewed
     * kinds (static field for the remote checkbox, instance method for the Swing unwrap).
     * Any shortfall — missing record, dropped alias, selector-kind drift — returns
     * {@link Optional#empty()} and the dialog stays native.</p>
     *
     * @param resolver the verified member resolver for the running Cubism version
     * @param state    the Turboism-side toggle state the checkbox drives
     * @return the admitted injector, or empty when the feature is not authorized
     */
    public static Optional<NativeEditToggleInjector> fromVerifiedResolver(
        final VerifiedMemberResolver resolver,
        final EditToggleState state
    ) {
        final VerifiedMemberResolver verified = Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(state, "state");
        if (!verified.isExactCubismVersion("5.2.03")
            && !verified.isExactCubismVersion("5.3.02")
            && !verified.isExactCubismVersion("5.3.03")) {
            return Optional.empty();
        }
        if (!verified.authorizesFeature(ADAPTER_SLICE_ID, CAPABILITY_ID, REQUIRED_ALIASES)) {
            return Optional.empty();
        }
        if (!isStaticField(verified, EditorIntegrationSettingsDialogSelectorContract.REMOTE_CHECKBOX_ALIAS)
            || !isInstanceMethod(verified, EditorIntegrationSettingsDialogSelectorContract.JCHECKBOX_ALIAS)) {
            return Optional.empty();
        }
        return Optional.of(new NativeEditToggleInjector(verified, state));
    }

    /** {@return an injector carrying its own fail-closed toggle state} */
    public static Optional<NativeEditToggleInjector> fromVerifiedResolver(
        final VerifiedMemberResolver resolver
    ) {
        return fromVerifiedResolver(resolver, new EditToggleState());
    }

    private static boolean isStaticField(
        final VerifiedMemberResolver resolver,
        final String alias
    ) {
        final StaticSelector selector = resolver.verifiedSelector(alias);
        return selector.kind() == StaticSelector.Kind.FIELD
            && (selector.requiredAccessFlags() & StaticSelector.ACCESS_STATIC) != 0;
    }

    private static boolean isInstanceMethod(
        final VerifiedMemberResolver resolver,
        final String alias
    ) {
        final StaticSelector selector = resolver.verifiedSelector(alias);
        return selector.kind() == StaticSelector.Kind.METHOD
            && (selector.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) != 0;
    }

    /** {@return the toggle state this injector drives} */
    public EditToggleState state() {
        return state;
    }

    /** {@return whether the injected checkbox is currently parented in the live row} */
    public boolean isInjected() {
        requireEdt();
        return injected != null && injected.getParent() != null;
    }

    /**
     * Appends the edit checkbox to the dialog's current top row, or reports that it is
     * already in place. Idempotent and rebuild-aware: the row is rediscovered through the
     * remote checkbox's current parent, so a dialog rebuilt for a different owner gets a
     * fresh checkbox while a reused dialog keeps the existing one.
     *
     * <p>Must run on the EDT; use {@link #ensureInjectedOnEdt()} from other threads.</p>
     *
     * @return {@code true} when the checkbox is parented in the row after this call;
     *         {@code false} when disabled, when the dialog has never been built (the
     *         remote checkbox is not yet parented), or when a verified member fails to
     *         resolve — every miss leaves the dialog untouched
     */
    public boolean ensureInjected() {
        requireEdt();
        if ("false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY))) {
            return false;
        }
        final Component remote = remoteJCheckBox();
        if (remote == null) {
            return false;
        }
        final Container row = remote.getParent();
        if (row == null) {
            return false;
        }
        if (injected != null) {
            if (injected.getParent() == row) {
                return true;
            }
            detach(injected);
            injected = null;
        }
        final JCheckBox edit = new JCheckBox(EDIT_LABEL, state.isEnabled());
        edit.putClientProperty(MARKER_KEY, Boolean.TRUE);
        edit.setFont(remote.getFont());
        edit.setFocusable(remote.isFocusable());
        edit.addItemListener(event ->
            state.setEnabled(event.getStateChange() == ItemEvent.SELECTED));
        row.add(edit);
        row.revalidate();
        row.repaint();
        injected = edit;
        return true;
    }

    /**
     * {@link #ensureInjected()} from any thread. Returns {@code false} on dispatch failure —
     * the dialog is never left half-mutated.
     */
    public boolean ensureInjectedOnEdt() {
        if (SwingUtilities.isEventDispatchThread()) {
            return ensureInjected();
        }
        final boolean[] result = {false};
        try {
            SwingUtilities.invokeAndWait(() -> result[0] = ensureInjected());
        } catch (Exception failure) {
            return false;
        }
        return result[0];
    }

    /**
     * Removes the injected checkbox from the dialog row. Idempotent; a dialog rebuild that
     * already orphaned the component makes this a no-op. Must run on the EDT.
     *
     * @return {@code true} when a live checkbox was detached
     */
    public boolean removeInjected() {
        requireEdt();
        if (injected == null) {
            return false;
        }
        final JCheckBox edit = injected;
        injected = null;
        detach(edit);
        return true;
    }

    private static void detach(final JCheckBox edit) {
        final Container parent = edit.getParent();
        if (parent != null) {
            parent.remove(edit);
            parent.revalidate();
            parent.repaint();
        }
    }

    private Component remoteJCheckBox() {
        try {
            final Object remoteCheckbox = resolver.readStaticField(
                EditorIntegrationSettingsDialogSelectorContract.REMOTE_CHECKBOX_ALIAS);
            if (remoteCheckbox == null) {
                return null;
            }
            final Object component = resolver.invoke(
                EditorIntegrationSettingsDialogSelectorContract.JCHECKBOX_ALIAS,
                remoteCheckbox);
            return component instanceof Component ? (Component) component : null;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                "native dialog injection must run on the EDT");
        }
    }
}
