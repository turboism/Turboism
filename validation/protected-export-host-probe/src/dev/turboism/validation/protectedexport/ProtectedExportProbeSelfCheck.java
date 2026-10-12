package dev.turboism.validation.protectedexport;

import java.awt.Component;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;

/**
 * Offline self-check for the probe's two discrimination rules.
 *
 * <p>The probe decides "which check box did the runtime inject?" and "which button confirms?" purely
 * from the Swing component tree. Both rules are load-bearing, and both would silently produce
 * plausible-but-wrong evidence if they regressed, so they are exercised here on synthetic
 * components before the jar is allowed to exist.</p>
 */
public final class ProtectedExportProbeSelfCheck {

    private ProtectedExportProbeSelfCheck() {
    }

    public static void main(final String[] args) {
        exactClassIsolatesTheInjectedCheckBox();
        buttonRowIdentifiesConfirmAndCancel();
        optionsPeerDispatchesOnExactHostVersion();
        nativeLeafIsolatesTheHostCheckBox();
        traversalTerminatesOnCyclesAndDepth();
        vetoKeyExtractionIsolatesTheKeyLine();
        System.out.println("[self-check] protected-export probe rules OK");
    }

    /** A subclass of {@code JCheckBox} must not be mistaken for a runtime-injected option. */
    private static void exactClassIsolatesTheInjectedCheckBox() {
        final JPanel root = new JPanel();
        final JPanel nested = new JPanel();
        final JPanel deeper = new JPanel();
        root.add(nested);
        nested.add(deeper);
        deeper.add(new NativeLikeCheckBox("native-a"));
        root.add(new NativeLikeCheckBox("native-b"));
        final JCheckBox injected = new JCheckBox("Protected export (candidate; currently unavailable)");
        deeper.add(injected);

        final List<JCheckBox> found = ProtectedExportHostProbeAgent.injectedCheckBoxes(root);
        require(found.size() == 1, "expected exactly one injected check box, found " + found.size());
        require(found.get(0) == injected, "injected check box identity mismatch");
        require(
            ProtectedExportHostProbeAgent.allComponents(root).size() == 6,
            "unexpected component count " + ProtectedExportHostProbeAgent.allComponents(root).size()
        );
    }

    /**
     * Confirm/cancel are told apart by position inside the native button row:
     * the enabled, action-bearing buttons sharing one row container, where the
     * affirmative button is first and the dismissive button is last. The rule
     * must ignore disabled or action-less buttons and must not manufacture a
     * pair from a lone button.
     */
    private static void buttonRowIdentifiesConfirmAndCancel() {
        final JPanel root = new JPanel();
        final JPanel row = new JPanel();
        final JButton confirm = new JButton("OK");
        confirm.setAction(new ConfirmAction());
        final JButton cancel = new JButton("Cancel");
        cancel.setAction(new CancelAction());
        final JButton actionless = new JButton("Decorative");
        final JButton disabled = new JButton("Browse");
        disabled.setAction(new ConfirmAction());
        disabled.setEnabled(false);
        row.add(confirm);
        row.add(cancel);
        root.add(actionless);
        root.add(disabled);
        root.add(row);

        require(
            ProtectedExportHostProbeAgent.confirmButton(root) == confirm,
            "button-row lookup did not return the confirm button"
        );
        require(
            ProtectedExportHostProbeAgent.cancelButton(root) == cancel,
            "button-row lookup did not return the cancel button"
        );

        final JPanel lone = new JPanel();
        final JButton single = new JButton("OK");
        single.setAction(new ConfirmAction());
        lone.add(single);
        require(
            ProtectedExportHostProbeAgent.confirmButton(lone) == null,
            "a single action button must not be treated as the pair's confirm"
        );
        require(
            ProtectedExportHostProbeAgent.cancelButton(lone) == null,
            "a single action button must not be treated as the pair's cancel"
        );
    }

    /**
     * The mount-parent pin is an exact-version dispatch: today every reviewed
     * build resolves to {@code com.live2d.ui.swingImpl.u}.
     */
    private static void optionsPeerDispatchesOnExactHostVersion() {
        for (final String version : new String[] {
            "5000", "5001", "5002", "5003", "5004", "5005", "5006",
            "5100", "5101", "5102", "5103",
            "5200", "5201", "5202", "5203",
            "5300", "5301", "5302", "5303", "5304",
            "", "9999"
        }) {
            require(
                "com.live2d.ui.swingImpl.u".equals(
                    ProtectedExportHostProbeAgent.optionsContainerPeer(version)),
                "options peer for " + version + " must be swingImpl.u"
            );
        }
    }

    /** A subclassed check box is a native leaf; the exact class is the injected row. */
    private static void nativeLeafIsolatesTheHostCheckBox() {
        require(
            ProtectedExportHostProbeAgent.isNativeCheckBoxLeaf(new NativeLikeCheckBox("native")),
            "a JCheckBox subclass must count as a native leaf"
        );
        require(
            !ProtectedExportHostProbeAgent.isNativeCheckBoxLeaf(new JCheckBox("injected")),
            "a plain JCheckBox must never count as a native leaf"
        );
        require(
            !ProtectedExportHostProbeAgent.isNativeCheckBoxLeaf(new JButton("button")),
            "a non-check-box must never count as a native leaf"
        );
    }

    /** Traversal must terminate on a genuine cycle and refuse to recurse without bound. */
    private static void traversalTerminatesOnCyclesAndDepth() {
        require(
            ProtectedExportHostProbeAgent.allComponents(new SelfReferencingPanel()).size() == 1,
            "a self-referencing container must be visited exactly once"
        );
        final JPanel shared = new JPanel();
        final JPanel diamond = new JPanel();
        diamond.add(shared);
        diamond.add(shared);
        require(
            ProtectedExportHostProbeAgent.allComponents(diamond).size() == 2,
            "a shared child reachable by two paths must be visited once"
        );

        JPanel deep = new JPanel();
        final JPanel top = deep;
        for (int i = 0; i < 200; i++) {
            final JPanel child = new JPanel();
            deep.add(child);
            deep = child;
        }
        require(
            ProtectedExportHostProbeAgent.allComponents(top).size() == 41,
            "depth guard misbehaved"
        );
    }

    /**
     * The veto diagnostic message is {@code intro \n\n key \n detail?}; only the
     * first pure config-key line may be extracted — sentences and detail
     * payloads containing punctuation must never be mistaken for the key.
     */
    private static void vetoKeyExtractionIsolatesTheKeyLine() {
        require(
            "protected-export.preflight-failed".equals(
                ProtectedExportHostProbeAgent.vetoKeyFromMessage(
                    "The export was stopped by a Turboism export option.\n\n"
                        + "protected-export.preflight-failed\n"
                        + "protected-export.unpinnable-structure:art-path=1")),
            "session failure key was not extracted"
        );
        require(
            "custom.reason".equals(
                ProtectedExportHostProbeAgent.vetoKeyFromMessage("custom.reason")),
            "a bare plugin key must resolve"
        );
        require(
            "".equals(ProtectedExportHostProbeAgent.vetoKeyFromMessage(
                "No key line here.")),
            "a message without a key line must yield empty"
        );
        require(
            "".equals(ProtectedExportHostProbeAgent.vetoKeyFromMessage("")),
            "an empty message must yield empty"
        );
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /** Reports itself as its own child, which is the only way to build a real containment cycle. */
    private static final class SelfReferencingPanel extends JPanel {
        private static final long serialVersionUID = 1L;

        @Override
        public Component[] getComponents() {
            return new Component[] { this };
        }
    }

    /** Stands in for Cubism's {@code com.live2d.ui.control.CCheckBox} wrapper. */
    private static final class NativeLikeCheckBox extends JCheckBox {
        private static final long serialVersionUID = 1L;

        private NativeLikeCheckBox(final String text) {
            super(text);
        }
    }

    private static final class ConfirmAction extends AbstractAction {
        private static final long serialVersionUID = 1L;

        @Override
        public void actionPerformed(final java.awt.event.ActionEvent event) {
            // No-op: the self-check only needs the action's class identity.
        }
    }

    private static final class CancelAction extends AbstractAction {
        private static final long serialVersionUID = 1L;

        @Override
        public void actionPerformed(final java.awt.event.ActionEvent event) {
            // No-op: the self-check only needs the action's class identity.
        }
    }
}
